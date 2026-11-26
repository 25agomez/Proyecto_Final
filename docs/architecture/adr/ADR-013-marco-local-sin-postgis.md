# ADR-013 — Marco métrico local del patio, sin PostGIS

- **Estado:** Aceptada
- **Fecha:** 2026-10-23
- **Ámbito:** backend-agent (P6); archivo en `docs/architecture/` revisado por `architect`

## Contexto

P6 recibe telemetría GPS (`lat`/`lon` en WGS84) y debe calcular distancias, geocercas y rutas de
vehículos. El esquema de P1 (`V1__init.sql`, inmutable) guarda `vehicle_position.x` / `.y` como
`NUMERIC(9,2)` y el seed de P1 ya define las geocercas como polígonos **en metros** (PUERTA
`x 0-120, y 0-40`; HAZMAT `x 300-420, y 0-40`; PROHIBIDA `x 200-260, y 60-100`). No hay columnas
`geography`/`geometry` ni extensión PostGIS instalada.

Alternativas consideradas:

1. **PostGIS** con `geography(Point,4326)`: exacta a escala global, pero exige `CREATE EXTENSION`
   (privilegios de superusuario), migración nueva sobre tablas ya sembradas y un `JOIN` espacial por
   cada mensaje de telemetría (1 msg/s por vehículo, hasta 50 vehículos).
2. **Haversine en Java** contra las geocercas en lat/lon: precisa, pero obliga a reproyectar las
   geocercas del seed en cada arranque y a mantener dos representaciones.
3. **Marco local equirectangular** anclado en el patio, persistido tal cual en `x`/`y`.

El patio mide unos 400 m × 200 m: la curvatura terrestre en esa extensión es despreciable frente a la
precisión de un GPS comercial (±3-5 m).

## Decisión

1. **Ancla fija** `YardFrame` en el puerto de Limón: lat `9.9908`, lon `-83.0350`, con
   `METERS_PER_DEGREE_LAT = 111 320` y `METERS_PER_DEGREE_LON = 111 320 · cos(lat)`.
   `x` crece hacia el este y `y` hacia el norte, en metros desde el ancla.
2. La conversión **solo ocurre en el borde del módulo**: el suscriptor MQTT decodifica `lat`/`lon` y
   `FleetTelemetry.normalized()` los convierte con `YardFrame`. El dominio, los repositorios y las
   alertas trabajan siempre en `x`/`y` metros; el track GeoJSON invierte la conversión para responder
   `[lon, lat]` como manda RFC 7946.
3. Las geocercas se leen del JSONB del seed **sin reproyectar**: sus coordenadas ya están en metros.
   `GeoPolygon` implementa *ray casting* y `centroid()` con la fórmula del área (no el promedio de
   vértices) para el punto de referencia de las asignaciones.
4. **Distancias euclidianas** en metros (`TrackGeometry.distance`): asignación del vehículo más
   cercano, KPI de distancia recorrida y validación de saltos imposibles.
5. `YardLayout` replica en `fleet` las constantes de `DistanceModel` del optimizador
   (40 m + 60 m por bloque + 6 m por bahía en `x`; 4 m por fila en `y`) para calcular la posición de un
   slot. **No se acopla `fleet` con `optimizer`**: si el layout cambia, cambian ambos y lo dice este ADR.

## Consecuencias

- **Positivas:** cero dependencias nuevas (ni extensión ni privilegios), cero migraciones sobre el
  esquema del arquitecto, lectura de geocercas sin transformación y distancias O(1) por mensaje.
  El error de la aproximación es < 0,1 m en 400 m, muy por debajo del ruido del GPS.
- **Negativas:** el marco es **local al patio**: si mañana hay dos patios separados por kilómetros,
  cada uno necesita su ancla y una tabla de anclas (o PostGIS). El acoplamiento de constantes con
  `DistanceModel` es una duplicación deliberada y documentada.
- **Revisión:** si entra un segundo patio o se pide geolocalización a escala nacional, migrar a PostGIS
  con una migración nueva (nunca editando `V1`) y conservar `x`/`y` como caché derivada.
