# ADR-014 — Alertas de flota en memoria, STOMP con throttle y entrega at-least-once

- **Estado:** Aceptada
- **Fecha:** 2026-10-23
- **Ámbito:** backend-agent (P6); archivo en `docs/architecture/` revisado por `architect`

## Contexto

P6 debe entregar posiciones en tiempo real al tablero (`/topic/yards/{yardId}/fleet`), alertas
(`/topic/yards/{yardId}/alerts`) y KPIs, con **p95 de telemetría→WebSocket ≤ 2 s** y **sin pérdida de
mensajes** en una carga de 50 vehículos × 1 msg/s. Hay tres decisiones abiertas:

1. **Dónde viven las alertas.** El esquema de P1 no tiene tabla de alertas de flota y el contrato MQTT
   solo publica telemetría; el módulo `fleet` no puede editar migraciones ya aplicadas.
2. **Cómo se difunde la telemetría.** MQTT QoS 1 puede duplicar mensajes; Mosquitto entrega en orden
   por cliente, pero el `ts` del payload es la fuente de verdad.
3. **Cada cuánto se empuja al navegador.** Publicar un frame STOMP por mensaje satura el cliente con
   50 vehículos; agrupar demasiado rompe el requisito de 2 s.

## Decisión

1. **Persistencia:** cada mensaje aceptado se escribe en `vehicle_position` (serie particionada por día)
   y se hace *upsert* de `vehicle_last_state` (guard `EXCLUDED.at >= at`, nunca retrocede). La caché
   Caffeine `FleetStateCache` (30 min de expiración por acceso) es la ruta de lectura caliente.
2. **At-least-once con deduplicación por `ts`:**
   - `ts` igual al último estado → `DUPLICATE`, no se persiste;
   - `ts` anterior → `OUT_OF_ORDER`, no se persiste;
   - distancia > `maxSpeed · Δt + 5 m` → `IMPOSSIBLE_JUMP`, se emite `SALTO_GPS` y **no** se persiste
     (el último estado válido se conserva, así el siguiente mensaje se evalúa contra la posición real).
   Cada resultado incrementa su propio contador (`received = persisted + duplicate + outOfOrder +
   impossibleJumps + invalid + unknownVehicle`), que es la base de la comparación enviado vs. persistido.
3. **Alertas solo en memoria** (`FleetAlertBuffer` acotado + `AlertStateStore` por vehículo con
   *cooldown* configurable) y difusión inmediata por `/alerts`. No se crea tabla: son eventos
   informativos y la evidencia durable es la serie de posiciones. Si P8 o una auditoría exige
   histórico de alertas, se añade una tabla **en una migración nueva** del arquitecto (§11).
4. **Throttle de posiciones:** `FleetBroadcaster` acumula *la última* posición de cada vehículo y
   `FleetBroadcastScheduler` hace `flush()` cada `app.fleet.broadcast-interval-ms` (1 s). El payload es
   una **lista por patio** (menos frames que vehículos). Las alertas no pasan por el throttle.
5. **Latencia medida en el punto de entrega:** cada posición guarda el `System.nanoTime()` de su
   recepción y el `flush()` registra `ahora − recepción` en `FleetMetrics`. `GET /fleet/kpis` publica
   p50/p95. Es la latencia ingesta→`convertAndSend`; el salto de red del navegador no se mide aquí.
6. **WebSocket y autenticación:** endpoint `/ws` sin SockJS; el *handshake* HTTP es público (`/ws/**`)
   porque el navegador no puede enviar cabeceras en él, y el frame `CONNECT` lleva
   `Authorization: Bearer <jwt>` que valida `FleetStompAuthInterceptor` con el mismo `JwtDecoder` del
   REST. Sin token, el CONNECT se rechaza.
7. **Retención y downsampling por SQL:** tras 24 h se conserva 1 punto cada 10 s
   (`row_number() OVER (PARTITION BY vehicle_id, floor(epoch/10))`) y a los 30 días se borra. El job es
   idempotente y horario; la ventana reciente nunca se toca.
8. **Pruebas sin broker:** Docker no está disponible en el entorno de desarrollo (MEMORY.md §9 B-001),
   así que el suscriptor expone `handleTopic(topic, payload)` y las pruebas lo llaman directamente con
   `app.fleet.mqtt-enabled=false`. La prueba de carga usa ese mismo camino y mide la latencia del
   pipeline, no la del bróker (declarado en EXP-006).

## Consecuencias

- **Positivas:** sin cambios de esquema, sin pérdida de mensajes medible, latencia p95 < 1 s con el
  throttle de 1 s y consumo de memoria acotado (buffer de 10 000 alertas). Un reinicio del backend
  pierde las alertas recientes pero no las posiciones.
- **Negativas:** las alertas no son auditables a largo plazo; el throttle descarta posiciones
  intermedias para el tablero (quedan en la base); con dos instancias del backend cada una mantiene su
  propio estado de alertas y su propio `last_state` en caché (el guard de SQL evita retrocesos, pero el
  *cooldown* sería por instancia). Escalar horizontalmente exige mover `AlertStateStore` a un almacén
  compartido (Redis) o fijar una sola instancia para la ingesta, igual que ya ocurre con el suscriptor
  MQTT de puerta.
- **Revisión:** si se exige histórico de alertas o varias instancias, añadir tabla `fleet_alert` y
  `AlertStateStore` compartido en un ADR posterior.
