# EXP-005 · Informe reproducible de congestión

Generado por `CongestionRegressionTest` (`cd backend && ./mvnw test -Dtest=CongestionRegressionTest`). No editar a mano.

## Escenario simulado

| Parámetro | Valor |
|---|---|
| Puerta | `33333333-3333-7333-8333-333333333331` (G1) |
| Ventana | 2026-09-01T06:00:00Z + 21 días |
| Visitas simuladas | 7064 |
| Carriles (servidores) | 3 |
| Servicio medio | 7.0 min (lognormal, sigma 0.3) |
| Llegadas base | 18.0 por hora (punta mañana x2.0, punta tarde x1.7, noche x0.1, fin de semana x0.6, feriado x0.3) |
| Semilla | 2026 |
| Citas | 60 % de las llegadas |

## Modelo

| Propiedad | Valor |
|---|---|
| Confianza global | ALTA |
| Franjas de 15 min por horizonte | 1531 |
| Partición temporal | 80 % entrenamiento / 20 % hold-out (sin fuga) |
| Servicio medio estimado | 7.033 min (cv² 0.100) |
| Tiempo de entrenamiento (3 horizontes) | 501 ms |
| Latencia del pronóstico | p50 0.251 ms · p95 1.011 ms · p99 2.226 ms |

## Métricas del hold-out temporal

| Horizonte | Modelo | MAE (min) | RMSE (min) | MAPE (%) | Cobertura p10-p90 | Filas |
|---|---|---|---|---|---|---|
| 15 min | Colas M/G/c | 4.907 | 8.886 | 335.509 | 0.818 | 307
| 15 min | Suavizado HW | 7.884 | 25.185 | 928.655 | 0.827 | 307
| 15 min | Híbrido | 3.248 | 5.762 | 127.994 | 0.850 | 307
| 15 min | Ingenua | 8.496 | 14.656 | 276.091 | 0.873 | 307
| 30 min | Colas M/G/c | 5.481 | 9.734 | 356.759 | 0.801 | 306
| 30 min | Suavizado HW | 10.232 | 29.097 | 1759.750 | 0.804 | 306
| 30 min | Híbrido | 3.919 | 7.018 | 142.833 | 0.869 | 306
| 30 min | Ingenua | 9.618 | 16.436 | 386.638 | 0.889 | 306
| 60 min | Colas M/G/c | 7.031 | 12.334 | 416.128 | 0.850 | 306
| 60 min | Suavizado HW | 12.356 | 25.694 | 805.390 | 0.794 | 306
| 60 min | Híbrido | 5.235 | 9.510 | 169.295 | 0.879 | 306
| 60 min | Ingenua | 11.565 | 19.399 | 696.438 | 0.886 | 306

> MAPE se calcula solo sobre filas con espera real > 0: con esperas de pocos segundos el
> porcentaje se dispara y no es comparable entre modelos; MAE y RMSE sí lo son.

- **15 min:** modelo activo **HIBRIDO**; la corrección de boosting mejoró el MAE del modelo de colas en el hold-out.
- **30 min:** modelo activo **HIBRIDO**; la corrección de boosting mejoró el MAE del modelo de colas en el hold-out.
- **60 min:** modelo activo **HIBRIDO**; la corrección de boosting mejoró el MAE del modelo de colas en el hold-out.

## Pronóstico en el instante de referencia

| Horizonte | Espera (min) | p10 | p90 | Cola | Riesgo | Modelo |
|---|---|---|---|---|---|---|
| 15 min | 0.000 | 0.000 | 4.944 | 0.0 | BAJO | HIBRIDO |
| 30 min | 0.000 | 0.000 | 6.780 | 0.0 | BAJO | HIBRIDO |
| 60 min | 0.000 | 0.000 | 10.844 | 0.0 | BAJO | HIBRIDO |

### Ejemplo ilustrativo en hora punta (17:30 local; no forma parte de la validación)

| Horizonte | Espera (min) | p10 | p90 | Cola | Riesgo | Modelo |
|---|---|---|---|---|---|---|
| 15 min | 5.525 | 0.000 | 10.469 | 0.7 | BAJO | HIBRIDO |
| 30 min | 0.673 | 0.000 | 7.454 | 0.0 | BAJO | HIBRIDO |
| 60 min | 1.268 | 0.000 | 12.112 | 0.0 | BAJO | HIBRIDO |

### Drivers del pronóstico a 15 min (17:30 local)

- 0 camiones esperando un carril en la puerta G1 al iniciar el pronóstico
- 9 citas programadas entre las 17:45 y las 18:45
- Franja punta de la tarde (15:00-18:00) con mayor llegada de camiones
- 28,3 llegadas/hora previstas para 3 carriles con atención media de 7,0 min (utilización 111 %)
- Corrección de boosting a 15 min entrenada con 1531 franjas (MAE 3,2 min)

### Recomendaciones (17:30 local)

- Operación normal: mantener los 3 carriles actuales sin cambios.
- La demanda prevista (111 % de la capacidad) supera la atención disponible: vigilar la fila y tener un carril de refuerzo listo.

## Hold-out por hora local (media, horizonte 15 min)

| Hora | Filas | Real | Híbrido | Suavizado | Colas |
|---|---|---|---|---|---|
| 00:00 | 3 | 0.000 | 0.064 | 0.000 | 0.002 |
| 01:00 | 4 | 0.000 | 0.095 | 0.004 | 0.002 |
| 02:00 | 8 | 0.000 | 0.097 | 0.023 | 0.006 |
| 03:00 | 5 | 0.000 | 0.038 | 0.069 | 0.003 |
| 04:00 | 7 | 0.000 | 0.027 | 0.006 | 0.015 |
| 05:00 | 4 | 0.000 | 0.096 | 0.007 | 0.003 |
| 06:00 | 16 | 0.770 | 0.198 | 3.136 | 1.971 |
| 07:00 | 16 | 7.662 | 9.170 | 7.116 | 14.016 |
| 08:00 | 16 | 21.558 | 24.952 | 22.294 | 29.869 |
| 09:00 | 16 | 30.521 | 30.315 | 28.772 | 32.324 |
| 10:00 | 15 | 31.789 | 28.971 | 34.069 | 34.185 |
| 11:00 | 16 | 21.207 | 20.012 | 24.564 | 22.528 |
| 12:00 | 16 | 19.844 | 15.582 | 21.654 | 19.087 |
| 13:00 | 15 | 8.269 | 9.671 | 21.590 | 12.741 |
| 14:00 | 15 | 1.330 | 3.418 | 9.189 | 6.539 |
| 15:00 | 16 | 5.667 | 4.482 | 29.877 | 5.124 |
| 16:00 | 16 | 9.054 | 9.633 | 26.988 | 13.946 |
| 17:00 | 16 | 14.221 | 14.295 | 18.341 | 20.266 |
| 18:00 | 15 | 19.186 | 17.089 | 17.836 | 19.357 |
| 19:00 | 18 | 13.834 | 15.307 | 28.991 | 19.381 |
| 20:00 | 20 | 8.795 | 10.132 | 16.480 | 13.604 |
| 21:00 | 20 | 2.351 | 3.554 | 6.472 | 5.941 |
| 22:00 | 7 | 0.000 | 0.058 | 0.035 | 0.001 |
| 23:00 | 7 | 0.000 | 0.169 | 0.002 | 0.005 |

## Gráfico ASCII del hold-out (media por día, horizonte 15 min)

```
2026-09-17  real  23,8 ################################################
           hibr  27,7 =======================================================
2026-09-18  real  25,5 ###################################################
           hibr  25,1 ==================================================
2026-09-19  real   0,6 #
           hibr   1,7 ===
2026-09-20  real   2,0 ####
           hibr   1,7 ===
2026-09-21  real  14,4 #############################
           hibr  14,0 ============================
```

## Gráfico ASCII del hold-out (media por día, horizonte 60 min)

```
2026-09-17  real  20,6 #########################################
           hibr  26,4 =====================================================
2026-09-18  real  25,5 ###################################################
           hibr  24,2 ================================================
2026-09-19  real   0,6 #
           hibr   1,1 ==
2026-09-20  real   2,0 ####
           hibr   2,0 ====
2026-09-21  real  14,4 #############################
           hibr  17,0 ==================================
```
