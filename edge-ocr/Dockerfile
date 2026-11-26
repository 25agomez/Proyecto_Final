# Edge OCR service (PaddleOCR + YOLOv8-obb).
# Multi-stage: the builder installs the dependencies in a virtualenv and the
# runtime image only keeps that virtualenv.
#
# Variants:
#   --build-arg VARIANT=cpu  -> solo dependencias ligeras (sin inferencia neuronal)
#   --build-arg VARIANT=gpu  -> anade PaddlePaddle GPU + Ultralytics
#
# La variante GPU no necesita una imagen CUDA: la rueda `paddlepaddle-gpu`
# incluye su runtime CUDA. El host debe tener el driver NVIDIA y
# `nvidia-container-toolkit` instalado (documentado en edge-ocr/README.md).
ARG VARIANT=cpu

FROM python:3.11-slim AS builder
ARG VARIANT
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_ROOT_USER_ACTION=ignore
WORKDIR /build
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt ./
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --upgrade pip \
    && /opt/venv/bin/pip install -r requirements.txt
# Los runtimes pesados se instalan solo en la variante pedida.
RUN case "${VARIANT}" in \
      cpu) echo "variante cpu: sin runtime de inferencia" ;; \
      gpu) /opt/venv/bin/pip install -r requirements-models-gpu.txt ;; \
      *) echo "VARIANT desconocido: ${VARIANT}" >&2; exit 1 ;; \
    esac

FROM python:3.11-slim AS runtime
ARG VARIANT
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    OUTBOX_PATH=/app/data/outbox.db \
    EVIDENCE_DIR=/evidence \
    MODELS_DIR=/models
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /opt/venv /opt/venv
COPY app ./app
COPY schemas ./schemas
COPY tools ./tools
RUN useradd --create-home --uid 10001 edge \
    && mkdir -p /evidence /app/data /models \
    && chown -R edge:edge /evidence /app/data \
    && chmod 755 /models
USER edge
EXPOSE 8001
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8001/healthz || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
