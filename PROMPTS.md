# PROMPTS.md — Prompts para generar componentes de "LogisticaPortuaria CR"

## Cómo usar este documento

1. Coloca `AGENTS.md` y `MEMORY.md` en la raíz del repositorio (el agente debe leerlos primero).
2. Ejecuta los prompts **en orden** (P1 → P8); cada uno depende de los anteriores.
3. Después de cada prompt, el agente debe crear `docs/experiments/EXP-NNN-*.md` y actualizar `MEMORY.md`.
4. Para comparar modelos/herramientas, repite el mismo prompt cambiando solo el agente y registra ambos resultados.
5. Cada prompt incluye el bloque **"Cierre obligatorio"**; no lo quites, es lo que alimenta la memoria y la documentación.

**Tabla de referencia**

| ID | Componente | Agente | Depende de |
|---|---|---|---|
| P1 | Arquitectura, modelo de datos PostgreSQL y docker-compose | architect | — |
| P2 | Servicio Edge OCR (matrículas + códigos BIC) | vision-agent | P1 |
| P3 | Backend: puertas, citas y bitácora digital | backend-agent | P1, P2 |
| P4 | Motor de stacking inteligente con OR-Tools | optimizer-agent | P1, P3 |
| P5 | Predicción de congestión en puertas y andenes | optimizer-agent | P3 |
| P6 | Trazabilidad en tiempo real de la flota interna | backend-agent | P1, P3 |
| P7 | Frontend React + Tailwind (dashboard, mapa, bitácora) | frontend-agent | P3–P6 |
| P8 | Simuladores, pruebas e2e e informe de resultados | qa-docs-agent | P1–P7 |

---

## Bloque común: "Cierre obligatorio" (se pega al final de cada prompt)

```
CIERRE OBLIGATORIO
1. Ejecuta compilación, lint y pruebas; pega la salida resumida (no inventes resultados).
2. Crea docs/experiments/EXP-NNN-<slug>.md con la plantilla de la sección 7 de AGENTS.md.
3. Actualiza MEMORY.md: estado de componentes, bitácora de sesiones, ADRs nuevos,
   índice de experimentos, métricas medidas, problemas abiertos y próximos pasos.
4. Termina con un resumen de máximo 15 líneas: qué se entregó, qué falló, qué supuestos hiciste.
Si algo no pudo medirse, escribe "NO MEDIDO" y explica por qué.
```

---

## P1 — Arquitectura, modelo de datos y orquestación Docker

**Agente:** `architect`

```
ROL
Eres el arquitecto de software senior del proyecto LogisticaPortuaria CR. Lee AGENTS.md y MEMORY.md antes de empezar.

CONTEXTO
Operador logístico de patios de contenedores y transporte terrestre en Costa Rica (Caribe y Pacífico). Necesitamos
un sistema local (on-premise, Linux + Docker), 100 % open source, con: OCR de matrículas y códigos BIC en el borde,
stacking inteligente, predicción de congestión, trazabilidad de flota en tiempo real y bitácoras digitales.

OBJETIVO
Diseñar y generar la base técnica del monorepo: estructura de carpetas, modelo de datos, contratos entre servicios
e infraestructura Docker lista para levantar.

TAREAS
1. Crea la estructura de repositorio definida en AGENTS.md §3 con README.md por módulo (placeholder útil, no vacío).
2. Diagrama de arquitectura en Mermaid (C4 nivel 2) en docs/architecture/c4-containers.md, mostrando:
   cámaras RTSP → edge-ocr → (HTTP/MQTT) → backend Spring Boot → PostgreSQL; Mosquitto para telemetría;
   frontend React; motor OR-Tools dentro del backend.
3. Modelo de datos PostgreSQL 16 con migraciones Flyway (V1__init.sql, V2__seed_demo.sql). Incluye como mínimo:
   - yard, yard_block, yard_slot (bloque, bahía, fila, nivel, tipo: dry/reefer/hazmat, capacidad TEU)
   - container (código BIC con CHECK de formato ^[A-Z]{3}[UJZ][0-9]{7}$, tamaño 20/40, tipo, estado, peso, cliente, ETA/ETD)
   - container_position (histórico de ubicaciones con vigencia; una sola posición activa por contenedor)
   - truck, driver, carrier, appointment (cita de ingreso/retiro)
   - gate, gate_event (lectura OCR cruda, texto normalizado, confianza, URL de evidencia, validación ISO 6346)
   - visit_log (bitácora digital de entrada/salida con tiempos de cada etapa)
   - vehicle (montacargas/rastra), vehicle_position (serie temporal), work_order (movimientos de patio)
   - audit_log (quién/qué/cuándo)
   Reglas: claves UUID, timestamps con zona (timestamptz, UTC), índices para consultas por código/matrícula/tiempo,
   restricción de unicidad para evitar dos contenedores en el mismo slot activo.
4. Contratos:
   - OpenAPI 3.1 inicial (docs/api/openapi.yaml) para: /gate-events, /visits, /containers, /yard/slots,
     /optimizer/recommendations, /congestion/forecast, /fleet/vehicles.
   - Contrato del servicio edge-ocr (JSON Schema del evento de lectura) y topics MQTT (AGENTS/MEMORY convenciones).
5. infra/docker-compose.yml con servicios: postgres, mosquitto, edge-ocr, backend, frontend (nginx), y opcional
   prometheus+grafana bajo un profile "observability". Incluye healthchecks, volúmenes, red interna,
   .env.example y un Makefile con targets up/down/logs/test/seed.
6. ADRs (docs/architecture/adr/) para al menos: separación del servicio OCR en Python, uso de MQTT,
   estrategia de optimización (CP-SAT + heurística de respaldo) y política de retención de imágenes/matrículas.

RESTRICCIONES
- Solo software libre. Sin servicios cloud.
- El esquema debe soportar 3 patios y ~15 000 TEU por patio sin rediseño.
- Todo script debe ser idempotente.

CRITERIOS DE ACEPTACIÓN
- `docker compose up -d postgres` aplica las migraciones sin error y `V2__seed_demo.sql` carga 1 patio demo
  con ≥ 6 bloques, ≥ 500 slots y ≥ 80 contenedores.
- El diagrama Mermaid renderiza.
- openapi.yaml valida con un linter OpenAPI (indica cuál usaste).

ENTREGABLES
Rutas completas de todos los archivos creados + resumen de decisiones.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P2 — Servicio Edge OCR: matrículas y códigos BIC

**Agente:** `vision-agent`

```
ROL
Eres ingeniero de visión por computador especializado en Edge AI y OCR industrial. Lee AGENTS.md y MEMORY.md primero.

CONTEXTO
Las cámaras de seguridad en las puertas del patio transmiten RTSP. Se debe leer (a) la matrícula del camión/rastra
(formato costarricense; también placas de remolque) y (b) el código BIC del contenedor (ISO 6346: 4 letras con
propietario+categoría U/J/Z, 6 dígitos, 1 dígito de control; el código puede estar horizontal o vertical y
frecuentemente incluye el código de tamaño/tipo, p. ej. "45G1", cerca).

OBJETIVO
Construir en edge-ocr/ un microservicio Python (FastAPI) que procese video/imágenes en CPU (GPU opcional)
y publique eventos de lectura validados al backend y a MQTT.

REQUISITOS FUNCIONALES
1. Detección:
   - YOLOv8-obb (Ultralytics) para localizar placas y regiones de código de contenedor con cajas orientadas.
   - Si no hay pesos propios, usa un modelo preentrenado ligero y documenta cómo reentrenar (script train.py,
     data.yaml de ejemplo, instrucciones de anotación con herramienta libre como Label Studio o CVAT).
2. OCR:
   - PaddleOCR (modelo de reconocimiento ligero, PP-OCR) sobre los recortes enderezados (rotación/perspectiva).
   - Preprocesado configurable: CLAHE, desenfoque/nitidez, binarización adaptativa, rotación de 90° para códigos verticales.
3. Postprocesado y validación:
   - Normalización: mayúsculas, eliminar espacios/guiones, corrección por confusión de caracteres según la
     posición esperada (O↔0, I↔1, S↔5, B↔8, Z↔2, D↔0) — letras en posiciones 1-4, dígitos en 5-11.
   - Validación del dígito de control ISO 6346: valores de letras A=10, B=12, C=13 … (se saltan los múltiplos
     de 11: 11, 22, 33), cada carácter × 2^posición (posición 0-9), suma, módulo 11, y el resultado módulo 10 debe
     igualar el 11.º carácter. Implementa y prueba con códigos conocidos válidos e inválidos.
   - Si el check digit falla, intenta corregir 1 carácter dentro de las confusiones permitidas y marca
     `corrected=true`; si no hay corrección posible, `valid=false` y se envía a revisión manual.
   - Consenso temporal: agrupar lecturas de N frames (ventana configurable) y votar por carácter/posición;
     deduplicar eventos del mismo vehículo en ≤ 30 s.
4. Entradas: `POST /v1/recognize` (imagen multipart), `POST /v1/streams` (registrar RTSP con id de puerta, FPS
   objetivo, ROI), `DELETE /v1/streams/{id}`, `GET /healthz`, `GET /metrics` (Prometheus).
5. Salidas: evento JSON {eventId, gateId, timestamp, kind: PLATE|CONTAINER, rawText, normalizedText, confidence,
   isoValid, corrected, bbox(obb), evidenceImagePath, modelVersion, latencyMs} publicado en MQTT
   `yard/{yardId}/gate/{gateId}/events` y reintentado por HTTP al backend (cola local con reintentos exponenciales
   si el backend no está disponible; persistencia en SQLite/archivo, no pérdida de eventos).
6. Privacidad: recorte/blur configurable de rostros y retención de imágenes con TTL.

REQUISITOS NO FUNCIONALES
- Python 3.11, FastAPI, pydantic v2, pytest, ruff/black, Dockerfile multi-stage (imagen CPU y variante GPU).
- Latencia objetivo ≤ 800 ms por frame en CPU de 8 núcleos (resolución 1280×720); reporta lo medido.
- Configuración por variables de entorno y config.yaml; modelos montados por volumen, sin descargar en tiempo de ejecución.

DATOS Y EVALUACIÓN
- Genera un script `tools/make_synthetic_dataset.py` que cree imágenes sintéticas de códigos BIC/placas
  (fuentes libres, ruido, rotación, brillo, desenfoque) con ground-truth en CSV.
- Script `tools/evaluate.py` que calcule: exactitud por código completo, exactitud por carácter (CER),
  % con check digit válido, % corregidos, latencia p50/p95. Guarda resultados en docs/experiments/.

PRUEBAS (mínimo)
- Unitarias: normalización, check digit (≥ 10 casos), consenso temporal, deduplicación.
- Integración: contenedor del servicio + imagen sintética → evento en MQTT (Testcontainers o docker compose de test).

CRITERIOS DE ACEPTACIÓN
- `docker compose up edge-ocr` arranca y `curl -F image=@sample.jpg localhost:8001/v1/recognize` devuelve JSON válido.
- Check digit implementado correctamente (el ejemplo clásico CSQU3054383 debe validar como correcto).
- Evaluación sobre ≥ 200 imágenes sintéticas con métricas reales reportadas.

NO HACER
- No usar APIs OCR de nube. No inventar métricas. No incluir pesos de modelos con licencia restrictiva sin documentarlo.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P3 — Backend Spring Boot: puertas, citas y bitácora digital

**Agente:** `backend-agent`

```
ROL
Eres desarrollador backend senior Java/Spring Boot. Lee AGENTS.md y MEMORY.md; respeta el esquema de P1 y el contrato de P2.

OBJETIVO
Implementar en backend/ el núcleo transaccional: recepción de eventos OCR, gestión de citas y bitácora digital de
entrada/salida "sin fricción documental" (sin papel, con validación automática).

STACK
Java 21, Spring Boot 3.x (Web, Validation, Data JPA, Security, Actuator), Flyway, PostgreSQL, MapStruct,
springdoc-openapi, Testcontainers, JUnit 5, Eclipse Paho (MQTT) o Spring Integration MQTT.

FUNCIONALIDAD
1. Ingesta de eventos: `POST /api/v1/gate-events` (desde edge-ocr) y suscripción MQTT a `yard/+/gate/+/events`.
   Idempotencia por `eventId`. Guardar lectura cruda + normalizada + evidencia.
2. Conciliación automática (servicio `GateReconciliationService`):
   - Empareja matrícula leída con una cita (`appointment`) vigente (ventana ±N minutos configurable).
   - Empareja código BIC con el contenedor esperado (retiro) o lo registra como nuevo (ingreso).
   - Reglas de resultado: AUTO_APROBADO, REQUIERE_REVISION (confianza baja, check digit inválido, matrícula sin cita,
     contenedor ya fuera del patio), RECHAZADO (bloqueado/hold). Devuelve motivo legible en español.
3. Bitácora digital (`visit_log`): máquina de estados ENTRADA_PUERTA → VALIDADO → ASIGNADO_ANDEN/POSICION → EN_PROCESO
   → SALIDA_PUERTA con marca de tiempo por etapa y cálculo de tiempos (espera en puerta, tiempo en patio, tiempo de
   proceso). Transiciones inválidas deben fallar con 409 y ProblemDetail.
4. Cola de revisión manual: endpoints para listar, aprobar, corregir código/matrícula (con auditoría) y rechazar.
5. CRUD con paginación/filtros para: contenedores, camiones, conductores, transportistas, citas, puertas.
6. Exportación: `GET /api/v1/visits/{id}/comprobante` genera un comprobante PDF o HTML imprimible con
   QR (librerías libres: OpenPDF/Flying Saucer, ZXing); y `GET /api/v1/visits/export.csv`.
7. Seguridad: JWT (resource server) con roles OPERADOR_PUERTA, SUPERVISOR, ADMIN, AUDITOR. Perfil `dev` con usuarios
   en memoria; documenta cómo conectar Keycloak (sin obligar a desplegarlo).
8. Auditoría automática (aspecto o listener JPA) hacia `audit_log`.
9. Observabilidad: Actuator, métricas Micrometer propias (eventos/min, % revisión manual, tiempo medio de puerta).

ARQUITECTURA
Paquetes por dominio (gate, yard, shared) con capas api / application / domain / infrastructure.
DTOs como `record`. Sin lógica de negocio en controladores. Manejo global de errores con ProblemDetail.

PRUEBAS (mínimo)
- Unitarias de reglas de conciliación (≥ 12 escenarios incluyendo bordes de ventana de cita y BIC inválido).
- Integración con Testcontainers/PostgreSQL: flujo completo evento OCR → visita → salida.
- Contrato: test que verifica que el OpenAPI generado coincide con docs/api/openapi.yaml (o documenta diferencias).

CRITERIOS DE ACEPTACIÓN
- `./mvnw verify` verde; cobertura JaCoCo ≥ 70 % en paquetes gate.
- `docker compose up backend postgres mosquitto` funcional; Swagger UI accesible.
- Un script `infra/scripts/demo_gate_flow.sh` que simule un ingreso y una salida con curl y muestre los tiempos calculados.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P4 — Motor de stacking inteligente con Google OR-Tools

**Agente:** `optimizer-agent`

```
ROL
Eres especialista en investigación de operaciones y logística portuaria. Lee AGENTS.md y MEMORY.md.

PROBLEMA
Asignar y reubicar contenedores en un patio con bloques, bahías, filas y niveles (apilado máx. configurable), de forma
que los contenedores con salida más próxima queden más accesibles y se minimicen los "restows" (movimientos
improductivos para sacar un contenedor tapado por otros).

OBJETIVO
Implementar en backend/.../optimizer/ un módulo con DOS estrategias intercambiables y un benchmark:
(A) heurística rápida (para decisión en puerta, < 200 ms) y (B) modelo CP-SAT de OR-Tools (para planificación por lotes).

DATOS DE ENTRADA
- Contenedor: id, tamaño (20/40), tipo (dry/reefer/hazmat), peso, ETD (fecha/hora estimada de salida) con
  incertidumbre (± horas), cliente, restricciones (requiere toma eléctrica, IMO class).
- Patio: slots con coordenadas (bloque, bahía, fila, nivel), disponibilidad, distancia a puerta/andén,
  capacidad de carga por nivel, slots reefer con toma, zonas segregadas para hazmat.

MODELO MATEMÁTICO (CP-SAT) — documenta en docs/architecture/optimizer-model.md
- Variables: x[c,s] ∈ {0,1} asignación contenedor→slot.
- Restricciones: un slot por contenedor; un contenedor por slot; soporte (no hay slot de nivel k ocupado sin nivel k-1
  debajo); 40' ocupa dos bahías contiguas; reefer solo en slots con toma; hazmat solo en zonas permitidas;
  peso: más pesado nunca encima de más liviano (o penalización); altura máxima.
- Objetivo (ponderado y configurable): minimizar Σ penalización_bloqueo(c) donde penalización crece cuando un
  contenedor con ETD posterior queda encima de otro con ETD anterior (esperado de restows), + λ·distancia
  a puerta ponderada por cercanía del ETD, + μ·dispersión por cliente (agrupar), + ν·movimientos de reubicación
  si se permite reoptimizar los ya ubicados.
- Soporte de horizonte de tiempo para "plan del día" y modo de reoptimización nocturna con límite de movimientos.

HEURÍSTICA (A)
- Regla: ordenar candidatos de slot por costo = f(ETD relativo de la pila, altura, distancia, compatibilidad);
  "ETD-aware stacking" (colocar sobre contenedores de ETD igual o posterior), con desempate configurable.
- Debe devolver Top-3 recomendaciones con explicación en español ("Se recomienda B3-12-02-N2: queda sobre un contenedor
  que sale 36 h después, a 80 m de la puerta").

API
- `POST /api/v1/optimizer/recommendations` (contenedor entrante → Top-3 slots).
- `POST /api/v1/optimizer/plans` (lote de contenedores y/o reoptimización, con `timeLimitSeconds`) → plan de movimientos
  ordenados + métricas esperadas; ejecución asíncrona con consulta de estado.
- `GET /api/v1/optimizer/metrics` (restows esperados, ocupación, distancia media).

BENCHMARK (obligatorio)
- Generador de escenarios con semilla fija (S: 100, M: 500, L: 2000 contenedores; ETD con distribuciones realistas).
- Comparar: aleatorio, FIFO (primer slot libre), heurística (A) y CP-SAT (B) en: restows esperados, distancia media,
  tiempo de cómputo, ocupación. Presenta tabla y gráfico (CSV + script que genere PNG o Markdown) en docs/experiments/.
- Reporta mejora porcentual real frente a FIFO. Si CP-SAT no escala a L, documenta el límite y usa descomposición
  por bloque o ventana deslizante.

PRUEBAS
- Propiedad: nunca produce solución que viole restricciones físicas (test con ≥ 200 escenarios aleatorios).
- Casos manuales deterministas (pila simple, 40' contiguo, reefer, hazmat).
- Timeouts: respeta `timeLimitSeconds` y devuelve la mejor solución encontrada con `status=FEASIBLE`.

CRITERIOS DE ACEPTACIÓN
- Heurística p95 < 200 ms con patio de 5 000 slots.
- Mejora medida vs. FIFO documentada (cualquiera que sea el valor; no inflar).
- Todo funciona sin salir de Java + OR-Tools oficial.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P5 — Predicción de congestión en puertas y andenes

**Agente:** `optimizer-agent`

```
ROL
Eres científico de datos aplicado a operaciones logísticas. Lee AGENTS.md y MEMORY.md.

OBJETIVO
Construir un módulo de predicción de congestión (cola y tiempo de espera) para puertas y andenes con horizonte de
15, 30 y 60 minutos, ejecutable localmente y explicable para el supervisor.

DATOS
- Históricos de `visit_log` y `gate_event` (llegadas, inicio/fin de servicio, puerta/andén, tipo de operación).
- Citas programadas (`appointment`) como señal de demanda futura.
- Calendario (día de la semana, hora, feriados de Costa Rica), y opcionalmente llegada de buques (tabla manual).
Si no hay histórico suficiente, genera un simulador de eventos discretos (SimPy en Python o en Java con
Apache Commons Math) con picos matutinos, llegadas Poisson no homogéneas y tiempos de servicio lognormales;
documenta los parámetros y guarda la semilla.

ENFOQUES A IMPLEMENTAR Y COMPARAR
1. Base teórica: modelo de colas M/M/c y M/G/c (aproximación de Allen-Cunneen) por puerta, con tasas estimadas por franja horaria.
2. Estadístico: suavizado exponencial (Holt-Winters) o ARIMA estacional del número de llegadas por intervalo de 15 min.
3. ML ligero: gradient boosting (Smile en Java, o scikit-learn/LightGBM en un microservicio Python opcional "forecast/")
   para predecir tiempo de espera usando: cola actual, citas en la próxima hora, hora/día, mix de operaciones,
   puertas activas. Justifica la elección de implementación en un ADR.
Elige un enfoque híbrido final (p. ej. colas + corrección ML del residual) si mejora en validación.

VALIDACIÓN
- Partición temporal (entrenar con el pasado, validar con el futuro; sin fuga de datos).
- Métricas: MAE, RMSE, MAPE y cobertura de intervalo de predicción al 80 %. Compara con línea base ingenua
  (espera actual persiste) y reporta mejora real.

API Y EVENTOS
- `GET /api/v1/congestion/forecast?gateId=&horizon=` → {gateId, horizonMin, expectedWaitMin, p10, p90, queueLength,
  riskLevel: BAJO|MEDIO|ALTO, drivers: [factores principales en español]}.
- `GET /api/v1/congestion/heatmap?from=&to=` → matriz hora × día de congestión histórica.
- Recomendaciones accionables: "Abrir carril 2 en 20 min", "Reprogramar citas de 10:00-10:30" (reglas simples sobre la predicción).
- Publicar alerta en MQTT `yard/{yardId}/alerts/congestion` y por WebSocket cuando riskLevel ≥ ALTO.
- Job programado (cada 5 min) que recalcula y persiste el pronóstico (tabla `congestion_forecast`).

PRUEBAS
- Unitarias de fórmulas de colas contra valores calculados a mano.
- Prueba de regresión del modelo con dataset sintético fijo (umbral de MAE).
- Prueba de degradación: sin histórico → usa solo modelo de colas y marca `confidence=LOW`.

CRITERIOS DE ACEPTACIÓN
- Notebook o script reproducible (docs/experiments/EXP-NNN-congestion/) con tablas de métricas y gráficos.
- Mejora medida frente a la línea base ingenua documentada.
- Explicabilidad: cada pronóstico devuelve al menos 2 `drivers` interpretables.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P6 — Trazabilidad en tiempo real de la flota interna

**Agente:** `backend-agent`

```
ROL
Eres ingeniero backend especializado en sistemas en tiempo real e IoT. Lee AGENTS.md y MEMORY.md.

OBJETIVO
Implementar la trazabilidad en tiempo real de montacargas, reach stackers y rastras dentro del patio, con ingesta de
telemetría, estado operativo, geocercas y difusión al frontend.

CONTEXTO TÉCNICO
Cada vehículo envía telemetría por MQTT (GPS/UWB/BLE, según equipo) cada 1-5 s a
`yard/{yardId}/fleet/{vehicleId}/telemetry` con: {vehicleId, ts, lat, lon, speedKmh, headingDeg, batteryPct/fuelPct,
status: LIBRE|EN_MOVIMIENTO|CARGANDO|DESCARGANDO|AVERIA, workOrderId?, containerCode?}.

REQUISITOS
1. Ingesta MQTT (Eclipse Mosquitto) con validación de esquema, tolerancia a duplicados y mensajes desordenados;
   descartar posiciones imposibles (salto > velocidad máxima física).
2. Persistencia: `vehicle_position` como serie temporal (particionado por día, índice BRIN/btree), retención configurable
   y downsampling (1 punto/10 s tras 24 h). Usa PostGIS solo si lo justificas en un ADR.
3. Estado actual en memoria (caché con Caffeine) + último punto en tabla `vehicle_last_state` para consultas rápidas.
4. Geocercas (polígonos GeoJSON): puertas, andenes, zonas hazmat, áreas prohibidas. Eventos de entrada/salida de zona y
   alertas por: exceso de velocidad, ralentí prolongado > N min, vehículo en zona prohibida, batería baja, sin señal > 60 s.
5. Órdenes de trabajo (`work_order`): crear, asignar al vehículo libre más cercano (heurística por distancia + prioridad),
   iniciar, completar; tiempos de ciclo por movimiento y utilización del equipo (% productivo/ocioso).
6. Difusión en tiempo real: WebSocket/STOMP `/topic/yards/{yardId}/fleet` con posiciones agregadas (throttle 1 s) y
   `/topic/yards/{yardId}/alerts`.
7. REST: `GET /api/v1/fleet/vehicles`, `GET /api/v1/fleet/vehicles/{id}/track?from=&to=` (GeoJSON LineString),
   `GET /api/v1/fleet/kpis` (utilización, distancia recorrida, tiempo medio de ciclo, alertas por tipo).
8. Trazabilidad de contenedor: `GET /api/v1/containers/{code}/trace` que une eventos de puerta, movimientos de patio,
   vehículos que lo movieron y posición histórica en una línea de tiempo.

SIMULADOR (obligatorio)
Crear simulators/fleet_sim.py (o Java) que genere N vehículos (configurable 5–50) moviéndose por rutas dentro del plano
del patio demo, publicando por MQTT con semilla fija, con inyección de fallos (pérdida de señal, salto GPS, avería).

PRUEBAS Y RENDIMIENTO
- Pruebas de integración con Testcontainers (Mosquitto + PostgreSQL).
- Prueba de carga: 50 vehículos × 1 msg/s durante 5 min; reporta uso de CPU/memoria y latencia
  mensaje→WebSocket (p50/p95).

CRITERIOS DE ACEPTACIÓN
- Latencia telemetría→cliente WebSocket p95 ≤ 2 s medida y documentada.
- Geocercas y alertas disparan correctamente en pruebas (≥ 6 casos).
- No se pierden mensajes en el escenario de carga (reportar conteo enviado vs. persistido).

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P7 — Frontend React + Tailwind CSS

**Agente:** `frontend-agent`

```
ROL
Eres desarrollador frontend senior (React + TypeScript) con criterio de diseño UX para operaciones logísticas.
Lee AGENTS.md y MEMORY.md; consume los contratos OpenAPI/WebSocket de P3–P6 (si algo no existe aún, usa un mock con
MSW y documenta la brecha).

OBJETIVO
Construir en frontend/ la interfaz web para operadores de puerta, supervisores de patio y gerencia.

STACK
React 18, Vite, TypeScript estricto, Tailwind CSS, React Router, TanStack Query, Zustand (estado ligero),
STOMP.js/WebSocket nativo, Recharts (gráficos), Leaflet o SVG propio (plano del patio), Vitest + Testing Library,
Playwright (e2e opcional), cliente API generado desde openapi.yaml (openapi-typescript).

PANTALLAS
1. **Panel de control (Dashboard)**: KPIs (camiones en patio, espera media en puerta, ocupación del patio %,
   movimientos del día, alertas activas), semáforo de congestión por puerta con pronóstico 15/30/60 min y gráfico
   histórico vs. pronóstico.
2. **Puertas / Monitor de lecturas**: lista en vivo de eventos OCR con miniatura de evidencia, texto leído,
   confianza, validez ISO 6346 y estado (auto-aprobado/revisión/rechazado). Cola de revisión manual con edición
   rápida del código/matrícula, atajos de teclado (Enter aprobar, Esc rechazar) y registro de motivo.
3. **Mapa del patio**: vista por bloques/bahías/filas/niveles con colores por ETD (próximo a salir = caliente), filtros
   por cliente/tipo, búsqueda por código BIC, detalle del slot, y panel de recomendaciones de stacking (Top-3) para un
   contenedor entrante con la explicación del optimizador; botón para aceptar y generar la orden de movimiento.
4. **Flota en tiempo real**: mapa con vehículos en movimiento (ícono por estado), estela de recorrido, geocercas,
   panel de alertas y KPIs de utilización.
5. **Bitácora digital**: tabla filtrable/paginada de visitas (placa, contenedor, transportista, tiempos por etapa),
   detalle con línea de tiempo, descarga de comprobante PDF y exportación CSV.
6. **Trazabilidad de contenedor**: buscador por código y línea de tiempo (puerta → patio → vehículos → salida).
7. **Administración**: usuarios/roles (según permisos), puertas, parámetros de optimización y umbrales de alertas.

REQUISITOS UX/UI
- Textos en español (es-CR), formato de fecha/hora local, i18n preparado (react-i18next).
- Diseño responsivo (tablet en caseta de puerta como dispositivo principal), modo oscuro, contraste WCAG AA, foco visible,
  navegación por teclado y roles ARIA en tablas/alertas.
- Estados vacíos, de carga (skeletons) y de error con reintento en todas las vistas.
- Sistema de diseño ligero con componentes reutilizables (Button, Badge, Card, DataTable, Modal, Toast, StatusDot).
- Paleta sobria industrial; evita saturar; usa color semántico solo para estado.
- Autenticación JWT con rutas protegidas y ocultamiento de acciones según rol.

RENDIMIENTO
- Code-splitting por ruta; la lista de eventos en vivo y la tabla de bitácora deben virtualizarse (TanStack Virtual).
- WebSocket con reconexión automática y indicador de estado de conexión visible.
- Bundle inicial documentado (< 250 kB gzip para la ruta de login/dashboard; reporta el real).

PRUEBAS
- Vitest/Testing Library: ≥ 15 pruebas (componentes clave, cola de revisión, filtros, reducer de eventos en vivo).
- Un flujo e2e con Playwright (login → ver evento OCR → corregir código → aparece en bitácora).

CRITERIOS DE ACEPTACIÓN
- `npm run build`, `npm run lint`, `npm run test` verdes.
- `docker compose up frontend` sirve la app con nginx y proxy a la API.
- Capturas de pantalla (Playwright) de cada pantalla guardadas en docs/experiments/ como evidencia.
- Reporte de accesibilidad con axe (número de violaciones) incluido.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## P8 — Simuladores, pruebas e2e e informe de resultados

**Agente:** `qa-docs-agent`

```
ROL
Eres ingeniero QA y redactor técnico. Lee AGENTS.md y MEMORY.md. Tu trabajo es verificar el sistema completo y
consolidar los resultados de todos los experimentos de los agentes.

OBJETIVO
(1) Crear un escenario de demostración reproducible de extremo a extremo, (2) automatizar pruebas e2e y de humo y
(3) producir el informe final de experimentación con agentes.

TAREAS
1. Simulador de jornada (simulators/day_sim.py): genera 1 jornada completa de operación con semilla fija —
   llegadas de camiones con citas, imágenes sintéticas de placas/códigos (reutiliza edge-ocr/tools), eventos de puerta,
   telemetría de flota y retiros de contenedores — y la ejecuta acelerada (x10, x60) contra el sistema.
2. Pruebas e2e (tests/e2e/, pytest + requests/Playwright):
   a. Ingreso nominal: imagen → OCR → evento → cita conciliada → visita abierta → recomendación de slot → orden de movimiento.
   b. Lectura dudosa (BIC con check digit inválido) → cola de revisión → corrección manual → visita continúa.
   c. Camión sin cita → REQUIERE_REVISION con motivo claro.
   d. Pico de congestión → alerta en MQTT/WebSocket y visible en el dashboard.
   e. Retiro de contenedor: comparación de restows con optimizador vs. línea base FIFO sobre la misma jornada.
   f. Caída del backend: edge-ocr encola eventos y los entrega al recuperarse (sin pérdidas).
3. Pruebas no funcionales: humo (`make smoke`), carga ligera (k6 o Locust, herramientas libres), y
   verificación de licencias de dependencias (`license-checker`, `mvn license:*`, `pip-licenses`) para confirmar
   que el stack es 100 % open source; lista cualquier excepción.
4. Informe final `docs/INFORME_EXPERIMENTACION.md` con:
   - Resumen ejecutivo (máx. 1 página).
   - Tabla comparativa por prompt (P1–P8): nº de iteraciones, ¿compiló a la primera?, tiempo vs. estimación manual,
     calidad 1-5, alucinaciones detectadas, ajustes al prompt.
   - Métricas del sistema (OCR, stacking, congestión, latencia de flota) — solo medidas reales; "NO MEDIDO" si falta.
   - Análisis de riesgos (privacidad de matrículas, falsos positivos OCR, dependencia de cámaras, calidad de datos).
   - Lecciones sobre ingeniería de prompts y sobre el uso de AGENTS.md/MEMORY.md (¿ayudó la memoria? evidencias).
   - Recomendaciones y hoja de ruta (piloto en un patio real).
5. README.md raíz: qué es, arquitectura, requisitos, `make up`, usuarios demo, cómo correr simuladores y pruebas.

CRITERIOS DE ACEPTACIÓN
- `make demo` levanta todo, carga datos y corre la jornada simulada sin intervención manual.
- Los 6 escenarios e2e pasan o, si fallan, quedan documentados con causa y ticket en MEMORY.md → Problemas abiertos.
- El informe cita rutas a los EXP-NNN de cada prompt y no contiene cifras sin fuente.

[Pegar aquí el bloque "CIERRE OBLIGATORIO"]
```

---

## Prompts opcionales de extensión (para seguir experimentando)

- **P9 — Seguridad y cumplimiento:** modelo de amenazas STRIDE, endurecimiento de contenedores, retención y anonimización de matrículas conforme a la Ley 8968 de Protección de la Persona frente al tratamiento de sus datos personales (Costa Rica), pruebas con OWASP ZAP.
- **P10 — Reentrenamiento del modelo OCR:** pipeline de aprendizaje activo (lecturas corregidas manualmente → dataset → reentrenamiento → comparación A/B de versiones de modelo).
- **P11 — Integración documental:** generación de comprobantes e intercambio con sistemas aduaneros/navieras vía API o EDI simplificado (formato abierto).
- **P12 — Despliegue en el borde:** optimización del modelo (ONNX Runtime/OpenVINO, cuantización INT8) para ejecutar en un mini-PC o Jetson junto a las cámaras.
