# ADR-003 — Optimizador de stacking: CP-SAT con heuristica de respaldo

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** architect (P1)

## Contexto

Asignar contenedores a slots minimizando *restows* (contenedores tapados que hay que mover para sacar otro)
es un problema combinatorio NP-difícil. En la puerta se necesita una respuesta en milisegundos; en la
planificacion nocturna se dispose de minutos.

## Decision

Dos estrategias intercambiables dentro del mismo modulo (`backend/.../optimizer/`):

1. **Heuristica ETD-aware** — decision inmediata en puerta. Ordena slots candidatos por costo
   `f(ETD relativo de la pila, altura, distancia a puerta, compatibilidad de tipo)` y devuelve Top-3 con
   explicacion en espanol. Objetivo: p95 < 200 ms con patio de 5 000 slots.
2. **Modelo CP-SAT (OR-Tools `com.google.ortools`)** — planificacion por lotes, con `timeLimitSeconds` y
   solucion de respaldo (`FEASIBLE`) si no cierra el tiempo.

Ambas se validan contra una **linea base FIFO / asignacion aleatoria** para cuantificar la mejora real.

## Consecuencias

- **Positivas:** el operador nunca espera por un solver; la planificacion nocturna puede maximizar el
  resultado; el benchmark con linea base hace auditable la mejora.
- **Negativas:** dos implementaciones que mantener y que pueden divergir; el modelo completo no escala a
  2 000 contenedores (se documenta el limite y se usa descomposicion por bloque o ventana deslizante).
- **Restricciones:** el solver debe respetar las mismas reglas fisicas que valida el motor de la base de
  datos (soporte de tiers, 40' ocupa dos bahias contiguas, reefer solo con toma, hazmat segregado,
  peso: lo mas pesado nunca encima de lo mas liviano).

## Alternativas consideradas

- **Google OR-Tools Routing:** resuelto para ruteo de vehiculos, no para asignacion con alturas.
- **Simulacion de annealing:** mas lenta de ajustar y menos reproducible que CP-SAT con semilla fija.
- **Solo heuristica:** insuficiente para el plan nocturno (se pierden oportunidades de reubicacion).
