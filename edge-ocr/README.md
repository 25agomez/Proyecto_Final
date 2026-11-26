# edge-ocr — Servicio de reconocimiento en el borde

Microservicio Python 3.11 + FastAPI que lee matriculas y codigos de contenedor (BIC, ISO 6346) desde
camaras RTSP o imagenes aisladas, y publica eventos validados al backend (HTTP) y a MQTT.

## Responsabilidades

- Deteccion de regiones de placa y de codigo con cajas orientadas (YOLOv8-obb / Ultralytics).
- OCR sobre el recorte enderezado (PaddleOCR / PP-OCR).
- Normalizacion y validacion del digito de control ISO 6346 (correccion de un caracter si es posible).
- Consenso temporal por ventana de frames y deduplicacion de eventos del mismo vehiculo.
- Publicacion del evento con la forma definida en `schemas/gate-event.schema.json`.
- Cola local durable con reintentos exponenciales: si el backend no responde, no se pierde el evento.
- Privacidad: recorte minimo (blur de rostros configurable) y retencion TTL de la evidencia.

## Contrato

| Item | Valor |
|---|---|
| HTTP | `POST /v1/recognize` (multipart), `POST /v1/streams`, `DELETE /v1/streams/{id}`, `GET /healthz`, `GET /metrics` |
| MQTT | `yard/{yardId}/gate/{gateId}/events` |
| Evento | `schemas/gate-event.schema.json` (JSON Schema 2020-12) |
| Backoff | exponencial con jitter, tope configurable, cola en disco |

## Estructura

```
edge-ocr/
├── app/                  # FastAPI (rutas, settings, publisher)
│   ├── main.py
│   ├── config.py
│   ├── api/
│   ├── pipeline/         # deteccion, OCR, normalizacion, consenso, deduplicacion
│   └── infra/            # MQTT, cola durable, evidencia
├── tools/
│   ├── make_synthetic_dataset.py   # dataset sintetico con ground truth
│   └── evaluate.py                # metricas: exactitud, CER, latencia p50/p95
├── datasets/             # data.yaml de ejemplo + guia de anotacion (CVAT/Label Studio)
├── train.py              # reentrenamiento y exportacion a ONNX del detector
├── tests/                # pytest
├── schemas/              # contrato del evento
├── models/               # pesos montados por volumen (nunca se descargan en runtime)
└── Dockerfile            # multi-stage: variante cpu y gpu
```

## Ejecutar

```bash
# Local (venv con Python 3.11)
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8001

# Docker
docker build -t lp-edge-ocr:cpu --build-arg VARIANT=cpu .
docker run --rm -p 8001:8001 -v lp-models:/models lp-edge-ocr:cpu
```

## Dataset sintetico y evaluacion

```bash
# 240 imagenes (120 contenedores + 120 placas) con ground truth en CSV.
# La semilla es fija: dos ejecuciones producen los mismos bytes.
python tools/make_synthetic_dataset.py --output data/synthetic --count 240 --seed 20260222

# Modo `corrupt`: mide la capa de post-procesamiento con un modelo de error simulado.
python tools/evaluate.py --dataset data/synthetic --mode corrupt --out ../docs/experiments/EXP-002-ocr-evaluation.json

# Modo `preprocess`: mide la latencia de recorte + CLAHE + binarizacion + rotaciones.
python tools/evaluate.py --dataset data/synthetic --mode preprocess --out ../docs/experiments/EXP-002-ocr-preprocess.json

# Modo `live`: pipeline completo (YOLOv8-obb + PaddleOCR). Sin pesos responde NO MEDIDO (exit 2).
python tools/evaluate.py --dataset data/synthetic --mode live --weights /models
```

Las imagenes generadas no se versionan (`edge-ocr/data/` esta en `.gitignore`): se regeneran con el
comando anterior. Los informes JSON/Markdown si se versionan en `docs/experiments/`.

Resultados medidos (EXP-002, CPU de 12 nucleos, 240 imagenes):

| Metrica (modo `corrupt`) | Valor |
|---|---|
| Exactitud por codigo completo antes del post-proceso | 60.00 % |
| Exactitud por codigo completo despues del post-proceso | 74.58 % |
| CER antes / despues | 0.0472 / 0.0347 |
| Check digit valido | 95.83 % |
| Lecturas corregidas (`corrected=true`) | 16.67 % |
| Lecturas que van a revision manual | 4.17 % |
| Latencia p50 / p95 del post-proceso | 0.003 ms / 0.018 ms |
| Latencia p50 / p95 del recorte + preprocesado | 7.09 ms / 11.46 ms |

> El modo `corrupt` mide la capa de post-procesamiento sobre un modelo de error de OCR simulado
> (misma tabla de confusiones que usa la reparacion). La precision del OCR neuronal y la latencia
> de deteccion+reconocimiento estan **NO MEDIDO** hasta que se monten los pesos (`--mode live`).

## Pruebas y calidad

```bash
pytest -q        # 94 pruebas
ruff check .     # lint
black --check app tests tools
```

## Reentrenamiento del detector

Sin pesos propios se parte de `yolov8n-obb.pt` (Ultralytics, AGPL-3.0) y se
fine-tunea con el dataset en formato YOLO-OBB:

```bash
python train.py --data datasets/data.yaml --epochs 50 --imgsz 1280 --name detector
# -> runs/detector/weights/best.pt y detector.onnx (el que monta /models)
```

La guia de anotacion con CVAT y Label Studio, el formato de etiquetas y las
licencias estan en `datasets/README.md`.

## Licencias

| Componente | Licencia | Nota |
|---|---|---|
| FastAPI, Pydantic, Uvicorn, httpx, PyYAML | MIT / BSD / Apache-2.0 | Sin copyleft |
| PaddleOCR / PaddlePaddle | Apache-2.0 | Sin copyleft |
| Ultralytics (YOLOv8) | AGPL-3.0 | Copyleft fuerte: un servicio derivado debe ofrecer su codigo fuente. **A revisar antes de un despliegue cerrado** (ver `MEMORY.md` §3, ADR-005). |
| OpenCV (opencv-python-headless) | Apache-2.0 | |
| NumPy / Pillow | BSD-3 / MIT-CMU | |
| DejaVu (fuente del dataset sintetico) | Bitstream Vera / Arev | Fuente libre del sistema |

## Estado

Implementado en P2. Los pesos de los modelos **no** se distribuyen con el repositorio: se montan en
`/models` (ver `.env.example`). Sin pesos montados el servicio arranca y responde `/healthz`, pero
`/v1/recognize` devuelve error explicito en vez de inventar lecturas.
