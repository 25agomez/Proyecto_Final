# AGENTS.md — Sistema Inteligente de Gestión de Patios Logísticos (LogisticaPortuaria CR)

> Fuente de verdad de **cómo debe trabajar un agente en este repo**. Lee este archivo y `MEMORY.md` completos antes de escribir código.
> `PROMPTS.md` contiene los 8 prompts (P1–P8) con objetivos, entregables y criterios de aceptación: consúltalo, no lo repitas aquí.

**Objetivo del producto:** OCR de matrículas y códigos BIC en el borde, stacking de contenedores por ETD, predicción de congestión en puertas/andenes, trazabilidad de flota en tiempo real y bitácora digital de entradas/salidas. Todo local (Linux + Docker), 100 % open source, sin nube extranjera.

---

## 1. Estado real del repo y reglas de sesión

- **Repo greenfield**: hoy solo existen `AGENTS.md`, `MEMORY.md` y `PROMPTS.md`. No hay código, manifiestos, CI ni infra. No asumas que un módulo "ya está": créalo desde cero.
- **La raíz del repo es la raíz del árbol de §3.** No crees una subcarpeta `logisticaportuaria-cr/`.
- **No es repositorio git.** Antes del primer commit: `git init` + `git config user.name` / `user.email` (no hay identidad global configurada; `sudo` exige password interactiva, así que no automatices nada que la pida).
- **Los prompts se ejecutan en orden P1→P8** (tabla de dependencias en PROMPTS.md): P2 consume el modelo de datos de P1; P3–P6 los contratos OpenAPI/MQTT de P1 y el evento de lectura de P2; P7 consume P3–P6; P8 consolida todo.
- **Bloque "CIERRE OBLIGATORIO"** (al final de cada prompt en PROMPTS.md), obligatorio: compilar/lint/pruebas → crear `docs/experiments/EXP-NNN-<slug>.md` (§7) → actualizar `MEMORY.md` (§8) → resumen de ≤15 líneas. Si no mediste algo: **"NO MEDIDO"** + motivo.
- Cuando ejecutes un prompt, adopta el rol que indica (arquitecto · visión · backend · optimizador · frontend · QA/docs) y registra en `MEMORY.md` §6 el agente y el prompt usados.
- Plan breve de 5–10 líneas antes de codificar; después ejecuta sin pedir confirmación salvo bloqueo real.

---

## 2. Entorno de ejecución (WSL2 · Ubuntu 26.04) — verificado

| Herramienta | Estado real | Acción |
|---|---|---|
| Docker | **No usable**: el shim de Docker Desktop en el PATH responde *"could not be found in this WSL 2 distro"* y el distro `docker-desktop` está **Stopped** | El usuario debe abrir Docker Desktop en Windows y activar *Settings → Resources → WSL Integration → Ubuntu*. Mientras tanto los criterios "docker compose up" **no son verificables**: valida los compose por revisión estática y deja el bloqueo en `MEMORY.md` §9 |
| JDK 21 | No hay JDK en Linux. El `mvn` del PATH es el de **Windows** (`/mnt/c/Program Files/Apache/maven/...`) → **no lo uses** | Descarga Temurin 21 en `~/.local/jdk21` (curl a `api.adoptium.net` funciona) y exporta `JAVA_HOME`. Windows tiene JDK 25/27 (`/mnt/c/Program Files/Eclipse Adoptium`): no sirven para un build Linux |
| Node / npm | No instalados | Instálalos con nvm en `~/.local` (hay red). Sin esto no corren `npm run lint/test/build` |
| make | No instalado | `make up/demo/smoke` no se puede ejecutar aquí: valida los targets leyendo el Makefile |
| Python | 3.14.4 **sin `ensurepip`** → `python3 -m venv` falla | Usa `python3 -m venv --without-pip` + `get-pip.py`, o `virtualenv`. El proyecto apunta a 3.11 (P2): decláralo en `pyproject.toml` y anota la diferencia |
| sudo | Pide password interactiva | Ningún script o Dockerfile debe requerir sudo |
| Red | `pypi.org`, `registry.npmjs.org` y `repo1.maven.org` responden 200 | Las descargas de dependencias funcionan |

**Rutas:** el repo vive en `/mnt/d/PROGRA UC/...`: montaje 9p de Windows **y con espacios**. Cita siempre las rutas entre comillas. Si Docker no logra montar el repo, usa volúmenes con rutas internas al contenedor en vez de bind mounts del repo.

---

## 3. Estructura de repositorio

```
(raíz del repo = esta carpeta)
├── AGENTS.md · MEMORY.md · PROMPTS.md
├── Makefile                # targets up/down/logs/test/seed (P1) + demo/smoke (P8) — en la RAÍZ para que `make demo` funcione
├── README.md               # P8
├── docs/
│   ├── architecture/       # C4 en Mermaid + adr/
│   ├── api/openapi.yaml    # contrato OpenAPI 3.1 (propiedad de architect)
│   └── experiments/        # EXP-NNN-<slug>.md, uno por prompt ejecutado
├── edge-ocr/               # FastAPI + YOLOv8-obb + PaddleOCR (Python)
├── backend/                # Spring Boot 3 · Java 21
│   └── src/main/java/cr/logisticaportuaria/{gate,yard,optimizer,congestion,fleet,shared}/
├── frontend/               # React 18 + Vite + TS strict + Tailwind
├── simulators/             # generadores de telemetría/jornada (semilla fija)
├── infra/                  # docker-compose.yml, mosquitto, nginx, scripts, .env.example
└── tests/e2e/
```

Cada módulo lleva **README propio no vacío** (P1). Stack por capa: Python/FastAPI (edge-ocr) · Java 21 + Spring Boot 3 + Flyway (backend) · React 18 + Vite + TS + Tailwind (frontend) · PostgreSQL 16 (PostGIS y pg_trgm permitidos) · **OR-Tools oficial `com.google.ortools`** (CP-SAT, dentro del backend) · Mosquitto MQTT + WebSocket/STOMP · Docker Compose.

---

## 4. Reglas no negociables

1. **100 % open source.** Nada de SDKs propietarios ni APIs de nube de pago (Google Vision, AWS Rekognition, Azure). Si dudas de una licencia, regístrala en `MEMORY.md` §3.
2. **Idioma:** código y comentarios técnicos en **inglés**; documentación, UI, mensajes de usuario y explicaciones del optimizador en **español (es-CR)**.
3. **Métricas honestas:** nunca inventes cifras ni "promesas". Sin medición → **"NO MEDIDO"** + motivo.
4. **Datos sintéticos y reproducibles:** simuladores con **semilla fija**; nunca datos reales de clientes.
5. **Sin secretos:** variables de entorno y `.env.example`; ninguna credencial en el repo.
6. **Privacidad:** la matrícula es dato personal (Ley 8968, Costa Rica) → retención configurable, blur de rostros configurable en la evidencia, logs sin datos personales innecesarios.
7. **Datos:** BD en UTC (`timestamptz`), la UI muestra `America/Costa_Rica`; UUID v7; BIC normalizado (mayúsculas, sin espacios) validado por ISO 6346; matrícula CR normalizada sin guiones **conservando la lectura cruda** en campo aparte.
8. **SQL:** `snake_case`, FKs indexadas, migraciones Flyway **inmutables** (jamás edites una `V<n>__` ya aplicada), CHECK de BIC `^[A-Z]{3}[UJZ][0-9]{7}$`.
9. **Java:** records para DTOs, `@Validated`, `ProblemDetail` (RFC 7807), sin lógica de negocio en controladores, capas `api/application/domain/infrastructure`.
10. **Python:** Python 3.11, FastAPI, pydantic v2, `ruff` + `black`, `pytest`.
11. **TypeScript:** `strict`, ESLint + Prettier, componentes funcionales.
12. **Commits:** `feat:`, `fix:`, `docs:`, `test:`, `chore:`.

**Glosario del dominio** (no es deducible del código): patio → bloques → bahías → filas → niveles = *slots*; capacidad en TEU; altura máx. de apilado 4 (llenos) / 5 (vacíos); *restow* = reubicación improductiva de un contenedor tapado; *cita* = `appointment`; ETD/ETA = salida/llegada estimada; reefer = contenedor con toma eléctrica; hazmat = zona segregada; montacargas/rastra = `vehicle`; bitácora = `visit_log`; 40' ocupa dos bahías contiguas.

---

## 5. Comandos y umbrales que se evalúan

| Módulo | Comandos (detalle en PROMPTS.md) |
|---|---|
| `backend/` | `./mvnw verify` (no uses el Maven de Windows), JaCoCo, Testcontainers para PostgreSQL/Mosquitto |
| `frontend/` | `npm run lint` · `npm run test` · `npm run build` |
| `edge-ocr/` | `pytest` + `ruff` + `black` |
| `simulators/`, `tests/e2e/` | `pytest tests/e2e` |

Umbrales numéricos contra los que se revisa cada entrega:

- **P2** — el check digit ISO 6346 debe validar `CSQU3054383`; ≥200 imágenes sintéticas con métricas reales; OCR p95 ≤ 800 ms/frame en CPU de 8 núcleos; corrección de 1 carácter → `corrected=true`.
- **P3** — JaCoCo ≥ 70 % en los paquetes `gate`; transición inválida de `visit_log` → HTTP 409 con `ProblemDetail`; idempotencia por `eventId`.
- **P4** — heurística p95 < 200 ms con 5 000 slots; **mejora medida vs. FIFO** (el valor que sea, sin inflar); respeta `timeLimitSeconds` y devuelve `status=FEASIBLE`.
- **P5** — MAE/RMSE/MAPE vs. línea base ingenua; ≥2 `drivers` interpretables por pronóstico; validación temporal sin fuga de datos.
- **P6** — telemetría→WebSocket p95 ≤ 2 s; conteo enviado vs. persistido (no perder mensajes).
- **P7** — bundle inicial < 250 kB gzip; ≥15 tests Vitest + 1 flujo Playwright; reporte de axe.
- **P8** — los 6 escenarios e2e; verificación de licencias 100 % open source.

---

## 6. Definición de "hecho" (DoD)

- [ ] Compila y pasa lint (comandos de §5).
- [ ] Pruebas automatizadas relevantes: unitarias y, si aplica, de integración con Testcontainers.
- [ ] Infraestructura lista y `docker compose up` sin pasos manuales ocultos — **o**, si Docker no está disponible (§2), revisión estática del compose + bloqueo documentado en `MEMORY.md` §9.
- [ ] Documentación breve (README del módulo o sección en `docs/`).
- [ ] `docs/experiments/EXP-NNN-<slug>.md` creado o actualizado (§7).
- [ ] `MEMORY.md` actualizado según §8.

---

## 7. Registro de experimentos (plantilla obligatoria)

Cada prompt ejecutado genera `docs/experiments/EXP-NNN-<slug>.md` con esta plantilla:

```markdown
# EXP-NNN — <título>

- **Fecha:** YYYY-MM-DD
- **Agente:** <nombre> | **Modelo/herramienta:** <p. ej. Claude Code, versión>
- **Prompt usado:** <ID del prompt, p. ej. P2> (versión del prompt: vX)
- **Objetivo:** ...

## Resultado
- Entregables generados (rutas):
- ¿Compila / corre a la primera? Sí/No (si No: qué falló)
- Nº de iteraciones/correcciones necesarias:
- Tiempo aproximado agente vs. estimación manual:

## Métricas (reales o "NO MEDIDO")
| Métrica | Valor | Cómo se midió |
|---|---|---|

## Calidad del código generado (1-5)
Corrección: _ | Legibilidad: _ | Seguridad: _ | Cobertura de requisitos: _

## Problemas y alucinaciones detectadas
- ...

## Ajustes sugeridos al prompt
- ...

## Conclusión
...
```

---

## 8. Protocolo de memoria (`MEMORY.md`)

`MEMORY.md` es la memoria persistente compartida entre sesiones y agentes (plantilla de 13 secciones ya existente).

**Al iniciar:** leerlo completo; respetar §3 "Decisiones" y §4 "Convenciones" vigentes.

**Durante:** anotar en el momento toda decisión técnica, restricción o error recurrente.

**Al terminar (obligatorio) — secciones exactas a tocar:**

| Sección de MEMORY.md | Qué escribir |
|---|---|
| §2 Estado de componentes | estado y % de avance por componente |
| §3 Decisiones (ADR resumidas) | ADR nuevo con ID incremental (`ADR-002`, `ADR-003`, …) |
| §4 Convenciones vigentes | convención que aplica a todo el repo |
| §5 Supuestos del dominio | supuesto que tomaste al faltar información |
| §6 Bitácora de sesiones | fecha, agente, prompt, qué se hizo, qué quedó pendiente |
| §7 Índice de experimentos | fila del EXP-NNN creado |
| §8 Métricas acumuladas | solo valores medidos; "NO MEDIDO" si no aplica |
| §9 Problemas abiertos | bloqueos (p. ej. Docker no disponible) con responsable |
| §11 Solicitudes entre agentes | cambios que necesitas de otro ámbito |
| §12 Próximos pasos | reordenados según avance real |

Reglas: **tacha con `~~texto~~` lo obsoleto y explica por qué** (nada de borrado silencioso). Máximo ~300 líneas útiles; si crece, resume lo antiguo en §13 "Archivo" conservando los IDs. Nunca secretos ni datos personales reales.

---

## 9. Ámbitos de propiedad, bloqueos y supuestos

| Agente | Dueño de |
|---|---|
| `architect` | `docs/architecture/`, `docs/api/openapi.yaml`, `infra/`, modelo de datos (migraciones Flyway), contratos MQTT |
| `vision-agent` | `edge-ocr/` |
| `backend-agent` | `backend/` salvo `optimizer/` y `congestion/` |
| `optimizer-agent` | `backend/.../optimizer/`, `backend/.../congestion/` |
| `frontend-agent` | `frontend/` |
| `qa-docs-agent` | `simulators/`, `tests/e2e/`, `docs/experiments/`, README raíz |

**Fronteras:** el esquema de BD, `docs/api/openapi.yaml`, los topics MQTT (`yard/{yardId}/fleet/{vehicleId}/telemetry`, `yard/{yardId}/gate/{gateId}/events`) y el `Makefile` raíz son de `architect`. Si tu módulo necesita cambiarlos, **no los edites**: anótalo en `MEMORY.md` §11 y sigue compatible con el contrato vigente.

**Ante bloqueos:**

- Falta información → asume lo más razonable, documenta el supuesto en `MEMORY.md` §5 y continúa.
- Requisitos contradictorios → gana la regla 100 % open source y la mayor seguridad; documéntalo.
- Una librería no funciona → prueba la alternativa open source equivalente y registra el motivo en un ADR (§3).
- Un comando o una métrica no ejecutable/medible en este entorno (§2) → **no lo inventes**: marcalo "NO MEDIDO" o como bloqueo en `MEMORY.md` §9, y sigue entregando el resto.
