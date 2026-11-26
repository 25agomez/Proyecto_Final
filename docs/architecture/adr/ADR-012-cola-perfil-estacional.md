# ADR-012 — Definición de cola, perfil estacional walk-forward y espera a horizonte

- **Estado:** Aceptada
- **Fecha:** 2026-10-22
- **Ámbito:** optimizer-agent (P5); archivo en `docs/architecture/` revisado por `architect`

## Contexto

El modelo de colas necesita tres insumos que la base de datos no entrega listos: **cuántos camiones
esperan**, **a qué tasa llegan** y **cuánto dura la atención**. Con el esquema de P1 (`visit_log` con
`entered_at`, `validated_at`, `assigned_at`, `started_at`, `exited_at`) hay que decidir qué columna es
la cola, qué es el servicio y cómo extrapolar el pronóstico al horizonte pedido sin inventar datos.

## Decisión

1. **Cola = camiones esperando carril**, no los que ya están en servicio. El SQL
   (`OPEN_VISITS_SQL`) filtra `assigned_at IS NULL` y la cola del simulador usa la misma definición:
   `queue_length` es el número de camiones que esperan un carril en el instante del pronóstico.
2. **Servicio = `assigned_at − entered_at`** (proxy de extremo a extremo, acotado a 1–120 min) cuando
   no hay datos de proceso más finos. La espera observada que se valida es `wait_seconds`
   (entrada → validación), no el tiempo en patio.
3. **λ = perfil estacional (día de la semana, hora) calculado walk-forward**: para cada franja solo se
   usan las semanas *anteriores* a la fecha del pronóstico. Si no hay semanas previas suficientes se
   cae a la media global y `confidence` baja a `BAJA`. La media horaria se calcula sobre la hora
   completa, sin multiplicar por franjas (error detectado y corregido en EXP-005).
4. **Espera a horizonte (`QueueingModel.waitAtHorizon`):** la espera de un camión que llega en `h`
   minutos es la del sistema M/G/c estacionario con la cola y λ pronosticadas **más** el drenaje del
   backlog actual:
   - si ρ < 1, se suma el tiempo en que la cola actual termina de drenarse dentro de `h`;
   - si ρ ≥ 1, solo cuenta el backlog que se puede drenar en `h` (aproximación fluida, tope 240 min).
   Se evita así que el modelo devuelva el tope de 240 min en cuanto ρ se acerca a 1, que era peor que
   predecir cero.
5. **Modo degradado explícito:** con `< 60` franjas de histórico se responde `model=COLAS`,
   `confidence=BAJA`, sin corrección ML y con los `drivers` que explican por qué.
6. **Riesgo por minutos de espera**, no por utilización: `BAJO` < `medium-risk-minutes`,
   `MEDIO` hasta `high-risk-minutes`, `ALTO` por encima (parámetros en `app.congestion.*`). La
   utilización se informa como driver y dispara una recomendación de refuerzo cuando supera el 100 %,
   aunque el riesgo siga en `BAJO`.
7. **Zona horaria:** los buckets se alinean a la época UTC y el calendario usa
   `America/Costa_Rica` (UTC-6 fijo, sin horario de verano); feriados fijos de Costa Rica más Jueves y
   Viernes Santo calculados por Pascua.

## Consecuencias

- **Positivas:** la cola y la espera son comparables con la operación real (lo que el supervisor ve en
  la puerta); el perfil estacional no filtra el futuro; el modo degradado nunca inventa una predicción
  de ML; el horizonte no satura el modelo.
- **Negativas:** el proxy `assigned_at − entered_at` incluye la espera en la validación, así que el
  servicio está sobreestimado (se acota a 120 min); el perfil estacional necesita al menos dos semanas
  para estabilizarse; los feriados móviles dependen del cálculo de Pascua.
- **Restricciones:** cualquier cambio al esquema de `visit_log` que rompa `assigned_at`/`entered_at`
  invalida el modelo; los feriados deben cargarse en el calendario antes de la corrida del job.

## Alternativas consideradas

- **Cola = visitas abiertas (incluye en servicio):** sobreestima la espera entre 1 y 2 carriles y
  contradice lo que ve el operador; descartada.
- **λ = media de la hora anterior observada:** muy ruidosa (una hora con dos camiones de más duplica
  la tasa); descartada por el perfil estacional.
- **λ = citas programadas solas:** infravalora las llegadas sin cita (≈40 % del total); se usa como
  cota inferior (`max(perfil, citas)`).
- **Servicio = `started_at − assigned_at`:** más preciso pero hoy hay pocas filas con `started_at`;
  queda como mejora cuando la puerta registre el inicio de proceso de forma fiable.
