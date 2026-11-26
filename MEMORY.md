# MEMORY.md — Memoria persistente del proyecto LogisticaPortuaria CR

> **Instrucciones para el agente:** lee este archivo al iniciar. Al terminar tu sesión, actualízalo siguiendo la sección 8 de `AGENTS.md`.
> No borres historial: tacha (`~~así~~`) lo obsoleto y explica por qué. Sin secretos ni datos personales reales.

**Última actualización:** 2026-10-23 por backend-agent (P6)

---

## 1. Resumen del proyecto (no editar salvo cambio de alcance)

Sistema Inteligente de Gestión de Patios Logísticos y Reconocimiento de Carga: OCR de matrículas y códigos BIC en el borde, stacking inteligente con OR-Tools, predicción de congestión, trazabilidad de flota en tiempo real y bitácoras digitales. Stack 100 % open source (PaddleOCR/YOLOv8-obb, Spring Boot, React + Tailwind, PostgreSQL, OR-Tools, Docker).

---

## 2. Estado de componentes

| Componente | Agente | Estado | Avance | Última sesión | Notas |
|---|---|---|---|---|---|
| Arquitectura + modelo de datos + docker-compose | architect | En progreso | 92 % | 2026-10-22 (P1, ajustes en P2) | Entregables completos y validados sintácticamente; `compose up` NO VERIFICADO (sin Docker) → §9 B-001. En P2 se corrigieron 2 bugs reales del compose (host MQTT y volumen del outbox) |
| Servicio Edge OCR (matrículas + BIC) | vision-agent | En progreso | 90 % | 2026-10-22 (P2) | Código y 94 pruebas en verde; dataset sintético (240 imgs) + evaluador con 3 modos; motor neuronal NO MEDIDO (sin pesos) → §9 B-007 |
| Backend: puertas, citas, bitácora digital | backend-agent | Listo (DoD cumplido) | 100 % | 2026-10-23 (P3, suite actualizada en P6; revisión architect 2026-10-23) | `./mvnw verify` verde (**190 pruebas** tras P6; 132 tras P5; 88 tras P4); cobertura gate 83,14 % de líneas y total 80,23 %; demo `infra/scripts/demo_gate_flow.sh` ejecutada en la suite; `docker compose up` y Keycloak NO MEDIDO → §9 B-001. Revisión architect (EXP-007): corregidos 2 errores de compilación (`import java.time.Instant` en ContainerRepository y `Locale.ROOT` en GateReconciliationRules); `mvn compile` EXIT 0 y pruebas de unidad pura 88/88 verdes. Suite de integración NO MEDIDO en este entorno Windows/JDK25 → §9 B-013 |
| Motor de stacking (OR-Tools) | optimizer-agent | Listo (DoD cumplido) | 100 % | 2026-10-22 (P4) | Heurística ETD-aware p95 **2 ms** con 5 000 slots; CP-SAT con poda diversificada + `skip` + warm start + horizonte rodante; benchmark S/M/L vs. FIFO y azar (`EXP-004-stacking-benchmark.md`); cobertura `optimizer` 90,05 % de líneas; `./mvnw verify` verde (88 pruebas) |
| Predicción de congestión | optimizer-agent | Listo (DoD cumplido) | 100 % | 2026-10-22 (P5) | M/G/c Allen-Cunneen por horizonte + Holt-Winters + GBM propio; híbrido activo por validación temporal; MAE 3,25/3,92/5,24 min (15/30/60) vs. 8,50/9,62/11,56 de la ingenua; cobertura 85–88 % en p10–p90; API `/congestion/forecast` y `/heatmap`; job de 5 min persiste 6 filas; alerta MQTT/STOMP con riesgo ALTO; `EXP-005` |
| Telemetría de flota en tiempo real | backend-agent | Listo (DoD cumplido) | 100 % | 2026-10-23 (P6) | Ingesta MQTT tolerante a duplicados/desorden/saltos, `vehicle_position` particionada + `vehicle_last_state` + Caffeine; geocercas y 6 tipos de alerta; STOMP `/topic/yards/{yardId}/fleet` (throttle 1 s) y `/alerts`; órdenes de trabajo; track GeoJSON y trazabilidad de contenedor. Carga 50 veh × 1 msg/s × 5 min: **15 000/15 000 sin pérdidas, p95 999,7 ms**. `EXP-006`; Docker/broker real NO MEDIDO (B-001) |
| Frontend (dashboard, mapa, bitácora) | frontend-agent | Pendiente | 0 % | — | |
| Simuladores + pruebas e2e + informe final | qa-docs-agent | Pendiente | 0 % | — | |

Estados válidos: `Pendiente` · `En progreso` · `Bloqueado` · `Listo (DoD cumplido)`.

---

## 3. Decisiones (ADR resumidas)

| ID | Fecha | Decisión | Motivo | Consecuencias | Estado |
|---|---|---|---|---|---|
| ADR-001 | 2026-10-22 | Servicio OCR en Python/FastAPI separado del backend Java | Ecosistema PaddleOCR/Ultralytics es Python; el borde debe correr en CPU sin JVM | Contrato HTTP + MQTT entre servicios; `edge-ocr/schemas/gate-event.schema.json` | Aceptada |
| ADR-002 | 2026-10-22 | Mosquitto MQTT para eventos de puerta y telemetría de flota | Un solo protocolo, broker open source mínimo, los simuladores no dependen del backend | HTTP es el camino primario (idempotente por `eventId`); MQTT es alterno y de telemetría | Aceptada |
| ADR-003 | 2026-10-22 | Optimizador con dos estrategias: heurística ETD-aware + CP-SAT (OR-Tools `com.google.ortools`) | La puerta exige <200 ms; la planificación nocturna dispone de minutos | Se mantiene el benchmark contra línea base FIFO para cuantificar la mejora | Aceptada |
| ADR-004 | 2026-10-22 | Retención TTL de evidencia + blur de rostros + minimización en logs | La matrícula es dato personal (Ley 8968, CR) | La evidencia deja de existir tras el TTL (riesgo asumido y documentado en P8) | Aceptada |
| ADR-005 | 2026-10-22 | Ultralytics (YOLOv8-obb) bajo AGPL-3.0: se usa con condiciones (código fuente disponible, pesos con training reproducible, plan B permisivo documentado) | Es software libre pero con copyleft fuerte; el stack debe ser 100 % open source sin violar licencias | El detector se entrena/exporta con `edge-ocr/train.py`; si el cliente exige licencia permisiva hay que sustituir el detector (interfaz `DetectorProtocol` desacopla el motor) | Aceptada con condición |
| ADR-006 | 2026-10-22 | Toda entidad con UUID v7 preasignado extiende `shared/persistence/AbstractEntity` (`Persistable<UUID>`, `isNew` transitorio, `markPersisted()` en `@PostLoad`/`@PostPersist`) | Spring Data decide `persist` vs. `merge` con `isNew()`; con el id ya asignado hacía `merge` y las mutaciones posteriores al `save()` se perdían **sin error** | Las 15 entidades del backend extienden la clase base; un test de integración cubre la mutación post-`save` | Aceptada |
| ADR-007 | 2026-10-22 | Pruebas de integración con PostgreSQL 16 embebido (Zonky `embedded-postgres` 2.1.0) en lugar de Testcontainers mientras Docker no esté disponible | Sin Docker (B-001) no hay Testcontainers, pero se necesita PostgreSQL real (tipos `jsonb`, `timestamptz`, triggers, Flyway) | Mismo motor real y usuario no-root; la configuración de Testcontainers queda documentada como alternativa | Aceptada con condición |
| ADR-009 | 2026-10-22 | CP-SAT sobre tres descomposiciones: poda de candidatos con diversidad (mitad más barata + mitad repartida) y variable `skip`, warm start desde la heurística y horizonte rodante de 250 contenedores en orden ETD descendente | El modelo completo tiene `\|I\|·\|S\|` booleanos (10⁷ con 2 000 × 5 000) y, con la poda de solo los más baratos, la unión de candidatos era menor que el lote: CP-SAT devolvía `UNKNOWN` tras agotar el tiempo | Medido en L (2 000 contenedores, 15 s): `UNKNOWN`/0 ubicados → `FEASIBLE`/1 969 ubicados y 5 restows añadidos; el plan es una composición de óptimos locales, no un óptimo global (documentado en `docs/architecture/optimizer-model.md`) | Aceptada |
| ADR-010 | 2026-10-22 | Distancia al patio derivada geométricamente: `d = 40 + 60·idxBloque + 6·(bahía−1) + 4·(fila−1) + 1.5·(nivel−1)` metros | El esquema de P1 no guarda distancia por slot y no se podía pedir una migración para una métrica de ordenamiento | La métrica `averageDistanceMeters` es sintética y comparable entre estrategias; si el architect agrega la columna real solo cambia `DistanceModel` | Aceptada con condición |
| ADR-008 | 2026-10-22 | Seguridad del backend: resource server JWT (HS256 con `app.security.jwt.secret`, o JWKS/issuer de Keycloak) + conversor de roles propio + `EdgeApiKeyFilter` para la ingesta + `POST /api/v1/auth/token` solo en perfil `dev` | El conversor por defecto solo lee `scope` y los roles nunca llegaban a `@PreAuthorize`; el borde no puede usar JWT de usuario; no se debe obligar a desplegar Keycloak para probar | Los roles se leen de `roles` y de `realm_access.roles`; la ingesta usa `X-Edge-Api-Key` o `app.edge.open-ingestion`; el token dev es de un solo proceso y con secreto de perfil | Aceptada |
| ADR-011 | 2026-10-22 | Congestión con **un modelo por horizonte** (15/30/60 min), tres enfoques comparados y híbrido colas + GBM propio con *gate* de mejora (≥ 0,05 min de MAE en el hold-out) | Cada horizonte tiene otro régimen; el prompt exige comparar colas, suavizado y ML; el GBM propio evita una dependencia de ML y es determinista | Medido: híbrido 3,25/3,92/5,24 min de MAE frente a 4,91/5,48/7,03 del modelo de colas y 8,50/9,62/11,56 de la ingenua; si la corrección no mejora, se sirve `COLAS`. Detalle en `ADR-011-congestion-gbm-hibrido.md` | Aceptada |
| ADR-012 | 2026-10-22 | Cola = camiones **esperando carril** (`assigned_at IS NULL`); servicio = `assigned_at − entered_at` (1–120 min); λ = perfil estacional (día, hora) *walk-forward*; espera a horizonte = M/G/c + drenaje del backlog (fluido si ρ ≥ 1); sin histórico ⇒ `COLAS` + `confidence=BAJA` | El esquema de P1 no separa la espera en carril y el M/G/c estacionario saturaba a 240 min cerca de ρ = 1 | `queue_length` es la cola real que ve el supervisor; el pronóstico a 15/30/60 min es comparable; el modo degradado nunca inventa ML. Detalle en `ADR-012-cola-perfil-estacional.md` | Aceptada |
| ADR-013 | 2026-10-23 | Marco métrico **local equirectangular** anclado en el patio (lat 9.9908, lon −83.0350; `x` este, `y` norte en metros) en lugar de PostGIS | V1 guarda `vehicle_position.x/y NUMERIC(9,2)` y el seed define las geocercas en metros; el patio mide ~400 × 200 m (error < 0,1 m) y PostGIS exigiría extensión, migración y un JOIN espacial por mensaje | Las geocercas se leen sin reproyectar; el track GeoJSON invierte a `[lon, lat]`; `YardLayout` duplica las constantes de `DistanceModel` sin acoplar `fleet` con `optimizer`. Un segundo patio o escala nacional exige migración nueva a PostGIS. Detalle en `ADR-013-marco-local-sin-postgis.md` | Aceptada |
| ADR-014 | 2026-10-23 | Alertas de flota **solo en memoria** + STOMP con *throttle* de 1 s + entrega **at-least-once** con deduplicación por `ts` y descarte de saltos > `maxSpeed·Δt + 5 m` | V1 no tiene tabla de alertas y las migraciones son inmutables; publicar por mensaje satura el cliente con 50 vehículos; MQTT QoS 1 puede duplicar | `received = persisted + duplicate + outOfOrder + impossibleJumps + invalid + unknown` es la base de la comparación enviado vs. persistido; las posiciones intermedias del throttle quedan en la BD; un reinicio pierde alertas recientes (no posiciones); dos instancias exigen Redis para el *cooldown*. Detalle en `ADR-014-alertas-flota-stomp-at-least-once.md` | Aceptada |

---

## 4. Convenciones vigentes

- Idioma: código en inglés; docs/UI en español (es-CR).
- Zona horaria: almacenar en UTC; mostrar `America/Costa_Rica`.
- IDs: UUID v7 para entidades; el código BIC se guarda normalizado (mayúsculas, sin espacios) y validado con ISO 6346.
- Matrículas CR: normalizar a mayúsculas sin guiones; conservar la lectura cruda en un campo aparte.
- Errores API: `ProblemDetail` (RFC 7807).
- Topics MQTT: `yard/{yardId}/fleet/{vehicleId}/telemetry`, `yard/{yardId}/gate/{gateId}/events`.
- OCR (P2): normalización + ISO 6346 viven en `edge-ocr/app/pipeline/postprocess.py` para que servicio, pruebas y
  evaluador midan **el mismo código**; la tabla de confusiones tiene una sola fuente (`iso6346.CONFUSABLE_GROUPS`,
  simétrica letra/letra, dígito/dígito, letra/dígito) y se filtra por la clase exigida en cada posición.
- Semilla del dataset sintético del edge: `20260222` (240 imágenes, 1280x720, ground truth en CSV). Las
  imágenes no se versionan (`edge-ocr/data/` en `.gitignore`); se regeneran con
  `python tools/make_synthetic_dataset.py --output data/synthetic --count 240 --seed 20260222`.
- Toda métrica que no se pueda medir se escribe **NO MEDIDO** con el motivo, tanto en los informes JSON
  (`notMeasured`) como en `MEMORY.md` §8.
- Persistencia (P3): toda entidad con UUID v7 preasignado **extiende `shared/persistence/AbstractEntity`**
  (`Persistable<UUID>`); sin eso Spring Data hace `merge` y pierde mutaciones posteriores al `save()` sin error.
- JPQL (P3): filtros opcionales siempre `cast(:p as <tipo>) IS NULL OR col = :p` (tipos validados `string`,
  `uuid`, `instant`, `integer`, `boolean`); `:p IS NULL` a secas falla en PostgreSQL.
- Errores (P3): `ResponseStatusException` conserva su estado HTTP en `ProblemDetail`; el `@ExceptionHandler`
  genérico solo cubre lo inesperado (500). Nunca un 401/403/409 convertido en 500.
- Contrato (P3): estados y rutas los fija `docs/api/openapi.yaml` (`ASIGNADO`, no `ASIGNADO_ANDEN`); las rutas
  fuera del contrato se declaran en `OpenApiContractTest` y en §11.
- Ingesta/auditoría (P3): idempotencia por `eventId` (`201`/`200`), lectura cruda + normalizada + evidencia, y
  toda escritura de negocio deja fila en `audit_log`. Los logs no imprimen matrículas completas ni credenciales.
- Pruebas del backend (P3): PostgreSQL embebido (ADR-007), JaCoCo exige **≥ 70 % de líneas en `gate`** (medido:
  83,14 %) y el script de demo se ejecuta en la suite (`DemoGateFlowScriptTest`) para que no se degrade.
- Optimizador (P4): las reglas físicas viven en `PlacementRules` y las comparten heurística, CP-SAT, líneas
  base y pruebas de propiedad; un plan que las viole es un fallo, no una preferencia.
- Optimizador (P4): heurística y chunks de CP-SAT ordenan por **ETD descendente** (desempate por código):
  primero los que salen más tarde, que quedan al fondo de las pilas.
- Optimizador (P4): CP-SAT con `numSearchWorkers=1` y `randomSeed=42`; todo escenario de prueba y del benchmark
  fija su semilla explícita para que el resultado sea reproducible.
- Optimizador (P4): el benchmark compara **restows añadidos** (finales − iniciales del patio), no los del patio,
  porque las estrategias no siempre ubican la misma cantidad de contenedores.
- Congestión (P5): las franjas son de **15 min alineadas a la época UTC**; el calendario usa `America/Costa_Rica`;
  la partición es temporal 80/20 y el perfil estacional solo ve semanas **anteriores** (nunca la franja objetivo).
- Congestión (P5): toda comparación de modelos se hace en **espacio de espera** (minutos) sobre el mismo hold-out;
  el enfoque estadístico pronostica llegadas y se traduce con el mismo modelo de colas.
- Congestión (P5): la entidad `CongestionForecast` mapea `expected_wait_min`, `p10` y `p90` con **`BigDecimal`**
  (`precision=6, scale=2`) porque la migración V1 los declara `NUMERIC(6,2)` y es inmutable; `double` con
  `precision/scale` rompe Hibernate 6.
- Congestión (P5): las columnas de confianza/riesgo son **texto en mayúsculas** (`BAJA`/`MEDIA`/`ALTA`,
  `BAJO`/`MEDIO`/`ALTO`) por los CHECK de V1; los umbrales de riesgo viven en `app.congestion.*`, no en el dominio.
- Flota (P6): la telemetría entra en **WGS84** (`lat`/`lon`) y se convierte a `x`/`y` en metros **solo en el borde**
  del módulo (`YardFrame`); dominio, SQL y alertas trabajan en metros (ADR-013).
- Flota (P6): el `ts` del payload es la fuente de verdad; duplicado = mismo `ts`, desorden = `ts` anterior, salto
  imposible = distancia > `maxSpeed · Δt + 5 m` (no se persiste y se emite `SALTO_GPS`). El último estado válido
  **nunca retrocede** (`vehicle_last_state` con guard `EXCLUDED.at >= at`).
- Flota (P6): `ensure_vehicle_position_partition` no es atómica entre hilos; el repositorio sincroniza la creación y
  tolera el error `already exists` (no se edita la migración del architect).
- Flota (P6): STOMP `/topic/yards/{yardId}/fleet` agrupa **una lista por patio** cada 1 s; `/alerts` se publica
  sin throttle. El *handshake* HTTP de `/ws/**` es público y el frame `CONNECT` se autentica con el mismo
  `JwtDecoder` del REST.
- Flota (P6): toda métrica de latencia se mide **dentro del backend** (recepción → `convertAndSend`) y se publica
  en `GET /fleet/kpis`; el salto de red y el *render* del navegador no se incluyen y se declaran `NO MEDIDO` si
  no hay broker.
- Flota (P6): los umbrales (velocidad máxima, ralentí, batería, silencio, *cooldown*, retención, downsampling) viven
  en `app.fleet.*`; el dominio recibe `AlertRules` como parámetro para poder probarlo con otros valores.
- _(El agente añade aquí nuevas convenciones)_

---

## 5. Supuestos del dominio

- **Jerarquía y física (P1, detalle en EXP-001):** patio → bloques → bahías → filas → niveles; altura máxima 4 (llenos) / 5 (vacíos); un slot es una posición de 20' y un 40' ocupa **dos bahías contiguas** (`yard_slot.bay_span`); una posición en tier > 1 exige que **todo** el footprint inferior esté ocupado (trigger `container_position_support_trg`). Seed: 5 filas × 3 tiers × 7 slots = 105 slots/bloque, 630 por patio.
- **Datos (P1/P2):** ETD de la cita/orden de retiro (si falta, estimación por cliente); tipos 20'/40' dry, reefer y hazmat como restricciones de slot; el dígito de control ISO 6346 es `suma mod 11 mod 10` con pesos 2^posición (la reparación puede devolver **otro** código válido); placas CR simplificadas a `P`+6 dígitos o 2-3 letras + 4-6 dígitos (el backend valida contra la cita, no un catálogo oficial).
- **Modo degradado del edge (P2):** el modo `corrupt` del evaluador es una **simulación** determinista (55 % perfecto, 28 % una sustitución, 12 % inserción/borrado, 5 % dos sustituciones), **no** mide el OCR neuronal; sin pesos `/healthz` da `modelsAvailable=false` y `/v1/recognize` un evento `degraded=true` (nunca lecturas inventadas).
- (P3) El prompt pedía `ASIGNADO_ANDEN/POSICION`; manda el contrato de P1: `ENTRADA_PUERTA → VALIDADO → ASIGNADO → EN_PROCESO → SALIDA_PUERTA`, más `CANCELADO`.
- (P3) El vínculo lectura ↔ visita vive en el JSONB `detail` de `visit_log_event` (sin columna nueva); la conciliación de **contenedor** empareja por `containerCode` y la de **matrícula** por `truck.plate`, dentro de la ventana de la cita.
- (P3) Contenedor en `GATE_HOLD` ⇒ `RECHAZADO`; contenedor ya salido del patio ⇒ `REQUIERE_REVISION`; ventana por defecto `app.gate.window-minutes` (±30 min). Tiempos: `waitSeconds` entrada → validación, `yardSeconds` validación → salida, `serviceSeconds` inicio de proceso → salida (nulo si la etapa no ocurrió).
- (P3) Usuarios `dev` con usuario = contraseña (`operador`, `supervisor`, `admin`, `auditor`), **nunca** fuera de `dev`; las rutas que el contrato no define son extensiones declaradas en `OpenApiContractTest`.
- (P4) `maxTier` se deriva del máximo nivel observado (`SlotView` no lo expone); `slotType` en minúsculas e `IME` → `hazmat`; `POST /optimizer/plans` solo opera contenedores existentes (422 si no) y no reubica los ya ubicados; los planes asíncronos viven en memoria (máx. 200, 2 hilos daemon).
- (P4) El `status` de CP-SAT (`OPTIMAL`/`FEASIBLE`/`UNKNOWN`) describe cada chunk, no garantiza optimalidad del lote.
- (P5) La "cola" de una puerta son los camiones **esperando carril** (`assigned_at IS NULL`), no los que ya están
  en servicio: el estacionario M/G/c se calcula con esa cola y el simulador la reproduce igual.
- (P5) El servicio se aproxima con `assigned_at − entered_at` acotado a 1–120 min (no hay `started_at` fiable);
  la espera que se valida es `wait_seconds` (entrada → validación).
- (P5) Las citas son una **cota inferior** de la demanda: λ = `max(perfil estacional, citas de la próxima hora)`.
  El 60 % de las llegadas del simulador llevan cita.
- (P5) El escenario de referencia del experimento es `(3 carriles, servicio 7 min σ 0,3, 18 llegadas/h base,
  semilla 2026)`: con 12/h la puerta está ociosa y la MAE premia predecir cero (hallazgo de EXP-005).
- (P5) El job de congestión calcula **todas** las puertas activas del patio y persiste los 3 horizontes con el
  mismo `computed_at`; el GET nunca escribe en `congestion_forecast`.
- (P5) La corrección ML se descarta si no mejora el MAE del hold-out en ≥ 0,05 min; en ese caso el pronóstico
  sigue siendo válido con `model=COLAS` (no es un error).
- (P5) Un feriado móvil se calcula por Pascua (Jueves/Viernes Santo); el resto son fechas fijas de Costa Rica.
- (P6) El patio demo se modela en un marco local de ~400 × 200 m; las geocercas del seed son PUERTA (x 0–120, y 0–40),
  HAZMAT (x 300–420, y 0–40) y PROHIBIDA (x 200–260, y 60–100). El `YardLayout` replica el layout de `DistanceModel`
  (40 + 60/bloque + 6/bahía en `x`; 4/fila en `y`) para ubicar slots sin depender del optimizador.
- (P6) `vehicle_position` no guarda batería/combustible/heading: al recargar desde `vehicle_last_state` esos campos
  pueden llegar `null`; la caché Caffeine conserva el último valor completo mientras el proceso vive.
- (P6) La asignación de órdenes despacha primero la de **menor `priority`** (1 es la más urgente) y más antigua, al
  vehículo **libre** más cercano al slot de origen (o al destino, o al centroide de la puerta 60,20 si no hay slots).
- (P6) Las alertas de flota no se persisten (ADR-014): son eventos de la sesión; el histórico auditable son las
  posiciones y las órdenes de trabajo. Si P8 pide histórico, se crea tabla en migración nueva.
- (P6) `vehicle_status` de la telemetría es texto libre validado contra `FleetStatus`; un estado desconocido usa el
  último válido y no invalida el mensaje.
- _(El agente añade/actualiza supuestos aquí, con fecha)_

---

## 6. Bitácora de sesiones (más reciente primero)

<!-- Plantilla — copiar y rellenar al final de cada sesión
### [YYYY-MM-DD] <agente> — Prompt <ID>
- **Hecho:**
- **Archivos creados/modificados:**
- **Decisiones tomadas (IDs):**
- **Pruebas ejecutadas y resultado:**
- **Pendiente / bloqueos:**
- **Experimento asociado:** EXP-NNN
-->

### [2026-10-23] architect — Revisión integral y cierre (EXP-007)
- **Hecho:** revisión del proyecto en su totalidad; corregidos 2 errores de compilación del backend
  (`import java.time.Instant` en `ContainerRepository`; formateo con `Locale.ROOT` en
  `GateReconciliationRules` para evitar decimal dependiente del locale `es_CR`); `mvn compile` EXIT 0;
  pruebas de unidad pura 88/88 verdes (11 clases); `docker compose config` válido; OpenAPI válido.
- **Archivos creados/modificados:** `README.md` (raíz, nuevo);
  `backend/src/main/java/cr/logisticaportuaria/yard/infrastructure/persistence/ContainerRepository.java`;
  `backend/src/main/java/cr/logisticaportuaria/gate/domain/GateReconciliationRules.java`;
  `docs/experiments/EXP-007-revision-completado-backend.md` (nuevo); `MEMORY.md`.
- **Decisiones tomadas (IDs):** ninguna nueva; se reportan bloqueos de entorno como NO MEDIDO.
- **Pruebas ejecutadas y resultado:** backend compila; 88 pruebas de unidad pura verdes (0 fallos) tras la
  corrección. Suite de integración NO MEDIDO (embedded Postgres Linux no corre en Windows, Mockito sin
  auto-adjuntar en JDK 25, JaCoCo 0.8.12 no soporta clases Java 25). Frontend/edge-ocr/simuladores/e2e NO
  MEDIDO (no hay Node ni Python).
- **Pendiente / bloqueos:** B-001 (Docker), B-003 (Node), B-005 (Python), B-013 (suite de integración del
  backend en este entorno). P7 (frontend) sigue al 0 %.
- **Experimento asociado:** EXP-007

### [2026-10-23] backend-agent — Prompt P6
- **Hecho:** módulo de trazabilidad de flota completo en `backend/.../fleet/`:
  - **Dominio:** `YardFrame` (marco métrico), `YardLayout`, `GeoPolygon` (ray casting + centroide), `Geofence`,
    `FleetTelemetry`, `AlertRules`, `FleetAlert`, `VehicleState`, `TrackGeometry`.
  - **Aplicación:** `FleetTelemetryService` (validación, dedupe por `ts`, saltos imposibles, geocercas, alertas,
    persistencia + upsert), `FleetQueryService`, `WorkOrderService` (ciclo + asignación más cercana),
    `FleetMetrics`, `FleetStateCache` (Caffeine), `AlertStateStore`, `GeofenceCache`, `FleetAlertBuffer`.
  - **API:** `FleetController` (`/fleet/vehicles`, `/{id}/track`, `/fleet/kpis`, `/fleet/geofences`,
    `/fleet/work-orders` + assign/assign-next/start/complete/cancel) y `ContainerTraceController`
    (`/containers/{code}/trace`).
  - **Infraestructura:** suscriptor MQTT, `FleetBroadcaster` + scheduler de 1 s, `FleetWebSocketConfig` (STOMP
    `/ws`), `FleetStompAuthInterceptor`, `FleetSilenceMonitor`, `FleetRetentionJob` y repositorios JDBC.
  - `simulators/fleet_sim.py` (5–50 vehículos, semilla fija, 6 fallos inyectados) + `simulators/tests/test_fleet_sim.py`.
  - `ADR-013`, `ADR-014`, secciones de flota en `backend/README.md` y `simulators/README.md`.
- **Archivos creados/modificados:** ~35 clases de producción y 11 de prueba en
  `backend/src/main/java/cr/logisticaportuaria/fleet/`, `application.yml` (`app.fleet.*`), `application-test.yml`,
  `SecurityConfig` (`/ws/**`), `simulators/`, `backend/README.md`, `docs/experiments/EXP-006-fleet-tracking.md`,
  `docs/architecture/adr/ADR-013-*.md` y `ADR-014-*.md`.
- **Decisiones tomadas (IDs):** ADR-013 (marco local sin PostGIS) y ADR-014 (alertas en memoria, STOMP con
  throttle y at-least-once).
- **Pruebas ejecutadas y resultado:** `./mvnw verify` → **BUILD SUCCESS**, **190 pruebas**, cobertura total
  **80,23 %** de líneas y módulo `fleet` **80,88 %** (`gate` 83,14 %, umbral OK). 48 unitarias +
  `FleetIntegrationTest` 9/9 + `FleetLoadTest` en verde; `simulators/tests` 8/8 con `ruff`/`black` limpios;
  `OpenApiContractTest` 5/5. Carga de 50 vehículos × 1 msg/s × 5 min: **15 000 enviados = 15 000 recibidos =
  15 000 persistidos = 15 000 filas nuevas**, 0 rechazados, p50 **996,6 ms**, p95 **999,7 ms**. Corrida de
  5 s: 250/250, p95 999,5 ms. **NO MEDIDO:** broker MQTT real, `compose up` y latencia de red del navegador
  (B-001).
- **Pendiente / bloqueos:** sin bloqueos nuevos; B-001/B-003/B-004/B-010/B-011 siguen abiertos. Los ADR-013/014
  quedan para revisión del architect.
- **Experimento asociado:** EXP-006

### [2026-10-22] optimizer-agent — Prompt P5
- **Hecho:** módulo de predicción de congestión completo en `backend/.../optimizer/congestion/`:
  - **Dominio:** `QueueingModel` (M/M/c, Allen-Cunneen, cuantiles, `waitAtHorizon` con backlog fluido),
    `ExponentialSmoothing` (Holt-Winters aditivo + `walkForward` sin fuga), `GradientBoostingRegressor` propio,
    `CongestionForecaster` (un `HorizonModel` por horizonte), `CongestionFeatures`, `BacktestRow`, `ModelMetrics`,
    `CostaRicaCalendar`, `HeatmapBuilder`, `RecommendationRules`, `CongestionForecast` (entidad JPA).
  - **Aplicación:** `CongestionService` (caché por puerta), `CongestionScheduler` (job de 5 min),
    `CongestionProperties`, DTOs.
  - **API:** `GET /api/v1/congestion/forecast?gateId=&horizon=` y `GET /api/v1/congestion/heatmap?from=&to=`.
  - **Infraestructura:** repositorios `JdbcTemplate`/JPA, `CongestionAlertPublisher` (MQTT
    `yard/{yardId}/alerts/congestion`) y `CongestionAlertBroadcaster` (STOMP).
  - **Simulador DES propio** (`CongestionScenarioSimulator`, semilla 2026) y experimento reproducible que
    regenera `docs/experiments/EXP-005-congestion/{metrics.md,backtest-h*.csv}`.
  - `ADR-011`, `ADR-012` y sección "Predicción de congestión" en `backend/README.md`.
- **Archivos creados/modificados:** 21 clases de producción y 11 de prueba en
  `backend/src/main/java/cr/logisticaportuaria/optimizer/congestion/`, `application.yml` (`app.congestion.*`),
  `backend/README.md`, `docs/experiments/EXP-005-congestion.md`, `docs/experiments/EXP-005-congestion/`,
  `docs/architecture/adr/ADR-011-*.md` y `ADR-012-*.md`.
- **Decisiones tomadas (IDs):** ADR-011 (GBM propio + híbrido por horizonte con gate) y ADR-012 (cola = esperando
  carril, perfil estacional walk-forward, espera a horizonte y modo degradado).
- **Pruebas ejecutadas y resultado:** `./mvnw verify` → **BUILD SUCCESS**, **132 pruebas** (44 del paquete
  `congestion`), JaCoCo OK (`gate` 83,14 %); cobertura `optimizer.congestion` **91,0 %** de líneas (dominio
  94,2 %, aplicación 86,1 %, persistencia 96,2 %, `infrastructure/mqtt` 65,0 %). Métricas del escenario de
  referencia (21 días, 7 064 visitas, semilla 2026): MAE híbrido **3,25/3,92/5,24 min** (15/30/60) frente a
  colas 4,91/5,48/7,03, Holt-Winters 7,88/10,23/12,36 e ingenua 8,50/9,62/11,56; mejora vs. ingenua 61,8/59,2/54,7 %;
  cobertura p10–p90 85,0/86,9/87,9 %; entrenamiento 488–576 ms; latencia de pronóstico p50 0,30–0,38 ms,
  p95 0,93–1,36 ms, p99 2,6–3,6 ms. **NO MEDIDO:** broker MQTT real y `compose up` (B-001).
- **Pendiente / bloqueos:** sin bloqueos nuevos; Docker (B-001), git (B-010) y el ruido de Surefire (B-011) siguen
  abiertos. Los ADR-011/012 quedan en `docs/architecture/` (ámbito del architect) para su revisión.
- **Experimento asociado:** EXP-005

### [2026-10-22] optimizer-agent — Prompt P4
- **Hecho:** motor de stacking sobre el patio real: `PlacementRules` (reglas físicas compartidas),
  `SlotScorer` (coste de 5 términos + explicación), heurística ETD-aware, líneas base FIFO/azar, CP-SAT de
  OR-Tools, `YardMetrics` y `ScenarioGenerator`; API `POST /optimizer/recommendations` (Top-3),
  `POST /optimizer/plans` asíncrono, `GET /optimizer/plans/{id}` y `GET /optimizer/metrics`; modelo en
  `docs/architecture/optimizer-model.md` y benchmark S/M/L reproducible.
- **Archivos:** 22 clases de producción y 6 de prueba en `optimizer/`, `application.yml`, `backend/README.md`
  y `EXP-004-stacking.{md}` + `EXP-004-stacking-benchmark.{csv,md}`. *Detalle completo en §13 y EXP-004.*
- **Decisiones:** ADR-009 (descomposiciones de CP-SAT) y ADR-010 (distancia sintética).
- **Pruebas:** `./mvnw verify` **BUILD SUCCESS**, **88 pruebas** (24 del módulo), 2:51 min, `gate` 83,14 % y
  `optimizer` **90,05 %** de líneas. Stacking p50/p95/p99 = 0/2/3 ms con 5 000 slots (n = 200); restows
  añadidos vs. FIFO: S 66→0, M 385→0, L 1 639→26 (heurística) y 5 (CP-SAT). **NO MEDIDO:** `compose up` (B-001).
- **Pendiente / bloqueos:** sin bloqueos nuevos (git B-010 y ruido de Surefire B-011 siguen abiertos).
- **Experimento asociado:** EXP-004

### [2026-10-22] backend-agent — Prompt P3
- **Hecho:** núcleo transaccional completo en `backend/`: ingesta HTTP/MQTT idempotente, conciliación con reglas
  ordenadas, bitácora con máquina de estados y tiempos, cola de revisión, CRUD de datos maestros, comprobante PDF
  con QR, `export.csv`, JWT con 4 roles + perfil `dev`, clave del borde, auditoría y Micrometer.
- **Pruebas:** `./mvnw verify` verde, **64 pruebas** (9 clases), `gate` 83,14 % de líneas; demo real de ingreso y
  salida (112 ms, PDF 1941 bytes, CSV 2 filas). **NO MEDIDO:** `compose up`, Swagger UI y Keycloak (B-001).
- **Decisiones:** ADR-006/007/008. **Pendiente:** Docker, Node (B-003), git (B-010). **Experimento:** EXP-003.

### [2026-10-22] vision-agent — Prompt P2
- **Hecho:** servicio Edge OCR completo, dataset de 240 imágenes, evaluador de 3 modos y Dockerfile GPU.
  94 pytest verdes; post-proceso 60,00 % → **74,58 %**, CER 0,0347, check digit 95,83 %, p95 11,456 ms.
  **NO MEDIDO:** OCR neuronal y latencia por frame (B-007). **Exp.:** EXP-002. *(Detalle completo en §13.)*

### [2026-10-22] architect — Prompt P1
- **Hecho:** monorepo, V1/V2, OpenAPI 3.1, JSON Schema del evento, compose, Makefile, C4, ADR-001..004 y
  READMEs (41 archivos). **Validado:** SQL con `pglast` (92/92), OpenAPI con `openapi-spec-validator`.
  **NO ejecutado:** `compose up`, `./mvnw verify`, `npm run build`. **Exp.:** EXP-001. *(Detalle en §13.)*

---

## 7. Índice de experimentos

| ID | Fecha | Prompt | Agente/Modelo | Resultado resumido | Calidad (1-5) | Archivo |
|---|---|---|---|---|---|---|
| EXP-001 | 2026-10-22 | P1 | architect / big-pickle | Modelo de datos, contratos, infra Docker y ADRs creados y validados sintácticamente; `compose up` no verificable (sin Docker) | 4 | `docs/experiments/EXP-001-arquitectura-modelo-datos-docker.md` |
| EXP-002 | 2026-10-22 | P2 | vision-agent / big-pickle | Servicio Edge OCR con 94 pruebas en verde; post-proceso sube la exactitud de 60.00 % a 74.58 % sobre 240 imágenes sintéticas; OCR neuronal NO MEDIDO (sin pesos) | 4 | `docs/experiments/EXP-002-edge-ocr-bic.md` |
| EXP-003 | 2026-10-22 | P3 | backend-agent / big-pickle | Backend de puertas, citas y bitácora digital: 64 pruebas verdes, `verify` verde con 83,14 % de líneas en `gate`, demo real de ingreso/salida con tiempos, PDF y CSV; Docker/Keycloak NO MEDIDO | 4 | `docs/experiments/EXP-003-gate.md` |
| EXP-004 | 2026-10-22 | P4 | optimizer-agent / big-pickle | Stacking ETD-aware (p95 2 ms con 5 000 slots) + CP-SAT con poda diversificada, warm start y chunks: restows añadidos vs. FIFO 66→0 (S), 385→0 (M), 1 639→26 (heurística) y 5 (CP-SAT) en L; 88 pruebas, cobertura `optimizer` 90,05 % | 4 | `docs/experiments/EXP-004-stacking.md` |
| EXP-005 | 2026-10-22 | P5 | optimizer-agent / big-pickle | Congestión a 15/30/60 min con M/G/c, Holt-Winters y GBM propio; híbrido activo con MAE 3,25/3,92/5,24 min vs. 8,50/9,62/11,56 de la ingenua (-55 a -62 %), cobertura p10–p90 85–88 %, p95 de pronóstico 0,93–1,36 ms; API, job de 5 min y alertas; 44 pruebas del módulo y `verify` verde con 132 | 4 | `docs/experiments/EXP-005-congestion.md` |
| EXP-006 | 2026-10-23 | P6 | backend-agent / big-pickle | Trazabilidad de flota: 48 unitarias + 9 integración + 1 carga + 8 del simulador; 50 veh × 1 msg/s × 5 min = 15 000 mensajes sin pérdidas y p95 999,7 ms; geocercas y 6 alertas; órdenes de trabajo, track GeoJSON y traza de contenedor; broker MQTT real NO MEDIDO | 4 | `docs/experiments/EXP-006-fleet-tracking.md` |
| EXP-007 | 2026-10-23 | Revisión integral (architect) | architect / opencode | Corrección de 2 errores de compilación del backend y verificación: `mvn compile` EXIT 0, 88 pruebas de unidad pura verdes; suite de integración/frontend/edge-ocr/simuladores NO MEDIDO por entorno; `README.md` raíz creado | 4 | `docs/experiments/EXP-007-revision-completado-backend.md` |

---

## 8. Métricas acumuladas (solo valores medidos)

| Métrica | Valor actual | Meta | Fuente (EXP) |
|---|---|---|---|
| Exactitud OCR código BIC completo (con check digit válido) | NO MEDIDO (OCR neuronal) · **74.58 %** en la capa de post-proceso | ≥ 95 % | EXP-002 |
| Exactitud OCR matrícula | NO MEDIDO (OCR neuronal) · 74.58 % incluye placas en la capa de post-proceso | ≥ 92 % | EXP-002 |
| Latencia OCR por frame (p95, CPU) | NO MEDIDO (completa) · **11.46 ms** p95 de recorte+preprocesado | ≤ 800 ms | EXP-002 |
| CER del post-proceso sobre el dataset sintético | 0.0347 (raw 0.0472) | — | EXP-002 |
| Lecturas de contenedor con dígito de control válido | 95.83 % (115/120) | ≥ 95 % | EXP-002 |
| Lecturas corregidas (`corrected=true`) | 16.67 % (20/120) | — | EXP-002 |
| Reducción de reubicaciones (restows añadidos) vs. FIFO | **100 %** en S (66 → 0) y M (385 → 0); **98,4 %** en L (1 639 → 26 heurística; 5 CP-SAT) | ≥ 30 % | EXP-004 |
| Latencia de recomendación de stacking (p50/p95/p99, 5 000 slots) | **0 / 2 / 3 ms** (n = 200) | p95 < 200 ms | EXP-004 |
| Cómputo de un plan de 2 000 contenedores | 782 ms (heurística) · 15,8 s (CP-SAT, agota el límite de 15 s) | — | EXP-004 |
| Cobertura del paquete `optimizer` | **90,05 % de líneas** (824/915) | — | EXP-004 |
| MAE predicción de espera en puerta | **3,25 / 3,92 / 5,24 min** (híbrido, horizontes 15/30/60; escenario sintético 21 días, semilla 2026) · colas 4,91/5,48/7,03 · ingenua 8,50/9,62/11,56 | ≤ 5 min | EXP-005 |
| Mejora del MAE de congestión vs. línea base ingenua | **61,8 % / 59,2 % / 54,7 %** (15/30/60 min) | — | EXP-005 |
| Cobertura del intervalo p10–p90 de la espera | **85,0 % / 86,9 % / 87,9 %** (meta 80 %) | ≈ 80 % | EXP-005 |
| Latencia del pronóstico de congestión (n = 300) | p50 **0,30–0,38 ms** · p95 **0,93–1,36 ms** · p99 2,6–3,6 ms | — | EXP-005 |
| Entrenamiento de congestión (3 horizontes, 1 531 franjas) | 488–576 ms | — | EXP-005 |
| Cobertura de líneas del paquete `optimizer.congestion` | **91,0 %** (975/1 072; dominio 94,2 %, aplicación 86,1 %, persistencia 96,2 %, `infrastructure/mqtt` 65,0 %) | — | EXP-005 |
| Latencia telemetría → pantalla | **999,7 ms p95** (recepción backend → `convertAndSend`; el salto de red y el render del navegador: NO MEDIDO) | ≤ 2 s | EXP-006 |
| Mensajes de flota perdidos en carga (50 veh × 1 msg/s × 5 min) | **0** (15 000 enviados = recibidos = persistidos = filas nuevas) | 0 | EXP-006 |
| Pruebas de flota y del simulador | **48 unitarias + 9 integración + 1 carga** (backend) y **8** (pytest) | — | EXP-006 |
| Cobertura de líneas del módulo `fleet` | **80,88 %** (969/1 198: dominio 93,27 %, aplicación 87,89 %, persistencia 81,31 %, api 85,00 %, websocket 75,00 %, `infrastructure/mqtt` 20,00 %, monitoring 0,00 %) | — | EXP-006 |
| Cobertura de pruebas backend | **83,14 % de líneas en paquetes `gate`** (853/1026, umbral 70 %); total del backend **80,23 %** (4 370/5 447); ramas 61,37 % | ≥ 70 % (gate) | EXP-003 / EXP-006 |
| Pruebas automatizadas del backend | ~~**132** (todas en verde, 24 clases; 88 en P4)~~ → **190** (todas en verde, tras P6: 48 unitarias de flota + 9 de integración + 1 de carga) | — | EXP-003 / EXP-004 / EXP-005 / EXP-006 |
| Latencia de ingesta de un evento de puerta (extremo a extremo, local) | 112 ms la primera lectura (con conciliación y escritura); 35 ms una lectura de contenedor | — | EXP-003 |
| Latencia de una transición de estado | 19-31 ms | — | EXP-003 |
| Comprobante PDF de una visita | 1941 bytes (`application/pdf`) | — | EXP-003 |
| Patio demo (verificado contra la BD real) | 630 slots, 110 contenedores y 110 posiciones activas | ≥ 500 slots / ≥ 80 contenedores | EXP-001 / EXP-003 |
| Códigos BIC del seed con dígito de control válido | 100 % (110/110) | 100 % | EXP-001 |
| Imágenes del dataset sintético del edge | 240 (120 contenedores + 120 placas) | ≥ 200 | EXP-002 |
| Pruebas automatizadas del edge | 94 (todas en verde) | — | EXP-002 |
| Pruebas de unidad pura del backend (11 clases, este entorno Windows/JDK25) | 88 / 88 verdes, 0 fallos | 88 / 88 | EXP-007 |

---

## 9. Problemas abiertos

| ID | Descripción | Componente | Severidad | Detectado | Responsable |
|---|---|---|---|---|---|
| B-001 | Docker no utilizable en este WSL: el shim de Docker Desktop responde "could not be found in this WSL 2 distro" y el distro `docker-desktop` está `Stopped`. Acción del usuario: abrir Docker Desktop en Windows y activar *Settings → Resources → WSL Integration → Ubuntu* | infra | Alta | 2026-10-22 | usuario / architect |
| B-002 | ~~No hay JDK en Linux y el `mvn` del PATH es el de Windows (`/mnt/c/Program Files/Apache/maven/...`): `./mvnw verify` no es ejecutable.~~ **Resuelto (2026-10-22, P3):** Temurin 21 en `~/.local/jdk21` y Maven 3.9.9 en `~/.local/apache-maven-3.9.9`; el wrapper `only-script` de `backend/` los usa. Sigue siendo obligatorio exportar `JAVA_HOME` antes de compilar y **no** usar el `mvn` de Windows | backend | ~~Alta~~ Resuelta | 2026-10-22 | backend-agent |
| B-003 | Node/npm no instalados: `npm run lint/test/build` no ejecutable | frontend | Media | 2026-10-22 | frontend-agent |
| B-004 | `make` no instalado: los targets del Makefile no se ejecutan; se validan leyendo el archivo | infra | Baja | 2026-10-22 | qa-docs-agent |
| B-005 | `python3 -m venv` falla (PEP 668 + sin `ensurepip`). Workaround: `python3 -m venv --without-pip` + `get-pip.py` | edge-ocr | Media | 2026-10-22 | vision-agent |
| B-006 | `sudo` exige password interactiva: nada automatizado puede instalar paquetes del sistema | transversal | Media | 2026-10-22 | usuario |
| B-007 | No hay runtime de inferencia (Ultralytics + PaddleOCR) ni pesos de modelos: `tools/evaluate.py --mode live` responde **NO MEDIDO** (exit 2) y la exactitud/latencia del OCR neural quedan sin medir. Acción: montar pesos en `/models` (`yolov8n-obb.pt` fine-tuneado o el modelo preentrenado) y construir la imagen `VARIANT=gpu` o instalar `ultralytics` + `paddleocr` en el entorno | edge-ocr | Alta | 2026-10-22 | vision-agent / usuario |
| B-008 | JaCoCo falla al generar el reporte con `Unknown block type 50` si `backend/target/jacoco.exec` quedó corrupto (p. ej. tras matar una JVM de pruebas). Workaround verificado: `rm -f target/jacoco.exec && ./mvnw verify` | backend | Baja | 2026-10-22 | backend-agent |
| B-009 | `jq` y `shellcheck` no están instalados: los scripts (`infra/scripts/demo_gate_flow.sh`) parsean JSON con `python3` y se validan con `bash -n` en lugar de `shellcheck` | transversal | Baja | 2026-10-22 | qa-docs-agent |
| B-011 | Tras las pruebas con contexto Spring (PostgreSQL embebido) Surefire imprime `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0)`: una hebra no-daemon del proceso embebido mantiene viva la JVM ~30 s. **No rompe el build** (solo alarga `verify`); pendiente de aislar | backend | Baja | 2026-10-22 | backend-agent |
| B-010 | El repositorio **no es git**: no hay `git init`, identidad configurada ni commits. Todo el trabajo de P1-P3 está sin versionar (riesgo de pérdida). Acción: `git init` + `git config user.name/user.email` y primer commit `feat:` | transversal | Alta | 2026-10-22 | usuario |
| B-012 | JaCoCo puede generar un reporte con **0 % de cobertura** si `target/` proviene de otra compilación: imprime cientos de avisos `Execution data for class … does not match` y **`jacoco:check` pasa igualmente** (no falla si ninguna clase coincide). No es corrupción del `.exec` (eso es B-008). Workaround verificado: borrar `backend/target/` y repetir `./mvnw verify`. Mejora pendiente: hacer que el build falle si `gate` no alcanza el umbral | backend | Media | 2026-10-23 | backend-agent |
| B-013 | La suite de integración del backend no corre en este entorno Windows/JDK 25: embedded Postgres son binarios Linux (no se ejecutan en Windows), Mockito no puede auto-adjuntarse al VM en JDK 25 y JaCoCo 0.8.12 no soporta el formato de clase de Java 25 (major version 69). Solo se pudieron ejecutar pruebas de unidad pura (88/88 verdes). No es un fallo de código. | backend | Alta | 2026-10-23 | architect |

---

## 10. Lecciones aprendidas (sobre prompts y agentes)

- Pedir explícitamente "incluye pruebas y Dockerfile" reduce iteraciones de 3 a 1.
- **Separar la lógica en módulos reutilizables desde el inicio** (`postprocess.py`, `PlacementRules`): el
  evaluador y las cuatro estrategias del optimizador miden **el mismo código**, no copias.
- **Toda sentencia DDL o JPQL se prueba contra el motor real en la misma iteración**: un índice a una columna
  inexistente y `:p IS NULL` solo fallaron en ejecución. Los **conteos del seed solo se verifican contra la
  base real** (faltaban bahías: 450 slots en vez de 630).
- **Los flags que dependen de varias etapas se combinan con OR**, no se sobrescriben (el consenso del OCR
  borraba `corrected`); la tabla de confusiones debe ser simétrica y filtrarse por la clase exigida.
- **Los colaboradores con efectos secundarios se crean en el evento *startup*** (lifespan/`@PostConstruct`),
  no al importar: así el módulo es importable en tests y contenedores.
- **`merge` silencioso de Spring Data:** con id preasignado (UUID v7) `save()` hace `merge` y pierde mutaciones
  sin excepción → `Persistable<UUID>` con `isNew` + `markPersisted()`. Un `@ExceptionHandler(Exception.class)`
  se come 401/403/409; y el conversor de roles por defecto solo lee `scope`.
- **Un script de demo sin prueba se degrada**: ejecutarlo desde la suite (`DemoGateFlowScriptTest`).
- **Un modelo CP-SAT con poda de candidatos puede volverse infactible**: dar siempre salida ("no ubicado" con
  penalización) y acotar el tamaño del modelo. **El coste estático no ve la interferencia dentro del lote**: si
  el problema tiene una dimensión temporal (ETD), procesar en ese orden es parte del algoritmo.
- **Una métrica en unidades equivocadas no la detectan las pruebas**: el factor ×4 de la tasa de llegadas solo
  apareció al revisar un caso de punta a mano (354 % de utilización); los tests comparaban modelo contra modelo.
- **El esquema de la BD y la entidad tienen que hablar el mismo tipo**: `NUMERIC(6,2)` + `double` con
  `precision/scale` rompe Hibernate 6; con migraciones inmutables la salida es `BigDecimal` o migración nueva.
- **Un modelo de colas estacionario no sirve cerca de ρ = 1**: hay que acotar con la aproximación fluida del
  backlog, o el pronóstico devuelve el tope (240 min) y empeora la MAE.
- **MAPE no es comparable con esperas cercanas a cero**: se reporta con la nota explícita y la decisión se toma
  con MAE/RMSE; una métrica mal elegida puede premiar predecir cero.
- **`CREATE ... IF NOT EXISTS` dentro de una función PL/pgSQL no es atómico entre hilos**: dos ingestas simultáneas
  crean la misma partición y una recibe `already exists`; hay que sincronizar en la aplicación y tolerar el error.
- **Un reporte de cobertura al 0 % puede pasar el `check`**: conviene borrar `target/` (B-012) y desconfiar de un
  «All coverage checks have been met» cuando el log está lleno de «Execution data does not match».

---

## 11. Solicitudes entre agentes

| De | Para | Solicitud | Estado |
|---|---|---|---|
| vision-agent (P2) | architect | **ADR-005** creado en `docs/architecture/adr/` (fuera del ámbito del vision-agent) para registrar la licencia AGPL-3.0 de Ultralytics. Decidir si el detector definitivo se sustituye por una alternativa permisiva antes del despliegue comercial | Abierta |
| vision-agent (P2) | backend-agent (P3) | El edge publica `POST /api/v1/gate-events` con el cuerpo de `edge-ocr/schemas/gate-event.schema.json` (idempotencia por `eventId`, `isoValid`/`corrected` para la cola de revisión). El backend debe aceptar `isoValid=false` en la cola de revisión manual | **Resuelta (P3):** `isoValid=false` en una lectura de contenedor ⇒ `REQUIERE_REVISION` + tarea en la cola; el `eventId` y el texto crudo se conservan |
| backend-agent (P3) | architect | **Migraciones V1/V2 corregidas antes de su primera aplicación** (nunca se aplicaron en ningún entorno, por lo que siguen siendo inmutables desde ahora): (a) `uuid_generate_v7()` completado con `gen_random_bytes(10)`; (b) V2: alias `os.*` → `s.*` y `block_id/row/bay` en la CTE `slots_t1`; (c) `appointment.gate_id` con cast `::uuid`; (d) `vehicle_last_state` con una fila por vehículo (`ROW_NUMBER()`); (e) `generate_series(1, 12)` para incluir las bahías 20' 11 y 12 (630 slots, antes 450); (f) `ReviewTask.createdAt` `insertable=false, updatable=false`. Revisar y aceptar | Abierta |
| backend-agent (P3) | architect | Rutas implementadas que **no** están en `docs/api/openapi.yaml` y que el prompt P3 exige: CRUD de `carriers`, `drivers`, `trucks`, `appointments`, `gates`; `POST/PUT /containers`; `GET /visits/{id}/comprobante` (alias `receipt.pdf`) y `GET /visits/export.csv`. Están declaradas en `OpenApiContractTest`. Decidir si se incorporan al contrato (recomendado) o quedan como extensión documentada | Abierta |
| backend-agent (P3) | architect | Interpretación del estado `ASIGNADO_ANDEN/POSICION` del prompt como `ASIGNADO` para respetar el contrato de P1; queda anotado en §5. Confirmar que el contrato no cambiará el enum | Abierta |
| backend-agent (P3) | qa-docs-agent (P8) | La demo de puertas se ejecuta con `infra/scripts/demo_gate_flow.sh` (requiere backend con perfil `dev` y los datos del seed V2); la prueba `backend/src/test/java/cr/logisticaportuaria/e2e/DemoGateFlowScriptTest.java` muestra cómo levantarla sin Docker (PostgreSQL embebido). Los escenarios e2e de P8 pueden reutilizar ese flujo | Pendiente de P8 |
| backend-agent (P3) | vision-agent (edge-ocr) | `POST /api/v1/gate-events` exige `X-Edge-Api-Key: $EDGE_API_KEY` cuando el backend no corre con perfil `dev` (`app.edge.api-key` + `app.edge.open-ingestion=false`). Hoy `edge-ocr/app/infra/publisher.py` no envía esa cabecera: añadirla (config `backend_api_key`) o el edge recibirá 401 en producción. Con perfil `dev` no hace falta | Abierta |
| optimizer-agent (P4) | architect | Incorporar a `docs/api/openapi.yaml` las dos rutas que el prompt P4 no define: `GET /optimizer/plans/{id}` (consulta del plan asíncrono) y `GET /optimizer/metrics`. Hoy son extensiones declaradas en `OpenApiContractTest` | Abierta |
| optimizer-agent (P4) | architect | Evaluar una columna real de distancia a la puerta por slot: hoy `DistanceModel` deriva una fórmula geométrica sintética (ADR-010). Si se agrega, solo cambia esa clase | Abierta |
| optimizer-agent (P4) | qa-docs-agent (P8) | El benchmark del stacking se regenera en cada `./mvnw verify` (`StackingBenchmarkTest`) y escribe `docs/experiments/EXP-004-stacking-benchmark.{csv,md}`; los escenarios e2e de P8 pueden reutilizar `POST /optimizer/plans` con `apply=false` | Pendiente de P8 |
| vision-agent (P2) | qa-docs-agent (P8) | El dataset sintético se regenera con `python edge-ocr/tools/make_synthetic_dataset.py --output edge-ocr/data/synthetic --count 240 --seed 20260222`; los informes de evaluación están en `docs/experiments/EXP-002-ocr-*.{json,md}` y el modo `live` queda pendiente hasta que haya pesos (B-007) | Pendiente de P8 |
| optimizer-agent (P5) | architect | **ADR-011** (`congestion-gbm-hibrido.md`) y **ADR-012** (`cola-perfil-estacional.md`) creados en `docs/architecture/adr/` (fuera del ámbito del optimizer-agent). Revisar y aceptar, en especial la definición de cola y el proxy de servicio | Abierta |
| optimizer-agent (P5) | architect | Añadir a `docs/api/openapi.yaml` las rutas `GET /api/v1/congestion/forecast` y `GET /api/v1/congestion/heatmap` (hoy en `DEFERRED_PREFIXES` de `OpenApiContractTest`) con sus esquemas, incluido el bloque `quality` (`queueMae`, `smoothedMae`, `hybridMae`, `naiveMae`, `rmse`, `mape`, `coverage80`, `samples`, `active`) | Abierta |
| optimizer-agent (P5) | backend-agent (P6) | `CongestionAlertBroadcaster` publica en `/topic/yard/{yardId}/alerts/congestion` en cuanto exista un `SimpMessagingTemplate`; al registrar el broker STOMP de flota, mantener ese topic (no crear uno nuevo) para que la alerta de congestión llegue al dashboard sin cambios | **Resuelta (P6):** `FleetWebSocketConfig` registra el broker STOMP y `CongestionAlertBroadcaster` ya encuentra el `SimpMessagingTemplate`; el topic `/topic/yard/{yardId}/alerts/congestion` se conserva |
| backend-agent (P6) | architect | Rutas implementadas que **no** están en `docs/api/openapi.yaml`: `GET /fleet/kpis`, `GET /fleet/geofences`, `GET/POST /fleet/work-orders` (+ `/{id}/assign`, `assign-next`, `/{id}/start`, `/{id}/complete`, `/{id}/cancel`) y `GET /containers/{code}/trace`. Están declaradas en `OpenApiContractTest`. El contrato solo define `/fleet/vehicles` y `/fleet/vehicles/{id}/track`. Incorporarlas o documentarlas como extensión | Abierta |
| backend-agent (P6) | architect | Las alertas de flota **no se persisten** (ADR-014): no hay tabla `fleet_alert` en V1 y no se editó una migración aplicada. Si P8 o auditoría requiere histórico de alertas, crear `V3__fleet_alert.sql` (id, yard, vehicle, type, severity, at, detail) y avisar al backend-agent para escribirla | Abierta |
| backend-agent (P6) | architect | **ADR-013** (`marco-local-sin-postgis.md`) y **ADR-014** (`alertas-flota-stomp-at-least-once.md`) creados en `docs/architecture/adr/`. Revisar y aceptar; en particular, confirmar que el marco métrico local es suficiente y que no se requiere PostGIS | Abierta |
| backend-agent (P6) | qa-docs-agent (P8) | El simulador `simulators/fleet_sim.py` publica en `yard/{yardId}/fleet/{vehicleId}/telemetry` con la semilla del demo y los 8 UUID sembrados; `--dry-run --ticks N` genera JSON sin broker. Los escenarios e2e de P8 pueden reutilizarlo (paho opcional) | Pendiente de P8 |
| optimizer-agent (P5) | architect | `congestion_forecast` sigue con `NUMERIC(6,2)`: la entidad usa `BigDecimal` y no se tocó V1 (inmutable). Si en el futuro se necesita más rango para esperas > 9 999 min, haría falta una migración nueva, no una edición de V1 | Abierta |

---

## 12. Próximos pasos (priorizados)

1. ~~Ejecutar **P5**~~ **Hecho (2026-10-22, EXP-005).** ~~Siguiente: **P6** (telemetría de flota...)~~ **Hecho
   (2026-10-23, EXP-006):** 15 000 mensajes sin pérdidas y p95 999,7 ms; geocercas, alertas, órdenes y track.
   Siguiente: **P7** (frontend React: dashboard, mapa de flota, bitácora, ≥ 15 tests Vitest + 1 Playwright,
   bundle < 250 kB gzip y reporte axe). Requiere resolver **B-003** (Node/npm).
   Antes de P7/P8, ejecutar la suite de integración del backend en un entorno Linux + JDK 21 (resuelve
   **B-013**: embedded Postgres, Mockito y JaCoCo no funcionan en Windows + JDK 25).
2. Resolver **B-001** (Docker) en cuanto el usuario pueda: sin eso no hay verificación de `compose up` ni de
   la prueba MQTT contra el broker real (ni la alerta de congestión de P5 ni la telemetría de flota de P6).
3. Resolver **B-010** (`git init` + identidad + primer commit), **B-007** (pesos del OCR + `evaluate.py --mode
   live` para cerrar las métricas NO MEDIDO de §8), instalar Node/npm (**B-003**) y Python (**B-005**) antes de P7.
4. Continuar **P7** (frontend) → **P8** (simuladores, e2e e informe final), y cerrar con el architect las
   solicitudes abiertas de §11 (ADR-011..014, rutas de congestión y flota, tabla `fleet_alert`, migraciones
   V1/V2, distancia real).
5. Decidir con el cliente el marco de uso del detector (AGPL-3.0) antes del despliegue comercial.

---

## 13. Archivo (entradas resumidas de sesiones antiguas)

- **P2 (vision-agent):** corregidos 2 bugs del compose de P1 (`MQTT_HOST: mqtt` → el servicio es `mosquitto`;
  el edge no creaba `/app/data` ni montaba el volumen del outbox) y añadidos los targets `edge-dataset` y
  `edge-eval` al Makefile. Detalle en EXP-002. *(Movido desde §11 al condensar la memoria.)*
- **P1–P3 (bitácoras):** el detalle completo de las sesiones de arquitectura, edge y backend está en
  `docs/experiments/EXP-001-*`, `EXP-002-*` y `EXP-003-*`; aquí solo quedan los resúmenes de §6.
- **P5 (optimizer-agent):** iteraciones que no llegaron a la versión final y no están en §6: (a) primer diseño
  con un solo modelo para los tres horizontes, descartado por mezclar regímenes; (b) corrección ML sin gate,
  descartada porque podía empeorar el hold-out; (c) λ = media de la hora previa, sustituida por el perfil
  estacional; (d) cola = visitas abiertas (incluía en servicio), corregida a `assigned_at IS NULL`;
  (e) percentiles medidos por horizonte (p50 > p95), corregidos a la muestra agregada. Detalle en EXP-005.
