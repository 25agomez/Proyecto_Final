# C4 — Contenedores del sistema (nivel 2)

> Contexto: patio de contenedores en Costa Rica. Camaras RTSP en las puertas, servicio OCR en el borde,
> backend transaccional, optimizador OR-Tools dentro del backend, PostgreSQL, Mosquitto y frontend React.
> Todo se ejecuta localmente (Linux + Docker), sin servicios de nube.

```mermaid
flowchart TB
    subgraph EDGE["Borde del patio (edge-ocr, CPU)"]
        CAM["Camaras RTSP<br/>puertas y andenes"]
        OCR["edge-ocr (FastAPI)<br/>YOLOv8-obb + PaddleOCR<br/>validacion ISO 6346"]
        QUEUE[("Cola local durable<br/>SQLite / archivo")]
        CAM -->|RTSP| OCR
        OCR --> QUEUE
    end

    subgraph BACK["Backend Spring Boot 3 (Java 21)"]
        API["API REST /api/v1<br/>ProblemDetail RFC 7807"]
        GATE["Modulo gate<br/>conciliacion + bitacora"]
        YARD["Modulo yard<br/>patio, slots, contenedores"]
        OPT["Modulo optimizer<br/>heuristica + CP-SAT (OR-Tools)"]
        CONG["Modulo congestion<br/>M/M/c, M/G/c, Holt-Winters"]
        FLEET["Modulo fleet<br/>ingesta MQTT, geocercas"]
        MQTTIN["Suscriptor MQTT<br/>yard/+/gate/+/events"]
        WS["WebSocket / STOMP<br/>/topic/yards/{yardId}/fleet"]
        API --> GATE
        API --> YARD
        API --> OPT
        API --> CONG
        API --> FLEET
        GATE --> MQTTIN
        FLEET --> WS
    end

    MQ[("Eclipse Mosquitto<br/>MQTT 1883 / WS 9001")]
    DB[("PostgreSQL 16<br/>Flyway + PostGIS + pg_trgm")]
    FE["Frontend React 18 + Vite<br/>(nginx)"]
    SIM["Simuladores<br/>telemetria / jornada"]

    OCR -->|"HTTP POST /api/v1/gate-events<br/>(idempotente por eventId)"| API
    OCR -->|"MQTT yard/{yardId}/gate/{gateId}/events"| MQ
    MQ --> MQTTIN
    MQ <-->|"yard/{yardId}/fleet/{vehicleId}/telemetry"| FLEET
    SIM -->|MQTT| MQ
    API --> DB
    GATE --> DB
    YARD --> DB
    OPT --> DB
    CONG --> DB
    FLEET --> DB
    FE -->|"REST / WebSocket"| API
    FE --> WS
```

## Flujos principales

| # | Flujo | Transporte | Notas |
|---|---|---|---|
| 1 | Lectura OCR -> backend | HTTP `POST /api/v1/gate-events` | Idempotencia por `eventId`; reintento exponencial con cola local durable en el borde. |
| 2 | Lectura OCR -> evento | MQTT `yard/{yardId}/gate/{gateId}/events` | Canal alterno/telemetria de eventos para replay y auditoria. |
| 3 | Consecuencia de la lectura | Suscripcion MQTT `yard/+/gate/+/events` | El backend tambien escucha para no depender de un solo camino. |
| 4 | Telemetria de flota | MQTT `yard/{yardId}/fleet/{vehicleId}/telemetry` | 1-5 s por vehiculo; validacion de esquema y descarte de posiciones imposibles. |
| 5 | Difusion al operador | WebSocket/STOMP | Posiciones agregadas con throttle de 1 s; alertas de congestion. |
| 6 | Consulta de patio | REST | Slots, ocupacion, recomendacion Top-3 del optimizador. |

## Decisiones de despliegue

- **Sin dependencia de nube**: todos los componentes son contenedores open source locales.
- **El borde no depende del backend**: si el backend cae, edge-ocr encola y reintenta sin perder eventos.
- **El optimizador vive dentro del backend**: evita un servicio mas y permite usar la misma conexion a datos.
- **PostgreSQL como fuente de verdad** para patio, bitacora, flota y pronosticos persistidos.
