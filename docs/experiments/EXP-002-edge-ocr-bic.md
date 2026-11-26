# EXP-002 — Servicio Edge OCR: matriculas, codigos BIC y post-procesamiento ISO 6346

- **Fecha:** 2026-10-22
- **Agente:** vision-agent | **Modelo/herramienta:** OpenCode / big-pickle (agente autonomo con shell + edicion de archivos)
- **Prompt usado:** P2 — "Servicio Edge OCR: matriculas y codigos BIC" (version del prompt: v1, el texto de `PROMPTS.md` sin modificar)
- **Objetivo:** Construir en `edge-ocr/` un microservicio Python (FastAPI) que detecte matriculas y codigos BIC
  en el borde (CPU), normalice y valide el digito de control ISO 6346, corrija un caracter cuando sea posible,
  deduplique y publique eventos por MQTT y HTTP sin perderlos, con evaluacion reproducible sobre un dataset
  sintetico de >= 200 imagenes.

## Resultado

- **Entregables generados (rutas):**
  - Servicio: `edge-ocr/app/{main,config,models}.py`, `edge-ocr/app/api/{routes,streams}.py`,
    `edge-ocr/app/pipeline/{detector,ocr,preprocess,normalize,iso6346,postprocess,consensus,service}.py`,
    `edge-ocr/app/infra/{outbox,publisher,evidence,metrics}.py`.
  - Datos y evaluacion: `edge-ocr/tools/make_synthetic_dataset.py`, `edge-ocr/tools/evaluate.py`.
  - Entrenamiento: `edge-ocr/train.py`, `edge-ocr/datasets/data.yaml`, `edge-ocr/datasets/README.md`.
  - Configuracion: `edge-ocr/pyproject.toml`, `edge-ocr/requirements-models-gpu.txt`, `edge-ocr/Dockerfile`.
  - Pruebas: `edge-ocr/tests/` (9 modulos de prueba, 94 pruebas).
  - Informes: `docs/experiments/EXP-002-ocr-evaluation.{json,md}`,
    `docs/experiments/EXP-002-ocr-preprocess.{json,md}`,
    `docs/experiments/EXP-002-ocr-evaluation-live.{json,md}`.
  - Documentacion: `docs/architecture/adr/ADR-005-licencia-ultralytics-agpl.md`, `edge-ocr/README.md`.
  - Correcciones de infraestructura detectadas en P2: `infra/docker-compose.yml`, `infra/.env.example`.
- **¿Compila / corre a la primera?** No. La primera corrida de pytest dejo **78 aprobadas y 3 fallidas**
  (codigo y pruebas ya escritos en la sesion anterior a esta). Los fallos no eran de sintaxis sino de
  semantica; se corrigieron y las corridas siguientes quedaron en verde. La suite final: **94 passed**.
  `ruff check .` y `black --check` quedan limpios (35 archivos revisados, 24 modulos de codigo en `app/` y `tools/`).
- **Nº de iteraciones/correcciones necesarias:** 8 corridas de pytest en esta sesion (6 en verde,
  2 con fallas) y 7 correcciones sustantivas:
  1. `outbox`: el indice `idx_outbox_pending` referenciaba una columna inexistente (`sent`) -> la cola
     durable fallaba en cada `enqueue`.
  2. `service`: la revalidacion del texto del consenso **sobrescribia** el flag `corrected` con `False`,
     perdiendo la correccion de un caracter (requisito explicito del prompt).
  3. `publisher`: `last_error` se pisaba con el valor viejo del item al reprogramar el reintento.
  4. `outbox`/`publisher`: `attempts` se incrementaba por canal en vez de por ronda de entrega; se
     separo el conteo de intentos del marcado de canal.
  5. `main.py`: `create_app()` tocaba el disco en el import (creaba `/app/data`); ahora los colaboradores
     se crean en el evento *startup* (lifespan) y el import no tiene efectos secundarios.
  6. `iso6346`: el algoritmo de reparacion original solo buscaba confusiones **entre clases** y fallaba
     justamente en el caso mas frecuente (digito de control mal leido). Se rediseño: la tabla de
     confusiones ahora cubre letra/letra, digito/digito y letra/digito, y los candidatos se filtran por la
     clase exigida en cada posicion (4 letras del propietario, 7 digitos del serial y del control).
  7. `normalize`: el mapa de "digitos que parecen letras" se construia por inversion de otro diccionario y
     resolia `0 -> D` en vez de `0 -> O`; se explicito.
- **Tiempo aproximado agente vs. estimacion manual:** tiempo de sesion del agente ~2 h 30 min (estimado a
  partir del numero de iteraciones y del volumen de codigo revisado; **no medido con reloj**). Estimacion
  manual de un equipo: 12-16 h (servicio, cola durable, dataset, evaluacion, reentrenamiento documentado).

## Metricas (reales o "NO MEDIDO")

Dataset sintetico: 240 imagenes de 1280x720 (120 codigos de contenedor + 120 matriculas), semilla fija
`20260222`, ground truth en `edge-ocr/data/synthetic/ground_truth.csv`. CPU de medicion: AMD Ryzen 5 5600G,
12 nucleos, 13 GB RAM, Python 3.14.4 en el venv de pruebas (el proyecto declara 3.11 en `pyproject.toml`).

| Metrica | Valor | Como se midio |
|---|---|---|
| Imagenes sinteticas generadas | 240 (120 contenedores + 120 placas) | `tools/make_synthetic_dataset.py --count 240`; reproducibilidad verificada por hash SHA-256 en `tests/test_tools.py` |
| Exactitud por codigo completo (antes del post-proceso) | 60.00 % | Modo `corrupt` de `tools/evaluate.py`: la lectura del OCR se simula con un modelo de error determinista |
| Exactitud por codigo completo (despues del post-proceso) | 74.58 % | Idem, sobre la salida de `normalize` + validacion/reparacion ISO 6346 |
| CER (antes / despues del post-proceso) | 0.0472 / 0.0347 | Distancia de edicion (Levenshtein) sobre el total de caracteres de la verdad |
| Lecturas con digito de control valido | 95.83 % (115/120) | `isoValid == true` tras la validacion ISO 6346 |
| Lecturas corregidas (`corrected=true`) | 16.67 % (20/120) | Reparacion de 1 caracter dentro de la tabla de confusiones |
| Lecturas no reparables (a revision manual) | 4.17 % (5/120) | `isoValid == false` -> cola de revision del backend |
| Latencia p50 / p95 del post-proceso | 0.003 ms / 0.018 ms | `time.perf_counter` alrededor de `normalise_reading` + `validate_reading` (240 muestras) |
| Latencia p50 / p95 de recorte + preprocesado | 7.093 ms / 11.456 ms | Modo `preprocess`: `crop_oriented` + CLAHE + nitidez + binarizacion adaptativa + 4 rotaciones, sobre las 240 imagenes reales |
| Latencia p95 por frame completa (objetivo <= 800 ms) | **NO MEDIDO** | Requiere los pesos de YOLOv8-obb y PaddleOCR: la deteccion y el reconocimiento no se pueden ejecutar en este entorno (§9 B-001/B-007) |
| Exactitud del OCR neuronal (motivo `live`) | **NO MEDIDO** | `tools/evaluate.py --mode live` responde "NO MEDIDO" y sale con codigo 2 mientras no haya runtime de modelos (`docs/experiments/EXP-002-ocr-evaluation-live.json`) |
| Integracion "contenedor + imagen sintetica -> evento en MQTT" | **NO MEDIDO** | Requiere Docker (B-001) y un broker Mosquitto; la entrega MQTT si esta implementada y probada con dobles de prueba (`tests/test_publisher.py`) |
| Ejecucion de `docker compose up edge-ocr` | **NO MEDIDO** | Sin Docker en este WSL (B-001) |

## Calidad del codigo generado (1-5)

Correccion: **4** | Legibilidad: **4** | Seguridad: **4** | Cobertura de requisitos: **4**

- Los 7 fallos encontrados durante las corridas de pruebas eran fallos reales de logica (no de estilo) y
  todos quedaron cubiertos por una prueba de regresion.
- Debilidades asumidas: el detector y el OCR solo pudieron probarse con dobles; el modo `live` del
  evaluador es el camino que falta por ejecutar cuando haya pesos.
- La reparacion ISO 6346 puede devolver **otro** codigo valido distinto del original (el digito de control
  es mod 10: varias sustituciones producen un codigo valido). Es el comportamiento estandar del digito de
  control y esta documentado en `tests/test_iso6346.py` y en el codigo.

## Problemas y alucinaciones detectadas

- **Bug de esquema en la cola durable:** el indice `idx_outbox_pending` apuntaba a una columna que no
  existia (`sent`); SQLite no valida indices al crearlos, por lo que el fallo aparecia en tiempo de
  ejecucion y no en el arranque. Detectado por las pruebas del outbox.
- **Perdida del flag `corrected`:** la segunda validacion (consenso temporal) sobrescribia el resultado de
  la primera y devolvia `corrected=false` en lecturas que si habian sido reparadas. Violaba un criterio
  explicito del prompt ("correccion de 1 caracter -> `corrected=true`").
- **Reparacion ISO 6346 mal planteada al inicio:** la primera version solo buscaba confusiones
  letra/digito, por lo que no podia reparar el caso mas comun (el digito de control leido como otro
  digito). Se rediseno el algoritmo y se amplio la tabla de confusiones.
- **Mapa de confusion invertido mal:** `0` se normalizaba a `D` (por invertir un diccionario con valores
  repetidos) en vez de a `O`.
- **Efectos secundarios en el import:** `create_app()` creaba `/app/data` al importar el modulo, lo que
  rompia cualquier import fuera del contenedor (incluidas las pruebas). Corregido con el patron *lifespan*.
- **Infraestructura de P1 con dos errores reales** (detectados al revisar la coherencia con P2):
  1. `MQTT_HOST: mqtt` en `edge-ocr` y `backend`, pero el servicio del broker se llama `mosquitto`:
     el hostname `mqtt` no resolvia en la red de compose.
  2. El contenedor del edge no creaba `/app/data` (destino por defecto del outbox) ni montaba un volumen
     para el: el servicio no habria podido arrancar y los eventos no habrian sobrevivido a un reinicio.
     Corregidos en `infra/docker-compose.yml`, `infra/.env.example` y `edge-ocr/Dockerfile`.
  3. `VARIANT=gpu` estaba declarado en el Dockerfile pero no hacia nada: ahora instala el runtime pesado
     (`requirements-models-gpu.txt`).
- **Licencia no registrada:** Ultralytics es **AGPL-3.0** (copyleft fuerte). Es software libre, pero
  obliga a revisar el uso comercial. Registrado en `ADR-005` y en la tabla de licencias del modulo.
- **Alucinaciones evitadas:** no se reporto ninguna metrica del OCR neuronal; el modo `live` responde
  "NO MEDIDO" con el motivo y codigo de salida 2 en vez de inventar numeros.

## Ajustes sugeridos al prompt

- P2 pide "YOLOv8-obb ... Si no hay pesos propios, usa un modelo preentrenado ligero": conviene anadir que
  **el servicio no debe descargar pesos en tiempo de ejecucion** y que el evaluador debe distinguir entre
  "modo con modelos" y "modo sin modelos", con la obligacion de responder NO MEDIDO en el segundo caso.
- P2 pide "integracion: contenedor del servicio + imagen sintetica -> evento en MQTT (Testcontainers o
  docker compose de test)": en un entorno sin Docker el criterio no es verificable; el prompt deberia
  admitir una alternativa (prueba de contrato con broker en proceso, p. ej. `amqtt`/`hbmqtt`, o prueba de
  integracion marcada como opcional si el entorno no tiene Docker).
- Conviene separar en el prompt las **metricas de la capa neuronal** (exactitud, latencia por frame) de las
  **metricas de la capa de post-procesamiento** (normalizacion, ISO 6346, consenso), porque se miden con
  herramientas y datasets distintos.
- P2 no menciona la licencia de las dependencias: agregar "registra las licencias de las librerias con
  copyleft (Ultralytics AGPL-3.0) en un ADR".

## Conclusion

El servicio de borde queda implementado y probado en su parte determinista: deteccion/OCR con interfaz
inyectable (listos para los pesos), normalizacion por posicion, validacion y reparacion ISO 6346, consenso
temporal con votacion por caracter, deduplicacion en 30 s, cola durable en SQLite con reintentos
exponenciales por canal (MQTT y HTTP) y privacidad (blur de rostros + TTL de evidencia). La evaluacion
sobre 240 imagenes sinteticas con semilla fija demuestra que el post-procesamiento sube la exactitud por
codigo completo de 60.00 % a 74.58 %, deja el 95.83 % de las lecturas de contenedor con digito de control
valido y marca como corregidas el 16.67 %. Lo que **no** puede medirse en este entorno (motor neuronal,
`docker compose`, MQTT en contenedor) queda explícitamente como NO MEDIDO con su motivo, y se ejecutara
cuando el usuario habilite Docker y se monten los pesos.
