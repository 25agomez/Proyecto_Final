# tests/e2e — Pruebas de extremo a extremo

Escenarios de P8, ejecutados contra la pila levantada (`make up`).

| Escenario | Que valida |
|---|---|
| a. Ingreso nominal | imagen -> OCR -> evento -> cita conciliada -> visita abierta -> recomendacion de slot -> orden de movimiento |
| b. Lectura dudosa | BIC con digito de control invalido -> cola de revision -> correccion manual -> la visita continua |
| c. Camion sin cita | resultado `REQUIERE_REVISION` con motivo legible |
| d. Pico de congestion | alerta en MQTT/WebSocket y visible en el dashboard |
| e. Retiro de contenedor | comparacion de restows: optimizador vs. FIFO sobre la misma jornada |
| f. Caida del backend | edge-ocr encola los eventos y los entrega al recuperarse, sin perdidas |

## Ejecucion

```bash
make smoke      # prueba de humo rapida
pytest tests/e2e -v
```

Prerrequisitos: la pila levantada (`docker compose -f infra/docker-compose.yml up -d`) y las variables
`BACKEND_BASE_URL`, `MQTT_HOST` y `YARD_ID` definidas.
