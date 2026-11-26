# ADR-005 — Licencia de Ultralytics (AGPL-3.0) en el detector del borde

- **Estado:** Aceptada con condicion (revisar antes de un despliegue cerrado)
- **Fecha:** 2026-10-22
- **Ámbito:** vision-agent (P2) / architect

## Contexto

El requisito de P2 es usar YOLOv8-obb (Ultralytics) para las cajas orientadas de matriculas y codigos de
contenedor. Ultralytics publica el codigo bajo **AGPL-3.0**, no MIT ni Apache-2.0. El resto del stack
(FastAPI, PaddleOCR, PaddlePaddle, OpenCV, Spring Boot, React, PostgreSQL, OR-Tools, Mosquitto) es
software libre sin copyleft o con copyleft debil.

AGPL-3.0 es software libre, por lo que no contradice la regla "100 % open source" de `AGENTS.md` §4.1,
pero si impone obligaciones: quien modification el programa y lo ofrece a terceros debe ofrecer tambien
el codigo fuente modificado de la obra completa, incluida la red.

El detector se distribuye como un archivo de pesos (`detector.onnx`) y no como un servicio: la frontera
juridica de la AGPL depende de como se use el modelo (el debate "AGPL en pesos de modelos" esta abierto y
depende de la jurisdiccion).

## Decision

- Se usa Ultralytics YOLOv8-obb como motor de deteccion, **con las siguientes condiciones**:
  1. El servicio de borde es software libre: el codigo del repositorio se publica bajo una licencia
     compatible con AGPL-3.0 (el repositorio completo queda disponible para quien quiera auditarlo).
  2. Los pesos reentrenados (`train.py`, `detector.onnx`) se/licencia/documentan de forma explicita y
     se entrega el `train.py`, el `datasets/data.yaml` y la guia de anotacion junto a ellos, de modo que
     el proceso de entrenamiento sea reproducible por terceros.
  3. `datasets/README.md` documenta la licencia y el riesgo, y `edge-ocr/README.md` incluye la tabla de
     licencias del modulo.
- El runtime del edge **no descarga pesos**: los pesos se montan por volumen (`/models`), lo que evita
  cualquier descarga de codigo en tiempo de ejecucion (tambien cuenta a favor del cumplimiento).
- Alternativa de escape documentada: si el cliente exige una licencia permisiva, la deteccion puede
  sustituirse por ONNX Runtime + un detector OBB equivalente (por ejemplo, el entrenamiento con
  `mmrotate`/MMRotate, Apache-2.0) manteniendo el mismo `Detection` interno; el resto del pipeline
  (recorte, OCR, normalizacion, ISO 6346) no cambia. Ladecision final se tomara con el cliente antes de
  cualquier despliegue comercial.

## Consecuencias

- **Positivas:** deteccion orientada de referencia, con amplia documentacion y comunidad; el modelo base
  `yolov8n-obb.pt` es ligero y apto para CPU; el pipeline es desacoplado del motor (interfaz `DetectorProtocol`).
- **Negativas:** copyleft AGPL-3.0; el cambio a un detector permisivo implica reentrenar y revalidar.
- **Pendiente:** confirmar con el cliente el marco de uso (producto interno vs. SaaS) antes de P8.

## Alternativas consideradas

- **Detector permisivo propio (mmrotate, Apache-2.0):** mejor encaje de licencia, pero exigeolo y
 dataset real; se deja como plan B documentado.
- **Detector de la familia YOLO con licencia MIT (YOLOX, Apache-2.0):** no soporta cajas orientadas de
  forma nativa; se descarto porque el texto vertical de los contenedores exige OBB.
- **No usar deteccion y leer el frame completo con OCR:** descartado por latencia y por perdida de la
  evidencia minima (ADR-004).
