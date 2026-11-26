# infra — Infraestructura local (Docker Compose)

Configuracion de la pila local: PostgreSQL 16, Eclipse Mosquitto, edge-ocr, backend, frontend y
(un perfil opcional) Prometheus + Grafana.

## Contenido

| Ruta | Proposito |
|---|---|
| `docker-compose.yml` | Servicios, healthchecks, red interna `lp-internal` y volumenes |
| `.env.example` | Plantilla de variables de entorno (copiar a `.env`, nunca versionar secretos) |
| `mosquitto/mosquitto.conf` | Broker MQTT (1883) y WebSocket (9001) |
| `prometheus/prometheus.yml` | Scraping de `/actuator/prometheus` y `/metrics` |

## Uso

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
docker compose logs -f backend
docker compose --profile observability up -d     # Prometheus + Grafana
```

## Notas operativas

- Los **modelos de vision no se descargan en runtime**: se montan en el volumen `edge-models`.
- La evidencia de imagenes vive en `edge-evidence` con TTL configurable (`EVIDENCE_RETENTION_DAYS`).
- El servicio `backend` aplica las migraciones Flyway al arrancar (Flyway + Spring Boot).
- CORS esta restringido por `CORS_ALLOWED_ORIGINS` (no usar `*`).
