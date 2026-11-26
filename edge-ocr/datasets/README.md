# Anotacion y reentrenamiento del detector

El detector del borde (YOLOv8-obb) se entrena con cajas **orientadas** (OBB):
una matricula esta inclinada en la camara, y una caja rectangular waste el
contexto y falla con el texto vertical de los contenedores.

## 1. Modelo base

Cuando no hay pesos propios se parte de `yolov8n-obb.pt` (la variante mas
pequena y ligera del modelo preentrenado de Ultralytics). El servicio **nunca**
descarga pesos en tiempo de ejecucion: el modelo entrenado se monta por volumen
en `/models` (ver ADR-001). La descarga ocurre solo en este entorno de
entrenamiento.

```bash
python train.py --data datasets/data.yaml --epochs 50 --imgsz 1280 --name detector
# salida: runs/detector/weights/best.pt y detector.onnx
```

El `detector.onnx` exportado es el archivo que espera el servicio
(`DETECTOR_WEIGHTS=detector.onnx`, `MODELS_DIR=/models`).

## 2. Anotacion con herramientas libres

| Herramienta | Licencia | Uso recomendado |
|---|---|---|
| [CVAT](https://www.cvat.ai/) | MIT | Anotacion de video/RTSP: ideal para las camaras de puerta |
| [Label Studio](https://labelstud.io/) | Apache-2.0 | Anotacion de imagenes sueltas y control de calidad |

Procedimiento recomendado:

1. Capturar 2 000-5 000 fotogramas de las camaras de puerta (con licencia o
   con el dataset sintetico de `tools/make_synthetic_dataset.py` como arranque).
2. En CVAT, crear la tarea con el formato **rectanglulos rotados** (poligonos de
   4 puntos) y las dos clases `PLATE` y `CONTAINER` en el orden de
   `datasets/data.yaml`.
3. En Label Studio se usa la plantilla *Rectangle Labels* con
   `rotation` habilitada; el exportador debe producir el formato YOLO-OBB
   (clase + 8 coordenadas normalizadas).
4. Revisar el 10 % de las anotaciones como control de calidad (doble
   anotacion y resolucion de discrepancias).
5. Exportar a `datasets/images/{train,val}` y `datasets/labels/{train,val}` con
   la estructura declarada en `datasets/data.yaml` (80 % train / 20 % val,
   **sin** filtrar por fotograma para no mezclar imagenes del mismo vehiculo
   entre conjuntos).
6. Entrenar y exportar: `python train.py --data datasets/data.yaml ...`.

## 3. Metricas de la reentrenada

El servicio reporta `modelVersion` en cada evento (`obb:<pesos>|ocr:<modelo>`),
de modo que cada lectura queda atribuible a una version del modelo. Para
comparar dos versiones se usa `tools/evaluate.py --mode live` sobre el mismo
dataset sintetico y se comparan exactitud, CER y latencia p95.

## 4. Licencias

| Componente | Licencia | Nota |
|---|---|---|
| Ultralytics (YOLOv8) | AGPL-3.0 | Software libre con copyleft fuerte: obliga a ofrecer el codigo fuente del servicio derivado. Revisar con el cliente antes de un despliegue cerrado. |
| PaddleOCR / PaddlePaddle | Apache-2.0 | Sin copyleft. |
| CVAT | MIT | |
| Label Studio | Apache-2.0 | |
| DejaVu (fuente del dataset sintetico) | Bitstream Vera / Arev | Fuente libre incluida en el sistema. |
