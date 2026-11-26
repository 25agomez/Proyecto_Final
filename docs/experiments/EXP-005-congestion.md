# EXP-005 — Predicción de congestión en puertas y andenes

- **Fecha:** 2026-10-22
- **Agente:** optimizer-agent | **Modelo/herramienta:** big-pickle (OpenCode)
- **Prompt usado:** P5 (versión v1 de `PROMPTS.md`)
- **Objetivo:** pronosticar la espera y la cola de una puerta a 15, 30 y 60 minutos, explicable para el
  supervisor, con tres enfoques comparados (colas M/G/c, suavizado estadístico y gradient boosting) y
  un híbrido elegido por validación temporal; API de pronóstico y de mapa de calor, alertas MQTT/STOMP
  y job de persistencia.

## Resultado

- **Entregables generados (rutas):**
  - `backend/src/main/java/cr/logisticaportuaria/optimizer/congestion/`
    - `domain/`: `QueueingModel` (M/M/c + Allen-Cunneen, cuantiles y `waitAtHorizon`),
      `ExponentialSmoothing` (Holt-Winters aditivo + `walkForward` sin fuga), `GradientBoostingRegressor`
      (propio, determinista), `CongestionForecaster` (por horizonte), `CongestionFeatures`,
      `HorizonModel`, `TrainedCongestionModel`, `BacktestRow`, `ModelMetrics`, `RiskLevel`,
      `ConfidenceLevel`, `CostaRicaCalendar`, `HeatmapBuilder`, `RecommendationRules`,
      `HistoricalVisit`, `GateHistory`, `CongestionPrediction` y la entidad `CongestionForecast`.
    - `application/`: `CongestionService` (caché por puerta), `CongestionScheduler` (job de 5 min),
      `CongestionProperties`, DTOs.
    - `api/`: `CongestionController` (`/forecast`, `/heatmap`).
    - `infrastructure/`: repositorios (`JdbcTemplate` + JPA), `CongestionAlertPublisher` (MQTT) y
      `CongestionAlertBroadcaster` (STOMP).
  - `backend/src/test/java/cr/logisticaportuaria/optimizer/congestion/`: 44 pruebas (dominio, simulador
    DES, regresión con dataset fijo, alertas e integración con PostgreSQL real).
  - Este informe + `docs/experiments/EXP-005-congestion/metrics.md` y `backtest-h{15,30,60}.csv`,
    generados por `CongestionRegressionTest` en cada corrida.
  - `docs/architecture/adr/ADR-011-*.md` y `ADR-012-*.md`; sección nueva en `backend/README.md`.
- **¿Compila / corre a la primera?** **No.** Fallos reales encontrados y corregidos:
  1. `@Column(precision/scale)` sobre `double` → Hibernate aborta con *"scale has no meaning for SQL
     floating point types"* al crear el `entityManagerFactory` (rompía los 7 tests de integración).
  2. Al quitar `precision/scale`, la validación de esquema falla: la migración V1 declara
     `NUMERIC(6,2)` y Hibernate espera `float(53)`. Se resolvió mapeando la entidad con `BigDecimal`
     (sin tocar la migración, que es inmutable).
  3. La tasa de llegadas del camino de producción multiplicaba por 4 una media que ya era horaria:
     pronosticaba ~90 llegadas/h con 18/h de base (detectado al revisar el ejemplo de punta del
     informe; ahora 28,3/h en la punta de la tarde).
  4. El modelo de colas devolvía el tope de 240 min en cuanto ρ ≥ 1; se cambió a la aproximación
     fluida (solo el backlog que no se puede drenar en el horizonte), mucho más realista.
  5. La cola se contaba con los camiones ya en servicio; ahora es la cola real (esperando carril),
     también en el SQL (`assigned_at IS NULL`).
  6. La tasa λ de la línea base usaba la hora anterior observada (muy ruidosa); se sustituyó por el
     perfil estacional (día de la semana, hora) calculado *walk-forward* solo con semanas previas.
  7. El percentil p50 se medía con el horizonte 15 y el p95 con el 30 (podía dar p50 > p95); ahora se
     agregan las 300 mediciones de los tres horizontes.
- **Nº de iteraciones/correcciones necesarias:** 9 sobre el modelo (3 de esquema/persistencia, 4 del
  modelo y 2 de la medición) más 3 de compilación.
- **Tiempo aproximado agente vs. estimación manual:** **NO MEDIDO** el tiempo de pared del agente. La
  estimación manual (un científico de datos + un backend senior) es de **6–8 días-persona** para el
  módulo, el simulador, la validación y el informe.

## Métricas (reales o "NO MEDIDO")

Escenario de referencia (simulador DES propio, determinista por semilla): puerta G1 con **3 carriles**,
servicio lognormal de **7 min** (σ = 0,3), **18 llegadas/h** de base (punta mañana ×2,0, punta tarde
×1,7, noche ×0,1, fin de semana ×0,6, feriado ×0,3), **60 % de las llegadas con cita**, **21 días**
(2026-09-01 a 2026-09-22), **7 064 visitas**, semilla **2026**. Partición temporal 80/20 (las últimas
~4,2 jornadas son el hold-out; 306–307 franjas de 15 min).

| Métrica | Valor | Cómo se midió |
|---|---|---|
| MAE 15 min — colas M/G/c (perfil estacional) | **4,91 min** | `CongestionRegressionTest`, hold-out temporal |
| MAE 15 min — suavizado HW de llegadas | 7,88 min | ídem (misma traducción a espera con el modelo de colas) |
| MAE 15 min — línea base ingenua (persistencia) | 8,50 min | ídem (espera media de la hora anterior) |
| MAE 15 min — híbrido (colas + boosting) | **3,25 min** | ídem |
| MAE 30 min — colas / suavizado / ingenua / híbrido | 5,48 / 10,23 / 9,62 / **3,92** min | ídem |
| MAE 60 min — colas / suavizado / ingenua / híbrido | 7,03 / 12,36 / 11,56 / **5,24** min | ídem |
| RMSE 15/30/60 min — híbrido | 5,76 / 7,02 / 9,51 min | ídem |
| RMSE 15/30/60 min — colas | 8,89 / 9,73 / 12,33 min | ídem |
| RMSE 15/30/60 min — ingenua | 14,66 / 16,44 / 19,40 min | ídem |
| Mejora del MAE vs. línea base ingenua | **61,8 % / 59,2 % / 54,7 %** (15/30/60) | calculada de la tabla anterior |
| Mejora del MAE vs. modelo de colas | 33,8 % / 28,5 % / 25,5 % | ídem |
| Cobertura del intervalo p10–p90 (meta 80 %) | **85,0 % / 86,9 % / 87,9 %** | ídem |
| MAPE del híbrido | 128 % / 143 % / 169 % | ídem; **no comparable**: se calcula solo con esperas > 0 y hay franjas de segundos |
| `drivers` por pronóstico | 4–5 (mínimo exigido: 2) | `CongestionRegressionTest` e integración |
| Entrenamiento de los 3 horizontes | 488–576 ms (1 531 franjas por horizonte, 120 árboles) | `System.nanoTime()` en el test |
| Latencia del pronóstico (n = 300, 3 horizontes) | p50 **0,30–0,38 ms** · p95 **0,93–1,36 ms** · p99 **2,6–3,6 ms** | ídem |
| Degradación sin histórico | `confidence=BAJA`, `model=COLAS`, sin corrección ML | `CongestionRegressionTest` + `CongestionIntegrationTest` |
| Persistencia del job | 2 puertas × 3 horizontes = **6 filas** con el mismo `computed_at` en `congestion_forecast` | `CongestionIntegrationTest` |
| Cobertura de líneas `optimizer.congestion` | **91,0 %** del paquete (975/1 072): dominio 94,2 % · aplicación 86,1 % · persistencia 96,2 % · `infrastructure/mqtt` 65,0 % | JaCoCo (`target/site/jacoco/jacoco.xml`) |
| Sueño de la puerta G2 en la demo (sin histórico) | 0 franjas → respuesta en ~35 ms (entrenamiento vacío) | `CongestionIntegrationTest` |
| `docker compose up` del módulo | **NO MEDIDO** — Docker no disponible (B-001) | — |
| Alerta MQTT contra broker real | **NO MEDIDO** — sin broker (B-001); el publicador se prueba con el broker caído y con las alertas apagadas | `CongestionAlertPublisherTest` |

**Hallazgo honesto:** el suavizado Holt-Winters *empeora* al modelo de colas con perfil estacional
(MAE 7,9–12,4 frente a 4,9–7,0). Con parámetros fijos (α = 0,3) y franjas de 15 min, la serie de
llegadas es demasiado ruidosa: la tasa pronosticada oscila y empuja al modelo de colas a la saturación
(RMSE ~25 min). El perfil estacional por (día, hora) es un estimador más robusto para este escenario.
El híbrido gana a los tres y por eso es el modelo activo en los tres horizontes.

## Calidad del código generado (1-5)

Corrección: 4 | Legibilidad: 4 | Seguridad: 4 | Cobertura de requisitos: 5

- **Corrección 4:** 44 pruebas verdes y `./mvnw verify` en verde, pero hicieron falta 9 correcciones
  sobre el modelo y el esquema antes de estabilizarlo.
- **Legibilidad 4:** el dominio está separado por horizonte y cada clase tiene su contrato; el
  `CongestionForecaster` concentra mucha lógica (≈450 líneas) y podría dividirse.
- **Seguridad 4:** roles de lectura, sin datos personales en el modelo ni en los logs; el publicador
  MQTT falla en silencio y no bloquea la API.
- **Cobertura de requisitos 5:** los tres enfoques, el híbrido validado, la API, las alertas, el job,
  las pruebas de fórmulas a mano, la regresión con dataset fijo y la degradación sin histórico.

## Problemas y alucinaciones detectadas

- **Entidad JPA vs. migración:** `precision/scale` en `double` es inválido en Hibernate 6 y, sin ellos,
  la validación de esquema choca con `NUMERIC(6,2)`. La solución correcta era `BigDecimal`; editar la
  migración V1 estaba prohibido (inmutable).
- **Un factor 4 escondido:** la media de llegadas por hora ya estaba en horas y se multiplicaba por
  cuatro "porque las franjas son de 15 min". El error no lo detectaron las pruebas (todas comparaban
  contra el modelo, no contra la física) sino el ejemplo ilustrativo del informe, que mostraba 354 % de
  utilización. **Lección:** incluir en el informe un caso de punta con números verificables a mano.
- **Singularidad de la cola:** el M/G/c estacionario explota cerca de ρ = 1 y devolvía 240 min aunque
  la espera real fuese de 10-20 min. La corrección (backlog fluido) está documentada en ADR-012.
- **Sesgo por la cola mal definida:** contar los camiones en servicio como "cola" inflaba la espera
  pronosticada; el SQL de `OPEN_VISITS_SQL` y el simulador se alinearon con la definición de cola.
- **Métrica mal calculada:** p50/p95/p99 se tomaban de horizontes distintos; con p50 > p95 se detectó.
- **Ninguna cifra inventada:** todo lo que no se pudo medir (broker, Docker) está como **NO MEDIDO**
  con su motivo.

## Ajustes sugeridos al prompt

- Aclarar **en qué espacio se comparan los enfoques**: Holt-Winters pronostica *llegadas*, no esperas;
  hubo que definir que la comparación se hace traduciendo las llegadas a espera con el mismo modelo de
  colas (y documentarlo).
- Pedir explícitamente la **degradación sin histórico por la API** (no solo en el dominio): así se
  escribe la prueba de integración desde el principio.
- Fijar un **escenario de referencia con congestión real**: con la carga original (12 llegadas/h y 3
  carriles) la puerta está ociosa y la mediana de la espera es 0, lo que hace que la MAE premie
  predecir cero; se subió a 18 llegadas/h (ρ ≈ 1,1 en la punta) y el problema pasó a ser el del prompt.
- Añadir la restricción de que la migración `congestion_forecast` ya existe en V1 y que la entidad no
  puede declarar `precision/scale` sobre `double`.

## Conclusión

El módulo cumple los criterios de P5: tres enfoques implementados y comparados en validación temporal
sin fuga, híbrido elegido por mejora medida (**MAE 3,25 / 3,92 / 5,24 min** frente a **8,50 / 9,62 /
11,56** de la línea base ingenua y **4,91 / 5,48 / 7,03** del modelo de colas), cobertura del 85–88 %
en la banda del 80 %, latencia de pronóstico < 1,5 ms p95, job que persiste 6 filas por corrida y
degradación explícita con confianza BAJA cuando no hay histórico. Lo que falta medir depende del
entorno: el broker MQTT real y el `docker compose up` (B-001).
