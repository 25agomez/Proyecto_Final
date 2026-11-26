# EXP-006 — Trazabilidad de flota en tiempo real

- **Fecha:** 2026-10-23
- **Agente:** backend-agent | **Modelo/herramienta:** big-pickle (OpenCode)
- **Prompt usado:** P6 (versión v1 de `PROMPTS.md`)
- **Objetivo:** ingerir telemetría MQTT de 5–50 vehículos con validación y tolerancia a duplicados,
  desorden y saltos imposibles; persistir el histórico y el último estado; calcular geocercas y alertas;
  difundir posiciones y alertas por STOMP con p95 ≤ 2 s; gestionar órdenes de trabajo; exponer REST de
  flota, KPIs y trazabilidad de contenedor, y medir enviado vs. persistido sin pérdidas.

## Resultado

- **Entregables generados (rutas):**
  - `backend/src/main/java/cr/logisticaportuaria/fleet/`
    - `domain/`: `YardFrame`, `YardLayout`, `GeoPolygon`, `Geofence`, `GeofenceKind`, `FleetStatus`,
      `FleetTelemetry`, `AlertType`, `FleetAlert`, `VehicleState`, `VehicleInfo`, `AlertRules`,
      `TrackGeometry`.
    - `application/`: `FleetTelemetryService`, `FleetQueryService`, `WorkOrderService`, `FleetProperties`,
      `FleetMetrics`, `IngestResult`, `FleetStateCache`, `AlertStateStore`, `GeofenceCache`,
      `VehicleDirectory`, `FleetAlertBuffer`, DTOs.
    - `api/`: `FleetController` (vehículos, track, KPIs, geocercas, órdenes de trabajo) y
      `ContainerTraceController`.
    - `infrastructure/`: `FleetTelemetryMqttSubscriber`, `FleetBroadcaster` + `FleetBroadcastScheduler`,
      `FleetWebSocketConfig`, `FleetStompAuthInterceptor`, `FleetSilenceMonitor`, `FleetRetentionJob` y
      repositorios JDBC (`VehiclePositionRepository`, `WorkOrderRepository`, `ContainerTraceRepository`,
      `FleetVehicleRepository`, `GeofenceRepository`).
  - `simulators/fleet_sim.py` (5–50 vehículos, semilla fija, seis fallos inyectados) y
    `simulators/tests/test_fleet_sim.py` (8 casos, sin red).
  - `backend/src/test/java/cr/logisticaportuaria/fleet/`: 48 pruebas unitarias, `FleetIntegrationTest` (9)
    y `FleetLoadTest` (1).
  - `docs/architecture/adr/ADR-013-marco-local-sin-postgis.md` y
    `docs/architecture/adr/ADR-014-alertas-flota-stomp-at-least-once.md`.
  - Secciones nuevas en `backend/README.md` y `simulators/README.md`.
- **¿Compila / corre a la primera?** **No.** Fallos reales:
  1. `ensure_vehicle_position_partition(?)` lanzaba `BadSqlGrammarException` cuando varios hilos de
     ingesta creaban la misma partición diaria a la vez: la función SQL comprueba `pg_class` y luego
     crea, sin atomicidad. Se sincronizó `ensurePartitionFor` y se tolera el error `already exists`.
  2. El primer diseño de la prueba de carga publicaba con un salto de 18 m en 1 s (envolvente de la
     trayectoria) y el backend lo descartaba como salto imposible; se corrigió la ruta sintética.
  3. La prueba de silencio del integrador estaba escrita con un *placeholder*; se reemplazó por una
     aserción real con `FleetTelemetryService.checkSilence` (una sola alerta por episodio).
  4. El primer `verify` sobre un `target/` heredado generó un reporte JaCoCo con 0 % de cobertura
     («Execution data does not match»); se repitió con `target/` borrado.
- **Nº de iteraciones/correcciones necesarias:** 6 (2 de la carrera de particiones, 1 de la ruta de la
  prueba de carga, 1 de la prueba de silencio, 1 del método de métricas en la prueba unitaria y 1 del
  reporte de cobertura).
- **Tiempo aproximado agente vs. estimación manual:** **NO MEDIDO** el tiempo de pared del agente. La
  estimación manual (1 backend senior + 1 DevOps) es de **7–9 días-persona** para ingesta, persistencia
  particionada, alertas, STOMP, órdenes, simulador y pruebas de carga.

## Métricas (reales o "NO MEDIDO")

| Métrica | Valor | Cómo se midió |
|---|---|---|
| Pruebas unitarias de flota | **48** en verde (0 fallos) | `FleetMetricsTest` 3, `FleetQueryServiceTest` 6, `WorkOrderServiceTest` 8, `AlertRulesTest` 14, `FleetGeometryTest` 6, `FleetTelemetryTest` 6, `FleetBroadcasterTest` 5 |
| Pruebas de integración de flota | **9/9** en verde | `FleetIntegrationTest` con PostgreSQL 16 embebido (ADR-007) |
| Casos de geocercas y alertas automatizados | **≥ 6** (entrada/salida, velocidad, ralentí, zona prohibida, batería, sin señal) | `AlertRulesTest` (14 casos) + `FleetIntegrationTest` |
| Pruebas del simulador | **8/8** en verde | `pytest simulators/tests -q` |
| Carga 50 vehículos × 1 msg/s × 5 s | **250 enviados = 250 recibidos = 250 persistidos = 250 filas nuevas**, 0 rechazados; p50 **987,0 ms**, p95 **999,5 ms** | `FleetLoadTest` (`-Dfleet.load.seconds=5`) |
| Carga 50 vehículos × 1 msg/s × 5 min | **15 000 enviados = 15 000 recibidos = 15 000 persistidos = 15 000 filas nuevas**, 0 rechazados; p50 **996,6 ms**, p95 **999,7 ms** (340,8 s de prueba) | `FleetLoadTest` (`-Dfleet.load.seconds=300`) |
| Latencia telemetría → WebSocket (p95) | **999,7 ms** (≤ 2 s) | `FleetMetrics` (recepción → `convertAndSend`), expuesta en `GET /fleet/kpis` |
| Mensajes perdidos en carga | **0** (recibidos = persistidos = filas nuevas en ambas corridas) | contadores de `FleetMetrics` + `count(*)` de `vehicle_position` |
| Cobertura de líneas del backend | **80,23 %** (4 370/5 447); módulo `fleet` **80,88 %** (969/1 198: dominio 93,27 %, aplicación 87,89 %, persistencia 81,31 %, api 85,00 %, websocket 75,00 %, `infrastructure/mqtt` 20,00 %, monitoring 0,00 %); `gate` **83,14 %** | `./mvnw verify` con `target/` limpio y JaCoCo 0.8.12 |
| Latencia de red del bróker MQTT | **NO MEDIDO** | Docker no disponible (B-001): el suscriptor se invoca con `handleTopic` y la métrica excluye el salto de red del bróker |
| `docker compose up` (Mosquitto real + STOMP + frontend) | **NO MEDIDO** | B-001 |

### Lectura de las métricas de latencia

El *throttle* de difusión es de 1 s (`app.fleet.broadcast-interval-ms`), así que la latencia
recepción→STOMP se reparte uniformemente entre 0 y 1 s: p50 ≈ 0,5 s y p95 ≈ 1,0 s **por diseño**.
El valor medido (p95 ≈ 999 ms) confirma que el pipeline no añade latencia apreciable sobre el temporizador
y cumple el umbral de 2 s con un factor de 2. La latencia que ve el navegador añade el salto de red del
WebSocket y el *render*, que no se miden aquí.

### Cómo reproducir

```bash
# Backend (JAVA_HOME ya exportado)
cd backend
./mvnw test -B -Dtest='FleetLoadTest' -DfailIfNoTests=false -Dfleet.load.seconds=300
./mvnw test -B -Dtest='FleetIntegrationTest'
./mvnw verify -B                      # suite completa + JaCoCo

# Simulador (sin broker)
/tmp/opencode/venv/bin/python -m pytest simulators/tests -q
/tmp/opencode/venv/bin/python simulators/fleet_sim.py --vehicles 20 --seed 42 --dry-run --ticks 5
```

## Calidad del código generado (1-5)

Corrección: 4 | Legibilidad: 5 | Seguridad: 4 | Cobertura de requisitos: 5

## Problemas y alucinaciones detectadas

- La primera versión de `ensurePartitionFor` asumía que `CREATE TABLE IF NOT EXISTS` dentro de una
  función SQL era atómico frente a concurrencia; no lo es. Detectado por la prueba de carga (4 hilos),
  no por las pruebas unitarias.
- La prueba de carga inicial generaba saltos de 18 m entre ticks y confundía un fallo del generador con
  una pérdida del backend; el criterio de saltos imposibles funcionó como se esperaba (descartó el
  mensaje), lo que obligó a corregir la ruta sintética.
- El reloj de `vehicle_position` se guarda en UTC; el track GeoJSON responde `[lon, lat]` (RFC 7946), no
  `[lat, lon]`, y se verificó en la prueba de integración.
- El primer `./mvnw verify` sobre un `target/` heredado reportó **0 % de cobertura** con 238 avisos
  «Execution data for class … does not match»: el `jacoco.exec` correspondía a una compilación anterior y el
  `jacoco:check` **pasa en silencio** si ninguna clase coincide. Se resolvió borrando `target/` y repitiendo
  `./mvnw verify`; queda anotado como riesgo de medición (el build no falla ante datos sin coincidencia).

## Ajustes sugeridos al prompt

- Pedir explícitamente la **latencia de recepción a `convertAndSend`** (no extremo a extremo) cuando el
  broker no esté disponible, para que la métrica sea honesta y comparable.
- Aclarar si la prueba de carga debe usar el broker real; sin Docker, el camino directo `handleTopic` es
  la única opción y se debe declarar.

## Conclusión

El pipeline de flota cumple el criterio medible en este entorno: **p95 ≈ 1 s ≤ 2 s** y **cero pérdidas**
en las corridas de 5 s y de 5 min (50 vehículos × 1 msg/s), con geocercas y alertas cubiertas por 14
reglas unitarias y la integración con PostgreSQL real; `./mvnw verify` queda en **BUILD SUCCESS con 190
pruebas** y **80,88 % de líneas en `fleet`** (total 80,23 %, `gate` 83,14 %). Quedan como **NO MEDIDO** la
latencia del bróker MQTT y el `compose up` completo. La decisión de no crear tabla de alertas y de usar un
marco métrico local está registrada en ADR-013 y ADR-014.
