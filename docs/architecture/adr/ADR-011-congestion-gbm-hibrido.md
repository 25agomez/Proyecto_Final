# ADR-011 — Pronóstico de congestión por horizonte con gradient boosting propio e híbrido con gate

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** optimizer-agent (P5); archivo en `docs/architecture/` revisado por `architect`

## Contexto

Hay que pronosticar la espera y la cola de una puerta a 15, 30 y 60 minutos, de forma explicable y
100 % local. El prompt exige implementar y comparar tres enfoques: colas M/M/c–M/G/c
(Allen-Cunneen), un método estadístico (Holt-Winters o ARIMA estacional de llegadas por franja de
15 min) y ML ligero (gradient boosting), y quedarse con un híbrido solo si mejora de verdad.

El histórico real no existe todavía (P5 se ejecutó sobre un simulador de eventos discretos). El
equipo no tiene GPU ni quiere añadir una dependencia pesada de ML al backend Java.

## Decisión

1. **Un modelo por horizonte.** `CongestionForecaster` entrena tres `HorizonModel` independientes
   (15, 30 y 60 min) porque el estado que predice la espera dentro de 15 min no es el mismo que el de
   60 min: las variables de estado se toman en el origen, las de calendario en la franja objetivo y
   la línea base cambia con el horizonte.
2. **Tres enfoques comparados con la misma partición temporal (80/20, sin fuga):**
   - `COLAS`: M/G/c Allen-Cunneen con la cola realmente esperando carril en el origen y λ del perfil
     estacional (día de la semana, hora), extendido con `QueueingModel.waitAtHorizon`.
   - `SUAVIZADO`: el mismo modelo de colas, pero con λ pronosticada por Holt-Winters aditivo
     (`ExponentialSmoothing.walkForward`, parámetros fijos a priori). Es el enfoque estadístico
     evaluado en espacio de espera, que es la salida común.
   - `HIBRIDO`: la línea base de colas más una corrección del residual con un **gradient boosting
     propio** (árboles de regresión, 120 árboles, profundidad 3, `randomSeed=42`). Las variables son
     el estado en el origen (camiones esperando, citas de la hora siguiente, llegadas de la hora
     previa) y el calendario de la franja objetivo, todo conocido al pronosticar.
3. **Gate de la corrección.** El híbrido solo queda activo si su MAE en el hold-out mejora el del
   modelo de colas en **≥ 0,05 min**; si no, se devuelve `COLAS`. El modelo entrenado se cachea por
   puerta durante `retrain-minutes` (60 min por defecto).
4. **Sin dependencia de ML:** el GBM está implementado en el propio dominio (≈200 líneas) en lugar de
   Smile/Weka. Menos dependencias, resultado determinista por semilla y auditable en el código.
5. **La comparación se publica** en `docs/experiments/EXP-005-congestion/metrics.md` y en
   `GET /api/v1/congestion/forecast` (`quality.queueMae`, `quality.smoothedMae`,
   `quality.hybridMae`, `quality.naiveMae`).

## Consecuencias

- **Positivas:** la mejora frente a la línea base es medible y reproducible (semilla fija); el
  supervisor ve las mismas variables que usa el modelo (`drivers`); no hay caja negra externa ni
  llamadas de red; el gate evita empeorar el pronóstico con una corrección que no aporta.
- **Negativas:** tres entrenamientos por puerta (~0,5 s con 1 531 franjas); el GBM propio no tiene
  las optimizaciones de una librería madura; el modelo depende de que el calendario y las citas estén
  cargados para rendir.
- **Restricciones:** el pronóstico debe seguir respondiendo con `< 60` franjas de histórico
  (`confidence=BAJA`, sin corrección ML); el GBM nunca se entrena con la franja objetivo.

## Alternativas consideradas

- **Smile / Weka / XGBoost4J:** obliga a otra dependencia y a serializar modelos; el problema es
  pequeño (10 variables, ~1 500 filas) y no compensa. Queda como mejora futura si el histórico real
  crece.
- **Solo modelo de colas:** no captura efectos no lineales (feriados, citas, efecto de la cola) y deja
  la MAE 25–34 % peor en el escenario medido.
- **Solo serie temporal (Holt-Winters):** en el escenario medido empeora al modelo de colas
  (MAE 7,9–12,4 frente a 4,9–7,0) por el ruido de las franjas de 15 min; se conserva como línea base
  y como insumo del modelo de colas.
- **Un único modelo para los tres horizontes:** mezcla regímenes distintos y degrada el horizonte
  largo.
