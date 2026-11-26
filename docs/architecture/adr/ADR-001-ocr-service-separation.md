# ADR-001 — Servicio OCR separado en Python/FastAPI

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** architect (P1)

## Contexto

La deteccion de matriculas y codigos BIC requiere modelos de vision por computador (YOLOv8-obb, PaddleOCR)
cuyo ecosistema practico es Python (PyTorch, ONNX,Ultralytics). El nucleo transaccional del sistema es Java.

## Decision

El OCR vive en `edge-ocr/`, un microservicio Python 3.11 + FastAPI, y se comunica con el backend por
contrato HTTP (`POST /api/v1/gate-events`) y MQTT. El backend nunca invoca inferencia: solo consume eventos.

## Consecuencias

- **Positivas:** el borde puede ejecutarse en CPU (min-PC junto a las camaras) sin arrastrar la JVM;
  el ciclo de upgrade de modelos es independiente del backend; el borde funciona aunque el backend caiga
  (cola local durable con reintentos).
- **Negativas:** dos lenguajes y dos artefactos de build; el contrato entre servicios pasa a ser una
  frontera que puede romperse (mitigado por JSON Schema en `edge-ocr/schemas/` y OpenAPI);
  se requiere versionado del evento (`modelVersion`).
- **Neutras:** el servicio se despliega con dos variantes de imagen (CPU y GPU) vía `Dockerfile` multi-stage.

## Alternativas consideradas

- **Java con DJL/ONNX Runtime:** evitaria el segundo lenguaje, pero complica el preprocesado y el
  soporte de modelos orientados; el ecosistema PaddleOCR/Ultralytics es Python-nativo.
- **Servicio unico en Python:** descartado por complejidad del dominio transaccional (bitacora, permisos).
