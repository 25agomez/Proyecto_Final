"""Training of the oriented detector (YOLOv8-obb / Ultralytics).

The script is a thin, reproducible wrapper around the Ultralytics trainer:

1. it validates the dataset configuration (``data.yaml``),
2. it trains with the arguments of the CLI,
3. it exports the best checkpoint to ONNX so the edge can load it without
   PyTorch.

The weights are **never** downloaded at runtime by the service: the trained
model is mounted on ``/models`` (see ADR-001). Downloading the pretrained base
model happens here, in the training environment, and only once.

Usage::

    python train.py --data datasets/data.yaml --epochs 50 --imgsz 1280 --name detector
    python train.py --data datasets/data.yaml --weights runs/detector/weights/best.pt --export-only

Licenses: Ultralytics is AGPL-3.0 (open source with copyleft). The trained
weights inherit that licence; see "Licencias" in the README.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

#: Classes of the detector: the index is the class id used by the service.
CLASS_NAMES: dict[int, str] = {0: "PLATE", 1: "CONTAINER"}

DEFAULT_BASE_WEIGHTS = "yolov8n-obb.pt"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse the arguments of the training script."""
    parser = argparse.ArgumentParser(description="Train the YOLOv8-obb detector of the edge OCR")
    parser.add_argument("--data", default="datasets/data.yaml", help="dataset configuration")
    parser.add_argument("--weights", default="", help="checkpoint to continue from")
    parser.add_argument("--base", default=DEFAULT_BASE_WEIGHTS, help="pretrained base model")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=1280)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--device", default="cpu", help="cpu | 0 | 0,1 (GPU)")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--name", default="detector", help="name of the run")
    parser.add_argument("--project", default="runs", help="directory of the runs")
    parser.add_argument("--export-only", action="store_true", help="only export to ONNX")
    parser.add_argument("--onnx", default="detector.onnx", help="path of the exported model")
    return parser.parse_args(argv)


def validate_dataset(data_path: Path) -> dict[str, object]:
    """Read the dataset configuration and check that the paths exist."""
    if not data_path.is_file():
        raise SystemExit(f"no existe la configuracion del dataset: {data_path}")
    try:
        import yaml
    except ImportError as error:  # pragma: no cover - PyYAML is a declared dependency
        raise SystemExit("falta PyYAML: pip install -r requirements.txt") from error

    content = yaml.safe_load(data_path.read_text(encoding="utf-8")) or {}
    for key in ("train", "val", "names"):
        if key not in content:
            raise SystemExit(f"falta la clave '{key}' en {data_path}")
    for key in ("train", "val"):
        images = Path(str(content[key]))
        if not images.is_dir():
            raise SystemExit(
                f"no existe el directorio de imagenes '{images}' declarado en {data_path}"
            )
    return content


def export_onnx(weights: str, output: str, imgsz: int) -> None:
    """Export a checkpoint to ONNX (the format loaded by the edge)."""
    from ultralytics import YOLO

    model = YOLO(weights, task="obb")
    model.export(format="onnx", imgsz=imgsz, simplify=True, dynamic=False)
    exported = Path(weights).with_suffix(".onnx")
    if exported.resolve() != Path(output).resolve():
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).write_bytes(exported.read_bytes())
    print(f"modelo exportado: {output}")


def train(arguments: argparse.Namespace) -> None:
    """Run the training and export the best checkpoint."""
    from ultralytics import YOLO

    data_path = Path(arguments.data)
    validate_dataset(data_path)
    base = arguments.weights or arguments.base
    model = YOLO(base, task="obb")
    model.train(
        data=str(data_path),
        epochs=arguments.epochs,
        imgsz=arguments.imgsz,
        batch=arguments.batch,
        device=arguments.device,
        workers=arguments.workers,
        project=arguments.project,
        name=arguments.name,
        exist_ok=True,
        pretrained=True,
        val=True,
        plots=True,
    )
    best = Path(arguments.project) / arguments.name / "weights" / "best.pt"
    if not best.is_file():
        raise SystemExit(f"el entrenamiento termino sin {best}")
    export_onnx(str(best), arguments.onnx, arguments.imgsz)


def main(argv: list[str] | None = None) -> int:
    """Entry point of the script."""
    arguments = parse_args(argv)
    if arguments.export_only:
        if not arguments.weights:
            raise SystemExit("--export-only requiere --weights")
        export_onnx(arguments.weights, arguments.onnx, arguments.imgsz)
        return 0
    train(arguments)
    return 0


if __name__ == "__main__":
    sys.exit(main())
