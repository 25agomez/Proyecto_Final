# LogisticaPortuaria CR — Sistema Inteligente de Gestión de Patios Logísticos

Sistema local (on-premise), 100 % open source, para la gestión de patios de contenedores en Costa Rica. Cubre:
OCR de matrículas y códigos BIC en el borde, apilado inteligente (stacking) con OR-Tools, predicción de congestión
en puertas/andenes, trazabilidad de flota en tiempo real y bitácora digital de entradas/salidas.

## Arquitectura (resumen)

Cámaras RTSP → **edge-ocr** (FastAPI + YOLOv8-obb + PaddleOCR, CPU) → eventos OCR por HTTP/MQTT →
**backend** (Spring Boot 3, Java 21, PostgreSQL 16, Flyway) → OR-Tools (stacking) + predicción de congestión +
WebSocket/STOMP (flota) → **frontend** (React 18 + Vite + TypeScript + Tailwind) vía nginx.
Mosquitto es el broker MQTT/telemetría. Todo se orquesta con Docker Compose (`infra/docker-compose.yml`).

## Estructura del repositorio

| Módulo | Contenido | Estado |
|---|---|---|
| `backend/` | Puertas, citas, bitácora, optimizador, congestión, flota (Java 21 / Spring Boot 3) | Código completo; compila y pruebas de unidad verdes |
| `edge-ocr/` | Servicio OCR de borde (Python / FastAPI) + dataset sintético + evaluador | Código completo |
| `frontend/` | React 18 + Vite + TS + Tailwind (esqueleto de pantallas P7) | Sin Node disponible en este entorno; no verificable |
| `infra/` | docker-compose.yml, mosquitto, prometheus, .env.example, Makefile | Compose válido (`docker compose config` OK) |
| `simulators/` | Generadores de telemetría y jornada (semilla fija) | Python requerido; no verificable aquí |
| `tests/e2e/` | Pruebas de extremo a extremo | Python requerido; no verificable aquí |
| `docs/api/openapi.yaml` | Contrato OpenAPI 3.1 | Válido |
| `docs/architecture/` | C4 Mermaid + ADRs | Completo |

## Requisitos

- Docker Desktop (con WSL 2 integration activada en Ubuntu) para levantar el stack completo.
- JDK 21 + Maven 3.9+ para compilar el backend fuera de Docker.
- Node.js + npm para `npm run lint/test/build` del frontend.
- Python 3.11 + pip para `pytest` de edge-ocr y simuladores.

> Nota: en el entorno de validación actual solo estaban disponibles Java y Maven. Node, Python y `make`
> no estaban instalados, por lo que frontend, edge-ocr y simuladores **no se pudieron ejecutar**
> (marcados como `NO MEDIDO` en el cierre de la sesión).

## Cómo ejecutar y verificar el funcionamiento

### Opción A: stack completo con Docker Compose

```bash
# 1. Copia las variables de entorno
cp infra/.env.example infra/.env

# 2. Levanta todo el stack (postgres, mosquitto, edge-ocr, backend, frontend)
make up

# 3. Verifica que los servicios estén sanos
make ps

# 4. Aplica migraciones y carga la semilla de demostración
make seed

# 5. Ejecuta la jornada simulada (demo de extremo a extremo)
make demo
```

Servicios expuestos (puertos por defecto):
- Backend API: `http://localhost:8080` (Swagger UI en `/swagger-ui.html`)
- Edge OCR: `http://localhost:8001` (`GET /healthz`, `POST /v1/recognize`)
- Frontend: `http://localhost:8088`
- MQTT: `localhost:1883` (WebSocket `9001`)
- PostgreSQL: `localhost:5432`

```bash
make logs      # ver logs
make down      # parar el stack (conserva volúmenes)
make clean     # parar y borrar volúmenes
```

### Opción B: solo el backend (sin Docker)

Requiere `JAVA_HOME` apuntando a JDK 21 y Maven en el PATH.

```bash
cd backend
./mvnw verify        # compila, ejecuta pruebas y genera reporte JaCoCo
```

Criterio de aceptación del backend: `./mvnw verify` verde y cobertura JaCoCo ≥ 70 % de líneas en los paquetes
`cr.logisticaportuaria.gate.*`.

### Opción C: edge-ocr (requiere Python y pesos de modelos)

```bash
cd edge-ocr
python -m pip install -r requirements.txt
make edge-dataset    # genera 240 imágenes sintéticas (semilla 20260222)
make edge-eval MODE=corrupt   # evalúa post-proceso sobre el dataset sintético
```

### Usuarios demo (perfil `dev`, solo entornos de desarrollo)

| Usuario | Contraseña | Rol |
|---|---|---|
| `operador` | `operador` | OPERADOR_PUERTA |
| `supervisor` | `supervisor` | SUPERVISOR |
| `admin` | `admin` | ADMIN |
| `auditor` | `auditor` | AUDITOR |

Nunca usar estos usuarios fuera del perfil `dev`.

## Verificación esperada (criterios de aceptación)

- `docker compose config` → sin errores (infra válida).
- `./mvnw verify` (backend) → BUILD SUCCESS; JaCoCo ≥ 70 % líneas en `gate`.
- Check digit ISO 6346: `CSQU3054383` debe validar como correcto.
- OCR: ≥ 200 imágenes sintéticas evaluadas con métricas reales.
- Stacking: heurística p95 < 200 ms con 5 000 slots; mejora real de restows vs. FIFO.
- Congestión: MAE del híbrido por debajo de la línea base ingenua.
- Flota: carga 50 vehículos × 1 msg/s × 5 min sin mensajes perdidos.

En el entorno de validación de esta sesión solo se pudo verificar el backend (compilación y pruebas de unidad).
El resto quedó como `NO MEDIDO` por falta de Node, Python y Docker en ejecución (ver `MEMORY.md` §9).

## Licencia y privacidad

100 % software libre. La matrícula es dato personal (Ley 8968, Costa Rica): retención de evidencia con TTL
configurable, desenfoque de rostros configurable y logs sin datos personales innecesarios. Detalle de licencias
en `docs/architecture/adr/ADR-005-*.md`.
