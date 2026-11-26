# EXP-004 — Optimizador de stacking ETD-aware y CP-SAT

- **Fecha:** 2026-10-22
- **Agente:** optimizer-agent | **Modelo/herramienta:** Big Pickle (OpenCode)
- **Prompt usado:** P4 (v1, ver `PROMPTS.md`)
- **Objetivo:** decidir en qué slot del patio va cada contenedor minimizando restows futuros,
  distancia y dispersión; con una heurística apta para el borde (p95 < 200 ms con 5 000 slots),
  un modelo CP-SAT de OR-Tools con presupuesto de tiempo, endpoint Top-3 con explicación en
  español, plan asíncrono por lotes y una mejora **medida** frente a FIFO y al azar.

## Resultado

- **Entregables generados:**
  - `backend/src/main/java/cr/logisticaportuaria/optimizer/`
    - `domain/`: `SlotNode`, `ContainerUnit`, `YardSnapshot`, `DistanceModel`, `PlacementRules`,
      `StackingWeights`, `SlotScorer`, `Recommendation`, `Move`, `PlacementPlan`, `YardMetrics`,
      `HeuristicPlanner`, `BaselinePlanner` (FIFO/RANDOM), `CpSatPlanner`, `ScenarioGenerator`.
    - `application/`: `OptimizerProperties`, `YardSnapshotLoader`, `OptimizerService`, `PlanService`.
    - `api/`: `OptimizerController` + DTOs (`OptimizerRequest/Response`, `RecommendationDto`,
      `SlotDto`, `PlanRequest`, `PlanStrategy`, `OptimizerPlanDto`, `MoveDto`, `PlanMetricsDto`,
      `YardMetricsResponse`).
    - `infrastructure/`: repositorio de solo lectura para cargar el patio.
  - `backend/src/test/java/cr/logisticaportuaria/optimizer/`: `HeuristicPlannerTest` (7),
    `CpSatPlannerTest` (5), `StackingPropertyTest` (2 métodos, 200 escenarios), `HeuristicPerformanceTest` (1),
    `OptimizerIntegrationTest` (8), `benchmark/StackingBenchmarkTest` (1) — **24 pruebas del módulo**.
  - `docs/architecture/optimizer-model.md` (modelo formal, descomposiciones y límites).
  - `docs/experiments/EXP-004-stacking-benchmark.{csv,md}` (generados por la prueba de benchmark).
  - `backend/src/main/resources/application.yml` (`app.optimizer.*`) y `backend/README.md`
    (sección del optimizador y endpoints).
  - `docs/api/openapi.yaml`: **no modificado** (es del architect). `OpenApiContractTest` solo difiere
    `/congestion` y `/fleet`; `/optimizer/*` ya se validan contra el contrato vigente.
- **¿Compila / corre a la primera?** No. Fallaron, en este orden: (1) `PlacementRules.acceptsType`
  aceptaba un hazmat con toma eléctrica en un slot `hazmat` sin energía; (2) `ContainerUnit` exigía
  `imoClass` para hazmat y el contrato no lo envía; (3) CP-SAT devolvía `UNKNOWN` con lotes grandes
  (diagnóstico abajo); (4) una prueba esperaba `INFEASIBLE` cuando el modelo pasó a ser siempre
  factible por la variable `skip`. Las cuatro se corrigieron antes de cerrar.
- **Nº de iteraciones/correcciones necesarias:** 4 correcciones de código + 3 corridas de benchmark
  para estabilizar CP-SAT (poda con diversidad, `skip`, warm start y chunks ETD).
- **Tiempo aproximado agente vs. estimación manual:** NO MEDIDO (no hay estimación manual registrada).

## Métricas (reales o "NO MEDIDO")

| Métrica | Valor | Cómo se midió |
|---|---|---|
| p50 / p95 / p99 de `POST /optimizer/recommendations` con 5 000 slots | **0 / 2 / 3 ms** (n = 200) | `HeuristicPerformanceTest`, patio 10×5×25×4, `System.nanoTime()`; falla si p95 > 200 ms |
| Mejora de restows añadidos vs. FIFO (escenario S, 100 contenedores) | **100 %** (66 → 0) | `StackingBenchmarkTest`, semilla 2026 |
| Mejora de restows añadidos vs. FIFO (escenario M, 500 contenedores) | **100 %** (385 → 0) | idem |
| Mejora de restows añadidos vs. FIFO (escenario L, 2 000 contenedores) | **98,4 %** (1 639 → 26; CP-SAT 5) | idem |
| Restows añadidos vs. azar (S / M / L) | 5 → 0 / 77 → 0 / 964 → 26 | idem |
| Cómputo de la heurística (S / M / L) | 112 / 313 / 782 ms | `StackingBenchmarkTest`, lote completo desde el patio de 5 000 slots |
| Cómputo de CP-SAT (S / M / L) | 5 872 / 10 706 / 15 829 ms (agota el presupuesto: 5 / 10 / 15 s) | idem |
| CP-SAT respeta `timeLimitSeconds` y devuelve estado | `FEASIBLE` en S, M y L; `OPTIMAL` en los casos pequeños de `CpSatPlannerTest` | `CpSatPlannerTest.respectsTheTimeLimit` + benchmark |
| Escenarios de propiedad (reglas físicas movimiento a movimiento) | **200** + determinismo | `StackingPropertyTest` |
| Casos manuales | pila simple, 40' contiguo, reefer, hazmat, peso y restow | `HeuristicPlannerTest` (7) y `CpSatPlannerTest` (5) |
| Cobertura del paquete `optimizer` | **90,05 % de líneas** (824/915); `domain` 88,91 % de líneas y 80,24 % de ramas; `api` 100 % | JaCoCo de `./mvnw verify` (`target/site/jacoco/jacoco.csv`) |
| Suite completa del backend tras P4 | `BUILD SUCCESS`, **88 pruebas** en 15 clases, cobertura `gate` 83,14 % (umbral 70 %) | `./mvnw verify`, 2:51 min |
| Latencia OCR, congestión y flota | NO MEDIDO | fuera del alcance de P4 |

Lectura honesta del benchmark: en S y M la heurística **no introduce** ningún restow (los que
quedan son los 213 que ya tenía el patio); en L ubica 1 924 de 2 000 contenedores y añade 26
restows, mientras FIFO ubica los 2 000 pero añade 1 639. CP-SAT, con el lote descompuesto, ubica
1 969 y añade 5, pero **gasta todo el presupuesto de tiempo**: por eso no es la estrategia del
borde. Ver la tabla completa y el gráfico en `EXP-004-stacking-benchmark.md`.

## Calidad del código generado (1-5)

Corrección: 4 | Legibilidad: 4 | Seguridad: 4 | Cobertura de requisitos: 5

- La corrección no es 5 por los tres fallos iniciales de CP-SAT (infactibilidad por poda de
  candidatos) y el de `PlacementRules`, detectados solo al ejecutar.
- Seguridad 4: los endpoints exigen JWT y roles (`OPERADOR_PUERTA`/`SUPERVISOR`/`ADMIN` para
  `POST`, más `AUDITOR` para lectura); el plan se guarda en memoria (200 planes) y no hay
  ejecución de código desde la entrada.

## Problemas y alucinaciones detectadas

1. **Poda de candidatos que rompía la factibilidad.** Conservar solo los 32 slots más baratos por
   contenedor hacía que todos propusieran los mismos slots: con 500 contenedores la unión de
   candidatos era menor que el lote y CP-SAT gastaba el límite de tiempo devolviendo `UNKNOWN`
   (medido con una prueba de diagnóstico temporal, ya eliminada). Solución: mitad más barata +
   mitad repartida por ranking, y variable `skip` para que el modelo siempre tenga solución.
2. **`UNKNOWN` con 2 000 contenedores.** Ni con `skip` el solver encontraba la primera solución en
   15 s (64 000 booleanos). Solución medida: horizonte rodante de 250 contenedores en orden ETD
   descendente + warm start desde la heurística → `FEASIBLE`, 1 969 ubicados, 5 restows añadidos.
3. **Restows peores que la heurística antes del orden ETD.** Sin ordenar los chunks por ETD, CP-SAT
   creaba 229 restows añadidos (frente a 26 de la heurística) porque el coste es estático y no ve
   la interferencia entre contenedores del mismo lote. Documentado como límite del modelo.
4. **Supuesto no verificado al inicio:** el esquema de P1 no guarda distancia por slot; se derivó
   una fórmula geométrica sintética (documentada en §2 de `optimizer-model.md`) en lugar de
   inventar una columna o pedir una migración.
5. **Confusión de alcance:** `GET /optimizer/plans/{id}` y `GET /optimizer/metrics` no están en el
   contrato de P1; se implementaron como extensión y se registraron en `MEMORY.md` §11.
6. **Ruido de pruebas:** tras las pruebas con contexto Spring aparece
   `Surefire is going to kill self fork JVM... after System.exit(0)` (≈30 s extra, no rompe el
   build). Se registra en `MEMORY.md` §9.

## Ajustes sugeridos al prompt

- Pedir explícitamente que el modelo CP-SAT sea **siempre factible** (variable de no ubicado) y que
  el lote se descomponga: sin eso, cualquier tamaño realista termina en `UNKNOWN`.
- Fijar en el prompt la fuente de la distancia al patio: hoy no existe en el esquema y obliga a un
  supuesto geométrico.
- Aclarar si "mejora vs. FIFO" se mide sobre restows del patio o sobre restows **añadidos** por el
  lote; la comparación solo es justa con los añadidos cuando las estrategias ubican cantidades
  distintas.
- Declarar de antemano que las métricas de cómputo dependen de la máquina y que CP-SAT agota su
  presupuesto de tiempo por diseño.

## Conclusión

P4 queda cerrada con las dos estrategias funcionando sobre el modelo de datos de P1: la heurística
ETD-aware responde en milisegundos (p95 2 ms con 5 000 slots) y reduce los restows añadidos frente
a FIFO y al azar en los tres tamaños medidos; CP-SAT mejora el resultado en el lote grande dentro
de su presupuesto, a costa de segundos de cómputo. Las tres descomposiciones que lo hacen viable
(poda con diversidad, warm start y horizonte rodante) están documentadas en
`docs/architecture/optimizer-model.md` y sus efectos se publican en el benchmark reproducible.
