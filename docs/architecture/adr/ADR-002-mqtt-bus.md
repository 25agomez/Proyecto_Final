# ADR-002 — MQTT (Eclipse Mosquitto) como bus de eventos y telemetria

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** architect (P1)

## Contexto

Se necesitan dos flujos con requisitos distintos: eventos de lectura de puerta (pocos, deben llegar y
persistirse) y telemetria de flota (muchos, alta frecuencia, tolerante a perdida).

## Decision

Usar **Eclipse Mosquitto 2** como bus local. Topicos:

- `yard/{yardId}/gate/{gateId}/events` — lecturas OCR publicadas por edge-ocr.
- `yard/{yardId}/fleet/{vehicleId}/telemetry` — posiciones de vehiculos.
- `yard/{yardId}/alerts/congestion` — alertas de pronostico critico.

El backend **suscribe** a `yard/+/gate/+/events` y a `yard/+/fleet/+/telemetry`. La ingesta HTTP
(`POST /api/v1/gate-events`) es el camino primario (idempotente por `eventId`); MQTT es el canal alterno
y el de telemetria.

## Consecuencias

- **Positivas:** broker open source y minimo (sin cluster ni ZooKeeper); un solo protocolo para varios
  consumidores; los simuladores pueden publicar sin conocer el backend (P6, P8).
- **Negativas:** Mosquitto es *broker*, no *bus*: no hay enrutado por contenido ni transformaciones;
  QoS 0 implica que la telemetria puede perderse (aceptado: el ultimo estado se guarda en
  `vehicle_last_state` y la perdida se reporta por conteo enviado vs. persistido).
- **Requiere:** topicos documentados y versionados en este ADR; consumidores idempotentes.

## Alternativas consideradas

- **Kafka:** overkill para un patio on-premise; huella operativa y de licencia mas compleja.
- **Redis Streams:** razonable, pero introduce un motor de datos adicional; se descarta por simpler.
- **Solo HTTP:** no escala a telemetria de 50 vehiculos x 1 msg/s con push al frontend.
