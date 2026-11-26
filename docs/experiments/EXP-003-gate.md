# EXP-003 — Backend Spring Boot: puertas, citas y bitácora digital

- **Fecha:** 2026-10-22
- **Agente:** backend-agent | **Modelo/herramienta:** OpenCode / big-pickle (agente autónomo con shell + edición de archivos)
- **Prompt usado:** P3 — "Backend Spring Boot: puertas, citas y bitácora digital" (versión del prompt: v1, el texto de `PROMPTS.md` sin modificar)
- **Objetivo:** Implementar en `backend/` el núcleo transaccional: ingesta de eventos OCR (HTTP y MQTT),
  conciliación automática con citas, bitácora digital con máquina de estados y tiempos, cola de revisión
  manual, CRUD paginado de datos maestros, comprobante PDF con QR, exportación CSV, seguridad JWT con cuatro
  roles, auditoría y métricas, respetando el esquema Flyway y el contrato OpenAPI de P1.

## Resultado

- **Entregables generados (rutas):**
  - Dominio y reglas: `backend/.../gate/domain/` (`GateReconciliationRules`, `ReconciliationPolicy`,
    `ReconciliationContext`, `ReconciliationDecision`, `VisitStateMachine`, `VisitState`, `ReadingKind`).
  - Casos de uso: `gate/application/` (`GateEventIngestionService`, `GateReconciliationService`, `VisitService`,
    `ReviewTaskService`, `VisitExportService`, `GateProperties`), `yard/application/` (`YardQueryService`,
    `MasterDataService`).
  - API: `gate/api/` (`GateEventController`, `VisitController`, `ReviewTaskController`),
    `yard/api/` (`ContainerController`, `MasterDataController`, `SlotController`), DTOs `record`.
  - Persistencia: `gate/infrastructure/persistence/`, `yard/infrastructure/persistence/`,
    `shared/persistence/AbstractEntity`; migraciones `V1__init.sql` (corregida) y `V2__seed_demo.sql` (corregida).
  - Seguridad: `shared/security/` (`SecurityConfig`, `DevTokenController`, `EdgeApiKeyFilter`, `AppRole`,
    `CurrentActor`); auditoría en `shared/audit/`; métricas en `shared/metrics/GateMetrics`.
  - MQTT: `gate/infrastructure/mqtt/GateEventMqttSubscriber` (`yard/+/gate/+/events`).
  - Demo: `infra/scripts/demo_gate_flow.sh` (ingreso y salida con `curl`, tiempos, PDF y CSV).
  - Pruebas: `backend/src/test/java/cr/logisticaportuaria/` (9 clases, ver métricas).
  - Documentación: `backend/README.md` (perfiles, Keycloak, MQTT, clave del borde, tabla de endpoints).
- **¿Compila / corre a la primera?** No. La primera corrida compiló (con `MapStruct` `unmappedTargetPolicy=ERROR`
  exigiendo marcar campos) pero dejó decenas de fallos de integración. Se corrigieron nueve problemas
  sustantivos:
  1. **`merge` silencioso de Spring Data:** las entidades reciben el UUID v7 antes del `save`, así que
     `isNew()==false` y Spring Data hacía `merge`; las mutaciones posteriores al `save()` se perdían sin
     error (por ejemplo, la visita no reflejaba la decisión de conciliación). Solución: `AbstractEntity`
     (`@MappedSuperclass`, `implements Persistable<UUID>`, `@Transient isNew`, `markPersisted()` en
     `@PostLoad`/`@PostPersist`); las 15 entidades pasaron a extenderla.
  2. **Parámetros nulos en JPQL sobre PostgreSQL:** el patrón `:p IS NULL OR col = :p` fallaba con
     `could not determine data type of parameter $n` (y `upper(bytea)`). Solución: `cast(:p as <tipo>) IS NULL`
     en todos los filtros opcionales (`uuid`, `string`, `instant`, `integer`, `boolean`), aplicado en
     `VisitLogRepository`, `ContainerRepository`, `GateEventRepository`, `ReviewTaskRepository`,
     `AppointmentRepository`, `CarrierRepository`, `TruckRepository` y `YardSlotRepository`.
  3. **Seed V2 (bahías 11 y 12 ausentes):** `generate_series(1, 10)` nunca generaba las bahías 20 pies; el
     conteo real era 450 slots en lugar de 630. Corregido a `generate_series(1, 12)`.
  4. **Seed V1 `uuid_generate_v7()`:** concatenaba solo una parte del timestamp; se completó con
     `gen_random_bytes(10)` para generar 128 bits válidos.
  5. **Seed V2, alias y tipos:** `os.*` referenciado cuando la CTE se llamaba `s`; `appointment.gate_id`
     insertado como `text` en una columna `uuid` (faltaba el `::uuid`); `vehicle_last_state` generaba una
     fila por evento en lugar de una por vehículo (`ROW_NUMBER()`).
  6. **`GlobalExceptionHandler` convertía los 401 en 500:** el `@ExceptionHandler(Exception.class)` capturaba
     los `ResponseStatusException`. Se agregó un handler específico que respeta el estado y el `ProblemDetail`.
  7. **Roles del JWT ignorados:** el `JwtAuthenticationConverter` por defecto solo lee `scope`, así que el
     `claim` `roles` nunca llegaba a `@PreAuthorize` y todo respondía 403. Se configuró un conversor que lee
     `roles` y `realm_access.roles` (Keycloak) con prefijo `ROLE_` automático.
  8. **Script de demo:** `curl -w '%{http_code} %{time_total}'` no termina en salto de línea y `read` devolvía
     código 1 por EOF, abortando el script con `set -e`. Se agregó `\n` al formato.
  9. **JaCoCo:** `target/jacoco.exec` corrupto (`Unknown block type 50`) hacía fallar el reporte; se borra el
     archivo y se repite la corrida (documentado en `MEMORY.md` §9).
- **Nº de iteraciones/correcciones necesarias:** 11 corridas de `./mvnw test|verify` (5 en rojo, 6 en verde),
  ~35 correcciones puntuales de mapeo/consultas y las 9 anteriores.
- **Tiempo aproximado agente vs. estimación manual:** sesión del agente ~6 h (estimación a partir del volumen
  de código y del número de iteraciones; **no medida con reloj**). Estimación manual de un equipo: 60-80 h
  (112 archivos Java: 101 de producción y 11 de prueba, esquema y seed, seguridad, PDF/CSV, demo y pruebas).

## Métricas (reales o "NO MEDIDO")

Entorno: WSL2 Ubuntu 26.04, Temurin 21.0.x en `~/.local/jdk21`, Maven 3.9.9 (`wrapper only-script`),
PostgreSQL 16.9.0 embebido (Zonky) — Docker no utilizable en esta máquina (`MEMORY.md` §9 B-001).

| Métrica | Valor | Cómo se midió |
|---|---|---|
| Pruebas del backend | **64 pruebas, 0 fallos** en 9 clases | `./mvnw test` (salida de Surefire) |
| Pruebas de reglas de conciliación | **16 escenarios** (≥ 12 exigidos) | `GateReconciliationRulesTest` |
| Pruebas de máquina de estados | 7 (incluye transición inválida → 409) | `VisitStateMachineTest` |
| Pruebas de flujo completo | 10 (`evento OCR → visita → salida`, idempotencia, 409, cola, CRUD, PDF/CSV) | `GateFlowIntegrationTest` |
| Cobertura JaCoCo de líneas en paquetes `gate` | **83,14 %** (853/1026) | `target/site/jacoco/jacoco.csv` tras `./mvnw verify` |
| Cobertura JaCoCo de ramas en paquetes `gate` | 61,37 % (224/365) | ídem |
| Cobertura global del bundle | 69,73 % de líneas (1576/2260) | ídem |
| `./mvnw verify` | **BUILD SUCCESS**, "All coverage checks have been met" (umbral 70 %) | `./mvnw verify` |
| Contrato OpenAPI | 5 pruebas verdes contra `docs/api/openapi.yaml` | `OpenApiContractTest` (rutas, códigos, `ProblemDetail`, filtros vacíos) |
| Latencia de ingesta HTTP (lectura de matrícula) | 112 ms (primera lectura, incluye conciliación y escritura) | `curl -w %{time_total}` dentro del script de demo |
| Latencia de transición de estado | 19-31 ms | ídem |
| Tiempos calculados por la bitácora (demo) | ingreso: espera 0 s / patio 3 s / proceso 2 s · salida: 0 s / 4 s / 2 s | script de demo contra servidor real con PostgreSQL embebido |
| Comprobante PDF | 1941 bytes, `Content-Type: application/pdf` | script de demo (descarga real) |
| Exportación CSV | 2 filas + encabezado, UTF-8 con BOM | script de demo |
| `docker compose up backend postgres mosquitto` | **NO MEDIDO** | Docker no utilizable en WSL2 (B-001): revisión estática del compose de P1 y PostgreSQL embebido como sustituto |
| Swagger UI en navegador | **NO MEDIDO** | Sin navegador en el entorno; `/v3/api-docs` sí se valida en `OpenApiContractTest` |
| Keycloak real | **NO MEDIDO** | No se despliega (el prompt no lo exige); se documenta la configuración en `backend/README.md` |

## Calidad del código generado (1-5)

Corrección: 4 | Legibilidad: 4 | Seguridad: 4 | Cobertura de requisitos: 5

## Problemas y alucinaciones detectadas

- El estado `ASIGNADO_ANDEN/POSICION` del prompt no existe en el contrato de P1; el contrato fija `ASIGNADO`
  y se respetó el contrato (documentado en `MEMORY.md` §5).
- El prompt pide `GET /api/v1/visits/{id}/comprobante` y el contrato de P1 no define esa ruta: se implementó
  `/comprobante` con alias `/receipt.pdf` (extensión documentada en el test de contrato y en `MEMORY.md` §11).
- La primera versión del seed "parecía" correcta (los `INSERT` no fallaban) pero dejaba 450 slots y
  `vehicle_last_state` con filas duplicadas; solo lo detectó la prueba de integración que cuenta 630 slots.
- El `merge` silencioso de Spring Data es un modo de fallo peligroso: no lanza excepción y la API responde
  200 con datos incompletos. Queda como convención obligatoria del repo (`MEMORY.md` §4).
- El entorno obligó a sustituir Testcontainers por PostgreSQL embebido (Zonky); la prueba de integración es
  equivalente (PostgreSQL real), pero no valida `docker compose`.
- Revisión estática del compose (no ejecutable): el servicio `backend` define `MQTT_HOST`/`MQTT_PORT`, que el
  backend no leía; se añadió el fallback `${MQTT_URL:mqtt://${MQTT_HOST:localhost}:${MQTT_PORT:1883}}` en
  `application.yml` (dentro del ámbito del backend, sin tocar el compose). Queda pendiente que el edge envíe
  `X-Edge-Api-Key` fuera del perfil `dev` (solicitud en `MEMORY.md` §11).

## Ajustes sugeridos al prompt

- Fijar explícitamente que el contrato de P1 manda sobre los nombres de estado y de rutas (`ASIGNADO`,
  `comprobante` vs. `receipt.pdf`) para evitar ambigüedad.
- Indicar que, si Docker no está disponible, se acepta PostgreSQL embebido como sustituto de Testcontainers
  y que el criterio de `docker compose up` pasa a revisión estática documentada.
- Añadir como criterio de aceptación que el script de demo se ejecute en la suite (prueba de humo) para que
  no se degrade: así se detectaron dos defectos reales del script.

## Conclusión

P3 queda implementado y verificado: 64 pruebas verdes, `./mvnw verify` en verde con 83,14 % de líneas en
`gate` (umbral 70 %), demo real de ingreso y salida con tiempos calculados, comprobante PDF y CSV. Los
bloqueos del entorno (Docker y Keycloak) quedan como **NO MEDIDO** con sustituto y motivo documentados. Los
tres defectos más costosos (merge silencioso, parámetros nulos en JPQL y roles del JWT) son decisiones de
infraestructura que ya quedaron como convenciones en `MEMORY.md` para P4-P8.
