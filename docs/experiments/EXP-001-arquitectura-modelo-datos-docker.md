# EXP-001 — Arquitectura, modelo de datos y orquestación Docker

- **Fecha:** 2026-10-22
- **Agente:** architect (OpenCode / big-pickle)
- **Prompt usado:** P1 (versión del prompt: v1, `PROMPTS.md` sin modificar)
- **Objetivo:** Generar la base técnica del monorepo: estructura de carpetas, modelo de datos PostgreSQL 16 con Flyway, contratos OpenAPI/MQTT/JSON Schema, infraestructura Docker y ADRs.

## Resultado

- **Entregables generados (rutas):**
  - Modelo de datos: `backend/src/main/resources/db/migration/V1__init.sql` (76 sentencias), `V2__seed_demo.sql` (16 sentencias)
  - Contratos: `docs/api/openapi.yaml` (OpenAPI 3.1), `edge-ocr/schemas/gate-event.schema.json` (JSON Schema 2020-12)
  - Infraestructura: `infra/docker-compose.yml`, `infra/.env.example`, `infra/mosquitto/mosquitto.conf`, `infra/prometheus/prometheus.yml`, `Makefile` (raíz), `.gitignore`
  - Arquitectura: `docs/architecture/c4-containers.md`, `docs/architecture/adr/ADR-001..004`
  - Andamiaje de los tres stacks: `backend/pom.xml`, `backend/Dockerfile`, `edge-ocr/requirements.txt`, `edge-ocr/Dockerfile`, `frontend/package.json`, `frontend/Dockerfile`, `frontend/nginx/default.conf`, `frontend/src/*`, `frontend/{index.html,vite.config.ts,tsconfig.json,tailwind.config.js,postcss.config.js}`
  - READMEs por módulo (no vacíos): `backend/`, `edge-ocr/`, `frontend/`, `infra/`, `simulators/`, `tests/e2e/`
- **¿Compila / corre a la primera?** **No verificable en este entorno.** Docker no está disponible (WSL sin integración de Docker Desktop y distro `docker-desktop` en estado `Stopped`), por lo que `docker compose up -d postgres` **no se pudo ejecutar**. El código Java/TS no se compiló (sin JDK Linux ni Node). Sí se validó: sintaxis SQL con el parser real de PostgreSQL, YAML, JSON, OpenAPI 3.1 y la lógica generativa del seed.
- **Nº de iteraciones/correcciones necesarias:** 3 correcciones de fondo (ver "Problemas").
- **Tiempo aproximado agente vs. estimación manual:** ~1 h 40 min de sesión para el alcance de P1 (estimación manual equivalente: 6–10 h).

## Métricas (reales o "NO MEDIDO")

| Métrica | Valor | Cómo se midió |
|---|---|---|
| Volumen del seed: bloques | 6 | Generado por `V2__seed_demo.sql` (INSERT de 6 filas) |
| Volumen del seed: slots | 630 | Simulación exacta de la lógica SQL (`generate_series` bays/rows/tiers) — NO ejecutada contra PostgreSQL |
| Volumen del seed: contenedores | 110 | Ídem; 110/110 con dígito de control ISO 6346 válido (verificado con el algoritmo de §P2) |
| Colocaciones activas (posiciones) | 110 (90 en tier 1 + 20 en tier 2) | Simulación; sin solapamiento de bahías y con soporte verificado en cada tier > 1 |
| Códigos BIC con dígito de control válido | 110/110 (100 %) | Algoritmo ISO 6346 reimplementado en Python y aplicado a los 110 códigos generados |
| Sentencias SQL que parsean | 92/92 (100 %) | `pglast` (wrapper de `libpg_query`, gramática real de PostgreSQL) |
| OpenAPI 3.1 válido | Sí | `openapi-spec-validator` 0.7.x |
| YAML/JSON válidos | Sí | `PyYAML` / `json` sobre compose, openapi, prometheus, schema y package.json |
| `docker compose up -d postgres` aplica migraciones | **NO MEDIDO** | Sin Docker en el entorno (§9 de MEMORY.md) |
| Iso 6346 check digit en PostgreSQL | **NO MEDIDO** | La función `bic_check_digit()` se validó contra `CSQU3054383` en la simulación, no en el motor |
| Carga y comportamiento del backend | **NO MEDIDO** | Sin JDK Linux (`.mvnw verify` no ejecutable) |
| Compilación del frontend | **NO MEDIDO** | Sin Node/npm instalados |

## Calidad del código generado (1-5)

Corrección: 4 | Legibilidad: 4 | Seguridad: 4 | Cobertura de requisitos: 4

- **Corrección (4):** el SQL parsea y la lógica del seed está verificada por simulación, pero **no se ejecutó** contra PostgreSQL; por eso no es 5.
- **Legibilidad (4):** migraciones comentadas en inglés, ADR con formato contexto/decisión/consecuencias, comandos en el `Makefile`.
- **Seguridad (4):** CORS por variable de entorno, sin secretos versionados (`.env.example`), roles y retención de evidencia definidos, trigger que valida el dígito de control antes de persistir un contenedor.
- **Cobertura de requisitos (4):** todos los entregables de P1 existen; falta el criterio de aceptación "compose levanta y migra" por la limitación del entorno.

## Problemas y alucinaciones detectadas

1. **Bug real encontrado y corregido en el seed:** `WHERE code > 'LRGU000090'` comparaba el código completo de 11 caracteres, que termina en el dígito de control; por eso el contenedor #90 también entraba en el grupo de tier 2 y **7 contenedores quedaban con dos posiciones activas**, violando el índice único `uq_container_position_active_container` (la migración habría fallado en `docker compose up`). Corregido comparando el serial numérico: `substring(code FROM 5 FOR 6)::INT > 90`.
2. **UUID inválidos en el seed:** se generaban con `('7' || lpad(i::text,8,'0'))::uuid` (9 caracteres, no es un UUID). Corregido a la forma `70000000-0000-7000-8000-<12 dígitos>`.
3. **`python3 -m venv` roto en este entorno** (PEP 668 + sin `ensurepip`): se resolvió con `venv --without-pip` + `get-pip.py`; documentado en AGENTS.md §2 para no repetir el rodeo.
4. **Riesgo de alucinación evitado:** al no poder ejecutar PostgreSQL, no se afirmó que las migraciones "aplican sin error"; se separó explícitamente "sintaxis verificada" de "migración ejecutada".

## Ajustes sugeridos al prompt

- **P1:** añadir al criterio de aceptación una herramienta de verificación alternativa cuando Docker no esté disponible (por ejemplo "el SQL debe parsear con `pglast` y el seed debe simularse"), para que el agente pueda cerrar el prompt sin Docker.
- **P1:** indicar explícitamente que las claves `id` del seed deben ser UUID v7 válidos (el prompt no lo dice y el formato de 9 dígitos es un error fácil).
- **General:** documentar en `AGENTS.md` que el "bloque de cierre obligatorio" puede cerrarse con **NO MEDIDO** por falta de toolchain, para no inducir cifras inventadas.

## Conclusión

La base técnica (modelo de datos, contratos, infraestructura, ADRs y andamiaje de los tres stacks) quedó creada y **validada sintácticamente** con herramientas reales y open source (`pglast`, `openapi-spec-validator`, `PyYAML`). Los dos criterios de aceptación dependent de Docker y del toolchain (levantar Postgres con las migraciones aplicadas y compilar backend/frontend) **quedan sin verificar**: están registrados como bloqueo abierto en `MEMORY.md` §9 con la instrucción exacta para el usuario (abrir Docker Desktop y activar la integración WSL de la distro Ubuntu).
