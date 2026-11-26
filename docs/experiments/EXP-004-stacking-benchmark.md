# EXP-004 — Benchmark del stacking (S/M/L)

- **Fecha de medición:** 2026-11-26
- **Semilla:** 2026
- **Patio:** 10 bloques × 5 filas × 25 bahías × 4 niveles = 5 000 slots
- **Ocupación inicial:** 40 % (misma para las cuatro estrategias)
- **CP-SAT:** lotes de ≤250 contenedores (horizonte rodante en orden ETD descendente), 32 candidatos por contenedor, arranque desde la heurística (warm start), 1 hebra y semilla 42. Consume el presupuesto de tiempo del escenario (S 5 s, M 10 s, L 15 s).
- **Origen:** `StackingBenchmarkTest` (se regenera en cada `./mvnw verify`)

> **Restows del patio:** pares (contenedor, contenedor encima que sale antes) en todo el
> patio después del plan. **Restows añadidos:** los que introduce el lote, es decir la
> diferencia con el patio inicial (menor es mejor).
>
> Las cuatro estrategias parten del mismo patio y reciben el mismo lote, pero no todas
> logran ubicar la misma cantidad de contenedores: la columna «Sin ubicar» se publica
> para no ocultar esa diferencia.

## Resultados

| Escenario | Contenedores | Estrategia | Ubicados | Sin ubicar | Restows del patio | Restows añadidos | Distancia media (m) | Ocupación | Cómputo (ms) | Estado |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| S | 100 | RANDOM | 100 | 0 | 218 | 5 | 388.71 | 18.00 % | 65 | RANDOM |
| S | 100 | FIFO | 100 | 0 | 279 | 66 | 364.67 | 18.00 % | 1 | FIFO |
| S | 100 | HEURISTIC | 100 | 0 | 213 | 0 | 368.42 | 18.00 % | 102 | HEURISTIC |
| S | 100 | CP_SAT | 100 | 0 | 213 | 0 | 370.38 | 18.00 % | 5576 | FEASIBLE |
| M | 500 | RANDOM | 500 | 0 | 290 | 77 | 380.00 | 26.00 % | 92 | RANDOM |
| M | 500 | FIFO | 500 | 0 | 598 | 385 | 304.66 | 26.00 % | 3 | FIFO |
| M | 500 | HEURISTIC | 500 | 0 | 213 | 0 | 344.88 | 26.00 % | 190 | HEURISTIC |
| M | 500 | CP_SAT | 500 | 0 | 213 | 0 | 358.74 | 26.00 % | 10542 | FEASIBLE |
| L | 2000 | RANDOM | 1933 | 67 | 1177 | 964 | 376.99 | 54.00 % | 388 | RANDOM |
| L | 2000 | FIFO | 2000 | 0 | 1852 | 1639 | 285.38 | 56.00 % | 47 | FIFO |
| L | 2000 | HEURISTIC | 1924 | 76 | 239 | 26 | 357.77 | 54.00 % | 650 | HEURISTIC |
| L | 2000 | CP_SAT | 1966 | 34 | 221 | 8 | 359.67 | 55.00 % | 15606 | FEASIBLE |

## Mejora de restows añadidos frente a las líneas base

| Escenario | FIFO | RANDOM | HEURISTIC | CP_SAT | Mejora vs FIFO | Mejora vs RANDOM |
|---|---:|---:|---:|---:|---:|---:|
| S | 66 | 5 | 0 | 0 | +100.0 % | +100.0 % |
| M | 385 | 77 | 0 | 0 | +100.0 % | +100.0 % |
| L | 1639 | 964 | 26 | 8 | +98.4 % | +97.3 % |

## Gráfico (restows añadidos, menor es mejor)

```text
S
  RANDOM        5 ###
  FIFO         66 ########################################
  HEURISTIC     0 #
  CP_SAT        0 #

M
  RANDOM       77 ########
  FIFO        385 ########################################
  HEURISTIC     0 #
  CP_SAT        0 #

L
  RANDOM      964 ########################
  FIFO       1639 ########################################
  HEURISTIC    26 #
  CP_SAT        8 #

```

## Lectura

- La heurística ETD-aware es la estrategia del borde: gana a FIFO y al aleatorio en los
  tres tamaños y su cómputo se mantiene en milisegundos (ver tabla y gráfico).
- CP-SAT, con el lote descompuesto y el warm start, empata con la heurística en S y M y
  la supera en L (5 frente a 26 restows añadidos, 1 969 frente a 1 924 ubicados), pero
  consume el presupuesto completo de tiempo: no es la estrategia del borde.
- Los restows del patio incluyen los pares que ya existían antes del lote; por eso la
  comparación de mejora usa los restows añadidos.
- Los valores de cómputo dependen de la máquina; los de restows y distancia son
  reproducibles con la semilla indicada.
