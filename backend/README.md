# backend — Núcleo transaccional (Spring Boot 3 / Java 21)

Backend del sistema: puertas, citas, bitácora digital, patio, optimizador, congestión y flota.
Expone la API bajo `/api/v1` y recibe los eventos del servicio de borde (HTTP y MQTT).

## Módulos

| Paquete | Responsabilidad |
|---|---|
| `gate` | Ingesta de eventos OCR, conciliación con citas, máquina de estados de la visita, cola de revisión manual, comprobante y exportación |
| `yard` | Contenedores, slots, bloques, citas, datos maestros (transportistas, conductores, camiones, puertas) |
| `optimizer` | Stacking inteligente: heurística ETD-aware + CP-SAT (OR-Tools) + benchmark vs. FIFO |
| `congestion` | Pronóstico de espera por puerta (colas M/M/c y M/G/c, suavizado exponencial, corrección ML) |
| `fleet` | Ingesta MQTT de telemetría, geocercas, órdenes de trabajo, difusión WebSocket |
| `shared` | Errores (`ProblemDetail`), auditoría, configuración, seguridad, MQTT común, persistencia |

## Capas

`api` (controladores) -> `application` (casos de uso) -> `domain` (reglas) -> `infrastructure` (JPA, MQTT).

- DTOs como `record`, validación con `@Validated`, errores con `ProblemDetail` (RFC 7807).
- Sin lógica de negocio en los controladores: la conciliación vive en `gate/domain` y `gate/application`.
- Migraciones Flyway en `src/main/resources/db/migration` (inmutables).
- Entidades con UUID v7 preasignado: extienden `shared/persistence/AbstractEntity` (`Persistable<UUID>`),
  de lo contrario Spring Data usa `merge` y las mutaciones posteriores al `save` se pierden.

## Puesta en marcha

```bash
./mvnw verify                     # compila, corre las pruebas y verifica la cobertura (JaCoCo)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # servidor local en :8080
docker compose -f infra/docker-compose.yml up -d postgres mosquitto backend
```

- Requisitos: JDK 21 y PostgreSQL 16. El *wrapper* (`./mvnw`) trae Maven: no uses el `mvn` de Windows en WSL.
- Documentación interactiva: `http://localhost:8080/swagger-ui.html` (contrato: `/v3/api-docs`).
- Health: `/actuator/health` · Métricas Micrometer: `/actuator/metrics`.
- Variables de entorno: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `EDGE_API_KEY`, `MQTT_URL`
  (`MQTT_HOST`/`MQTT_PORT` como alternativa, que es lo que define el compose).
  Ver `infra/.env.example`.

## Perfiles

| Perfil | Uso | Qué activa |
|---|---|---|
| *(ninguno)* | producción | JWT contra Keycloak (`app.security.jwt.*`), ingesta con `X-Edge-Api-Key`, MQTT |
| `dev` | desarrollo local | Usuarios en memoria, `POST /api/v1/auth/token`, ingesta abierta (`app.edge.open-ingestion=true`), SQL visible |
| `test` | pruebas | MQTT apagado, secreto de prueba, PostgreSQL embebido (Zonky) |

## Seguridad

Roles (`AppRole`): `OPERADOR_PUERTA`, `SUPERVISOR`, `ADMIN`, `AUDITOR`.

| Operación | Rol |
|---|---|
| Consultar visitas, contenedores, slots, cola de revisión | cualquier usuario autenticado |
| Transiciones de la visita, resolver revisión | `OPERADOR_PUERTA`, `SUPERVISOR`, `ADMIN` |
| CRUD de datos maestros y citas | `SUPERVISOR`, `ADMIN` |
| Configuración de puertas | `ADMIN` |
| `POST /api/v1/gate-events` | servicio de borde (clave de API o ingesta abierta en `dev`) |

El JWT se valida con (en este orden) `app.security.jwt.secret` (HS256), `app.security.jwt.jwk-set-uri`
o `app.security.jwt.issuer-uri`. Los roles se leen del *claim* `roles`
(`["ROLE_SUPERVISOR"]` o `["SUPERVISOR"]`) y de `realm_access.roles` de Keycloak.

### Token del perfil `dev`

```bash
curl -s -X POST 'http://localhost:8080/api/v1/auth/token?username=supervisor&password=supervisor'
# {"access_token":"...","token_type":"Bearer","expires_in":3600}
```

Usuarios en memoria: `operador`, `supervisor`, `admin`, `auditor` (usuario = contraseña).

### Keycloak (producción)

1. Crear el *realm* `logisticaportuaria` y un cliente confidencial `backend`.
2. Crear los roles `OPERADOR_PUERTA`, `SUPERVISOR`, `ADMIN`, `AUDITOR` y asignarlos a los usuarios.
3. En el backend configurar `app.security.jwt.issuer-uri=http://keycloak:8080/realms/logisticaportuaria`
   (o `jwk-set-uri=.../protocol/openid-connect/certs`) y **no** definir `app.security.jwt.secret`.
4. El *claim* de roles debe viajar en el token: *Client scopes -> dedicated -> mapper* de tipo
   *User Realm Role* con el nombre `roles` (o dejar `realm_access.roles`).

### Ingesta desde el borde

`POST /api/v1/gate-events` exige `X-Edge-Api-Key: $EDGE_API_KEY` (`app.edge.api-key`) salvo que
`app.edge.open-ingestion=true` (perfil `dev`/`test`). El servicio de borde nunca recibe un JWT de usuario.

## Ingesta y conciliación

```bash
curl -s -X POST http://localhost:8080/api/v1/gate-events \
  -H 'Content-Type: application/json' \
  -H "X-Edge-Api-Key: $EDGE_API_KEY" \
  -d '{"eventId":"5b0c...","yardId":"1111...","gateId":"3333...","timestamp":"2026-10-22T10:00:00Z",
       "kind":"PLATE","rawText":"SJO-123456","normalizedText":"SJO123456","confidence":0.98}'
```

- Respuesta `201` para un evento nuevo y `200` cuando el `eventId` ya existía (idempotencia).
- También se consume el *topic* MQTT `yard/+/gate/+/events` con el mismo JSON
  (`app.mqtt.enabled`, `app.mqtt.url`). Sin *broker* el suscriptor degrada con avisos y el HTTP sigue operativo.
- Se guarda la lectura cruda y la normalizada (`gate_event`), la evidencia y el resultado.

Reglas (`GateReconciliationRules`, en orden; el primer motivo aplicable gana):

1. Contenedor en `GATE_HOLD` -> `RECHAZADO`.
2. Confianza menor que `app.gate.min-confidence` (0.75) -> `REQUIERE_REVISION`.
3. Código BIC con dígito de control inválido (ISO 6346) -> `REQUIERE_REVISION`.
4. Contenedor que ya salió del patio -> `REQUIERE_REVISION`.
5. Matrícula sin cita en la ventana (`app.gate.window-minutes`, ±30 min) -> `REQUIERE_REVISION`.
6. BIC distinto al de la cita -> `REQUIERE_REVISION`.
7. En cualquier otro caso -> `AUTO_APROBADO`.

Las lecturas que requieren revisión crean una tarea en la cola (`/review-tasks`, estado `PENDIENTE`) y la
bitácora queda en estado `VALIDADO` solo cuando la lectura se aprueba.

### Máquina de estados de la visita

```
ENTRADA_PUERTA -> VALIDADO -> ASIGNADO -> EN_PROCESO -> SALIDA_PUERTA
                                                   \-> CANCELADO
```

Cualquier otra transición responde `409` con `ProblemDetail` (`type: urn:logisticaportuaria:error:conflict`).
La bitácora calcula `waitSeconds` (entrada -> validación), `yardSeconds` (validación -> salida) y
`serviceSeconds` (inicio de proceso -> salida).

## Endpoints

| Método y ruta | Descripción |
|---|---|
| `POST /api/v1/gate-events` | Ingesta de una lectura del borde (idempotente por `eventId`) |
| `GET /api/v1/gate-events` | Consulta paginada de lecturas |
| `GET /api/v1/visits` · `GET /api/v1/visits/{id}` | Bitácora digital y su línea de tiempo |
| `POST /api/v1/visits/{id}/transitions` | Transición de estado (`409` si es inválida) |
| `GET /api/v1/visits/{id}/comprobante` (alias `receipt.pdf`) | Comprobante PDF con QR |
| `GET /api/v1/visits/export.csv` | Exportación de la bitácora (UTF-8 con BOM) |
| `GET /api/v1/review-tasks` · `POST /api/v1/review-tasks/{id}/resolve` | Cola de revisión manual |
| `GET/POST/PUT /api/v1/containers` | Contenedores (BIC normalizado y validado) |
| `GET /api/v1/yard/slots` · `/api/v1/yard/slots/{id}` | Ocupación de slots |
| `GET/POST/PUT` de `/carriers`, `/drivers`, `/trucks`, `/appointments`, `/gates` | Datos maestros y citas |
| `POST /api/v1/optimizer/recommendations` | Top-3 de slots para un contenedor, con explicación en español |
| `POST /api/v1/optimizer/plans` | Plan de stacking por lotes (asíncrono: `202` + `Location`) |
| `GET /api/v1/optimizer/plans/{id}` | Estado y movimientos del plan |
| `GET /api/v1/optimizer/metrics` | Indicadores del patio (ocupación, restows esperados, distancia media) |
| `GET /api/v1/congestion/forecast` | Espera, cola, riesgo, drivers y recomendaciones por horizonte |
| `GET /api/v1/congestion/heatmap` | Matriz histórica de congestión día × hora |
| `GET /api/v1/fleet/vehicles` | Estado actual de la flota (posición, batería, orden activa) |
| `GET /api/v1/fleet/vehicles/{id}/track?from=&to=` | Track GeoJSON `LineString` de un vehículo |
| `GET /api/v1/fleet/kpis` | Utilización, ciclo medio, distancia y latencia de difusión |
| `GET /api/v1/fleet/geofences` | Geocercas del patio (polígonos en metros) |
| `GET/POST /api/v1/fleet/work-orders` · `/{id}/assign` · `/assign-next` · `/{id}/start` · `/{id}/complete` · `/{id}/cancel` | Órdenes de trabajo y su ciclo de vida |
| `GET /api/v1/containers/{code}/trace` | Trazabilidad completa de un contenedor (puerta + patio + flota) |
| `POST /api/v1/auth/token` | Token del perfil `dev` |

Todo se escribe en `audit_log` (actor, acción, entidad, instante en UTC) y se publican métricas
Micrometer: `gate.events.total`, `gate.review.tasks.created`, `gate.review.tasks.resolved`,
`gate.visits.closed`, `gate.review.ratio`, `gate.events.per.minute`, `gate.visit.wait` y `gate.visit.yard`.

## Optimizador de stacking

`/optimizer/recommendations` decide en el borde dónde va **un** contenedor (heurística ETD-aware,
p95 < 200 ms con 5 000 slots). `/optimizer/plans` planifica un **lote** de forma asíncrona y
devuelve `202` con la ruta de consulta; el plan corre en un ejecutor daemon y se descarta al
cabo de 200 planes (estado en memoria, no es una cola duradera).

Estrategias (`strategy` del `POST /plans`):

- `HEURISTIC` — orden ETD descendente + mejor `score` disponible. Es la estrategia del borde.
- `CP_SAT` — modelo de asignación con OR-Tools: poda de candidatos, warm start desde la heurística
  y horizonte rodante de 250 contenedores en orden ETD. Respeta `timeLimitSeconds` (1–600) y
  publica el estado real del solver (`OPTIMAL`/`FEASIBLE`/`UNKNOWN`).

```bash
curl -s -X POST http://localhost:8080/api/v1/optimizer/plans \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"strategy":"HEURISTIC","timeLimitSeconds":10,
       "containerCodes":["TSTU1234568","TSTU1234573"],"apply":false}'
```

- Solo opera sobre contenedores **existentes** en la base (`422` si un código no existe).
- `apply=true` registra la posición (`container_position`) además de devolver los movimientos.
- Un contenedor ya ubicado se informa en `alreadyPlaced` y no se mueve.
- Los roles de lectura (`GET`) incluyen `AUDITOR`; `POST` exige `OPERADOR_PUERTA`, `SUPERVISOR` o `ADMIN`.
- Configuración: `app.optimizer.*` (`max-candidates`, `top-recommendations`, `weights.*`, `plan.time-limit-seconds`).

El modelo matemático, las reglas físicas y las descomposiciones están en
`../docs/architecture/optimizer-model.md`; los resultados medidos (restows, distancia, cómputo,
escenarios S/M/L) en `../docs/experiments/EXP-004-stacking-benchmark.md`.

## Predicción de congestión

`GET /api/v1/congestion/forecast?gateId=&horizon=` devuelve la espera prevista de un camión que
llegue a la puerta dentro de 15, 30 o 60 minutos, con banda p10–p90, longitud de cola, riesgo
(`BAJO`/`MEDIO`/`ALTO`), confianza (`BAJA`/`MEDIA`/`ALTA`), el modelo usado (`COLAS`/`HIBRIDO`),
al menos dos `drivers` en español y recomendaciones accionables. `GET /api/v1/congestion/heatmap?from=&to=`
devuelve la matriz histórica día × hora (7 × 24) con la espera media y el número de muestras.

```bash
curl -s "http://localhost:8080/api/v1/congestion/forecast?gateId=$GATE&horizon=30" \
  -H "Authorization: Bearer $TOKEN"
```

- **Modelo:** M/G/c Allen-Cunneen (cola = camiones esperando carril, no los que ya están en servicio)
  extendido a horizonte con `waitAtHorizon`; corrección del residual con un gradient boosting propio
  (sin dependencias de ML). Cada horizonte tiene su propio modelo y su propio hold-out temporal.
- **Comparación:** el hold-out del escenario sintético de referencia (21 días, semilla 2026) se mide
  contra la línea base ingenua (persistencia) y contra el suavizado Holt-Winters de llegadas; la
  corrección solo se usa si mejora el MAE del hold-out (`MIN 0.05 min`). Resultados en
  `../docs/experiments/EXP-005-congestion/metrics.md`.
- **Sin histórico** (`< 60` franjas) el pronóstico sigue respondiendo con el modelo de colas y
  `confidence=BAJA`: nunca inventa una predicción de ML.
- **Job:** cada 5 minutos recalcula las dos puertas activas y persiste 6 filas en
  `congestion_forecast` con el mismo `computed_at`; purga lo anterior a `retention-days`.
- **Alertas:** con `riskLevel=ALTO` publica en MQTT `yard/{yardId}/alerts/congestion` y, si el broker
  STOMP de P6 está presente, en `/topic/yard/{yardId}/alerts/congestion`.
- **Configuración:** `app.congestion.*` (`history-days`, `retrain-minutes`, `season-length`,
  `medium-risk-minutes`, `high-risk-minutes`, `alerts-enabled`, `alert-topic`).
- **Privacidad:** el modelo solo usa instantes y duraciones de `visit_log`; no lee placas ni
  matrículas.

## Trazabilidad de flota

Los vehículos publican telemetría en MQTT `yard/{yardId}/fleet/{vehicleId}/telemetry`; el suscriptor
valida el esquema, normaliza el BIC y descarta duplicados (`ts` igual), mensajes desordenados (`ts`
anterior) y saltos imposibles (distancia > `maxSpeed · Δt + 5 m`). Cada mensaje aceptado se escribe en
`vehicle_position` (particionada por día) y actualiza `vehicle_last_state`; la ruta caliente de lectura
es una caché Caffeine.

- **Tiempo real:** STOMP `/topic/yards/{yardId}/fleet` (una lista por patio, *throttle* de 1 s) y
  `/topic/yards/{yardId}/alerts` (sin throttle). El *handshake* de `/ws` es público y el frame
  `CONNECT` exige `Authorization: Bearer <jwt>`.
- **Geocercas y alertas:** entrada/salida de geocerca, exceso de velocidad, ralentí prolongado, zona
  prohibida, batería baja y falta de señal > 60 s, con *cooldown* por vehículo.
- **Órdenes de trabajo:** crear, asignar al vehículo libre más cercano (`assign` o `assign-next` por
  prioridad y antigüedad), iniciar, completar y cancelar; `409` si la transición es inválida.
- **Privacidad:** la serie de posiciones se reduce a 1 punto cada 10 s tras 24 h y se purga a los
  30 días (configurable); no se guardan matrículas ni identidades de conductores en la telemetría.
- **Configuración:** `app.fleet.*` (`max-speed-kmh`, `jump-tolerance-m`, `broadcast-interval-ms`,
  `alert-cooldown-seconds`, `silence-threshold-seconds`, `retention-days`, `downsample-after-hours`,
  `trace-window-hours`).
- **Resultados medidos:** latencia de ingesta→STOMP, conteo enviado vs. persistido y pruebas de carga en
  `../docs/experiments/EXP-006-fleet-tracking.md`.
- **Diseño:** `../docs/architecture/adr/ADR-013-marco-local-sin-postgis.md` (marco métrico) y
  `ADR-014-alertas-flota-stomp-at-least-once.md` (alertas, throttle y entrega).

## Pruebas

```bash
./mvnw test        # 132 pruebas (2026-10-22, tras P5)
./mvnw verify      # + JaCoCo: >= 70 % de líneas en los paquetes gate (medido: 83,14 %)
```

- PostgreSQL real sin Docker: `Zonky embedded-postgres` (16.9.0). Si Docker está disponible se puede usar
  Testcontainers; ver `MEMORY.md` §9 (B-001).
- `OpenApiContractTest` valida el contrato `../docs/api/openapi.yaml` (rutas, códigos, `ProblemDetail`).
- `DemoGateFlowScriptTest` ejecuta `infra/scripts/demo_gate_flow.sh` contra un servidor real y comprueba
  que el script registre un ingreso y una salida.
- Demo manual (backend `dev` levantado): `./infra/scripts/demo_gate_flow.sh`.

## Base de datos

- `V1__init.sql` — esquema completo (patio, slots, contenedores, puertas, visitas, flota, auditoría).
- `V2__seed_demo.sql` — patio demo: 1 patio, 6 bloques, 630 slots, 110 contenedores, citas y flota.

Las migraciones **nunca se editan** una vez aplicadas en cualquier entorno: se agrega una nueva `V<n>__`.
Los identificadores son UUID v7, las fechas `timestamptz` en UTC y la UI muestra `America/Costa_Rica`.
