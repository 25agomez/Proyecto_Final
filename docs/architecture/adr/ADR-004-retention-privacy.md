# ADR-004 — Politica de retencion de imagenes y matriculas (privacidad)

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** architect (P1)

## Contexto

La matricula es un dato personal bajo la Ley 8968 (Proteccion de la Persona frente al tratamiento de sus
datos personales, Costa Rica). Las imagenes de evidencia pueden contener rostros de conductores y
operadores. El sistema opera localmente, pero la evidencia se guarda en disco.

## Decision

- **Evidencia con retencion configurable por TTL** (`EVIDENCE_RETENTION_DAYS`, por defecto 7 dias) en el
  volumen `edge-evidence`; un job de limpieza borra lo vencido.
- **Blur configurable de rostros** (`FACE_BLUR_ENABLED`, por defecto activo) en el recorte de evidencia.
- **Lectura cruda separada de la normalizada**: se conserva `raw_text` para auditoria y `normalized_text`
  para operar; la retencion de la lectura sigue la del evento.
- **Minimizacion en logs**: los logs de aplicacion no imprimen matriculas completas ni rutas de evidencia
  con identificadores de persona; se registran `eventId` y codigos de contenedor.
- **Nunca persistir Rostros sin necesidad**: el recorte de evidencia se limita a la region del codigo o la
  placa detectada; el resto del frame no se archiva.
- **Acceso restringido** al volumen de evidencia y exportacion de evidencia solo para roles
  `SUPERVISOR` / `AUDITOR` (P3 define el modelo de roles).

## Consecuencias

- **Positivas:** cumple el principio de minimizacion; el volumen de evidencia no crece sin limite;
  auditable sin exponer datos personales en logs.
- **Negativas:** el blur puede reducir la precision de una relectura manual (por eso es configurable);
  la evidencia deja de existir tras el TTL y no puede servir como prueba a largo plazo (se acepta y se
  documenta en el informe de riesgos de P8).
- **A registrar:** cualquier ampliacion del TTL o del alcance de la evidencia requiere un ADR nuevo.

## Alternativas consideradas

- **Guardar la evidencia de forma indefinida:** descartado por costo y por minimizacion de datos.
- **No guardar evidencia:** descartado porque la cola de revision manual necesita soporte visual.
- **Guardar solo el recorte, nunca el frame completo:** es lo que se adopta (el recorte ya es el recorte).
