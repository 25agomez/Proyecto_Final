# simulators — Generadores de datos y telemetria

Todos los simuladores son **deterministas** (semilla fija) para que las_metricas sean reproducibles.
Ninguno usa datos reales de clientes.

| Script | Que genera |
|---|---|
| `fleet_sim.py` | Telemetría MQTT de N vehiculos (5-50) por rutas del patio demo, con inyeccion de fallos: perdida de señal, salto GPS, averia. |
| `day_sim.py` | Una jornada completa: llegadas con cita, imagenes sinteticas de placas/codigos, eventos de puerta, telemetria y retiros, ejecutada acelerada (x10, x60). |
| `run_demo.sh` | Orquestador de `make demo`: levanta la pila, espera el backend y corre la jornada. |

## Uso

```bash
python simulators/fleet_sim.py --vehicles 20 --seed 42 --rate 1
python simulators/fleet_sim.py --vehicles 8 --dry-run --ticks 5
python simulators/day_sim.py --seed 42 --speed 10
```

Variables de entorno relevantes: `MQTT_HOST`, `MQTT_PORT`, `YARD_ID`, `BACKEND_BASE_URL`.

### `fleet_sim.py`

- **Determinista:** el mismo `--seed` genera la misma secuencia. El reloj es virtual (un tick por
  `1/--rate` segundos).
- `--vehicles` acepta **5–50**; los ocho primeros UUID coinciden con los vehículos sembrados en
  `V2__seed_demo.sql`, el resto se derivan con `uuid5` (no requieren registro previo).
- Recorrido por los pasillos del patio en metros y conversión WGS84 idéntica a `YardFrame` (ADR-013).
- **Fallos inyectados** cada 18 ticks, ciclando: pérdida de señal (`silence`), batería baja (`battery`),
  salto GPS (`jump`), entrada a zona prohibida (`forbidden`), exceso de velocidad (`speed`) y avería
  (`breakdown`). Así se ejercitan todas las alertas del backend.
- `--dry-run` imprime `{"topic": ..., "payload": ...}` por línea (no necesita broker); `--ticks` corta
  la ejecución tras N ticks; `--duration` la corta por tiempo.
- Requiere `paho-mqtt` solo para publicar de verdad (`pip install paho-mqtt`).
- Pruebas: `/tmp/opencode/venv/bin/python -m pytest simulators/tests -q` (8 casos, sin red).
