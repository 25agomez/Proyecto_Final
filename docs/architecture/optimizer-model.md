# Modelo del optimizador de stacking (P4)

- **Estado:** vigente
- **Fecha:** 2026-10-22
- **Ámbito:** optimizer-agent (P4)
- **Código:** `backend/src/main/java/cr/logisticaportuaria/optimizer/`
- **Mediciones:** `docs/experiments/EXP-004-stacking.md` y `EXP-004-stacking-benchmark.md`

Este documento describe el problema de acomodo (*stacking*) que resuelve el optimizador, las
restricciones duras, la función de coste, el modelo CP-SAT de OR-Tools y las tres
descomposiciones que lo hacen tratable. No describe la API: eso está en
`docs/api/openapi.yaml` y en `backend/README.md`.

---

## 1. Alcance

Dado un patio con slots ocupados y libres, y un lote de contenedores que llegan (por puerta o
por planificación de buque), se decide en qué slot va cada contenedor. El criterio es
**minimizar el trabajo improductivo futuro**:

1. *restows* (reubicaciones de contenedores tapados) si los ETD no cambian;
2. distancia a la puerta/anden, ponderada por la urgencia del ETD;
3. dispersión de un mismo cliente por el patio;
4. altura de apilado;
5. orden de pesos (un contenedor pesado nunca va sobre uno liviano).

El sistema expone dos estrategias con el **mismo objetivo**:

| Estrategia | Uso | Garantía |
|---|---|---|
| `HEURISTIC` | decisión del borde (1 contenedor) y lotes grandes | rápida, determinista, p95 < 200 ms con 5 000 slots |
| `CP_SAT` | planificación por lotes (asíncrona) | mejor solución global dentro de un presupuesto de tiempo |

---

## 2. Datos y notación

| Símbolo | Significado | Origen |
|---|---|---|
| `S` | conjunto de slots del patio | `yard_slot` + `yard_block` (`YardSnapshotLoader`) |
| `s = (b, bay, row, t)` | slot: bloque, bahía, fila, nivel | `yard_slot` |
| `size(s) ∈ {20, 40}` | tamaño que acepta el slot | `yard_slot.size_ft` |
| `type(s) ∈ {dry, reefer, hazmat}` | tipo de carga del slot | `yard_slot.slot_type` |
| `power(s) ∈ {0,1}` | el slot tiene toma eléctrica | `yard_slot.has_power` |
| `maxTier(b)` | altura máxima del bloque | máximo nivel observado por bloque |
| `d(s)` | distancia a la puerta en metros | `DistanceModel` |
| `I` | lote de contenedores a ubicar | petición / catálogo `container` |
| `i = (code, size, type, w, etd, client, power)` | contenedor del lote | `container` |
| `occ(s)` | contenedor que ocupa `s` (o ⊥) | `container_position` activa |

Las distancias `d(s)` son **sintéticas**: el esquema de P1 no guarda una distancia por slot. Se
derivan geométricamente y son estables:

```
d(b, bay, row, t) = 40 + 60·idx(b) + 6·(bay − 1) + 4·(row − 1) + 1.5·(t − 1)      [m]
```

`idx(b)` es la posición alfabética del bloque (`A = 0`). Cuando el arquitecto agregue una
columna real de distancia, solo cambia `DistanceModel`.

---

## 3. Restricciones duras (`PlacementRules`)

Un par (contenedor, slot) es **factible** solo si se cumplen todas:

1. **Tamaño:** `size(s) = size(i)`. Un 40' ocupa dos bahías contiguas en el esquema, por eso un
   slot de 40' ya las representa.
2. **Altura:** `t ≤ maxTier(b)`.
3. **Disponibilidad:** `occ(s) = ⊥`.
4. **Tipo y energía:**
   - `hazmat` solo en slots `hazmat`, y los slots `hazmat` no aceptan otra carga;
   - `reefer` o `requiresPower(i)` exige `power(s) = 1`;
   - el resto, solo en slots `dry`.
5. **Apoyo:** si `t > 1`, `occ(s↓) ≠ ⊥` (donde `s↓` es el slot inmediatamente inferior de la
   misma columna); no se permiten contenedores flotantes.
6. **Peso:** si `t > 1` y ambos pesos son conocidos, `w(i) ≤ w(occ(s↓))`.

Estas reglas son compartidas por la heurística, CP-SAT, las líneas base y las pruebas de
propiedad: cualquier plan que las viole se considera un fallo, no una preferencia.

---

## 4. Función de coste (`SlotScorer`)

El coste de ubicar `i` en `s` es la suma de cinco términos (menor es mejor). Todos los pesos son
configurables en `app.optimizer.weights.*`; los valores por defecto son
`(restow, distancia, dispersión, altura, peso) = (4.0, 2.0, 0.5, 0.3, 1.0)`.

### 4.1 Restow

```
restow(s, i) = min(5, 1 + días(i.etd, a.etd)   para cada a arriba con a.etd > i.etd
                    + 1 + días(b.etd, i.etd)   si el apoyo b sale antes que i) · w_restow
```

- Si `i` queda **encima** de un contenedor que sale antes, `i` lo bloquea.
- Si `i` queda **debajo** de contenedores que salen después, `i` queda tapado.
- `días(x, y)` se acota a 7 días; los ETD nulos se tratan como "muy lejanos".

### 4.2 Distancia

```
distancia(s, i) = w_distancia · (d(s) / d_max) · urgencia(i.etd)
urgencia(etd)   = clamp(1 − 0.9 · días(ahora, etd) / 7, 0.1, 1)      (0.5 si no hay ETD)
```

Un contenedor que sale pronto paga más por estar lejos de la puerta.

### 4.3 Dispersión

```
dispersión(s, i) = −w_dispersión   si la bahía vecina (misma fila, nivel más bajo) es del mismo cliente
                   0                en otro caso
```

Es un **bono**: agrupa la carga de un cliente para facilitar el despacho.

### 4.4 Altura y 4.5 peso

```
altura(s) = w_altura · (t − 1)
peso(s, i) = w_peso · max(0, w(i) − w(apoyo)) / 1000      (solo si t > 1)
```

La altura penaliza el uso de niveles altos; el peso penaliza apilar sobre un contenedor más
liviano (el caso `w(i) > w(apoyo)` es directamente infactible, ver §3.6).

El coste total es `score(s, i) = restow + distancia + dispersión + altura + peso`, redondeado a
3 decimales. La **explicación en español** que devuelve la API se construye con los mismos
términos (`SlotScorer.explain`).

---

## 5. Modelo CP-SAT

### 5.1 Variables

Para cada contenedor `i` y cada slot candidato `k ∈ K(i)` (ver §6.1):

```
x[i][k] ∈ {0,1}     1 si i se ubica en el slot k
skip[i] ∈ {0,1}     1 si i queda sin ubicar
```

### 5.2 Restricciones

1. **Un destino por contenedor** (o ninguno):

   ```
   Σ_k x[i][k] + skip[i] = 1        ∀ i
   ```

2. **Un contenedor por slot** (los slots ya ocupados no están en `K(i)`):

   ```
   Σ_{i,k : slot(k) = s} x[i][k] ≤ 1     ∀ s
   ```

3. **Apoyo:** si `s↓` está libre, el contenedor que va en `s` exige que alguien llene `s↓`:

   ```
   Σ_k' x[j][k'] − x[i][k] ≥ 0      ∀ (i,k) con t(s) > 1 y s↓ libre
   ```

   donde la suma recorre los candidatos `k'` cuyo slot es exactamente `s↓`. La restricción es
   lineal (implicación `x[i][k] ⇒ (alguien en s↓)`), no requiere variables auxiliares.

4. **Reglas físicas:** tamaño, tipo, energía, altura y peso **no** aparecen como restricciones:
   se aplican al generar `K(i)` (§6.1), que solo contiene pares factibles. Esto es una
   descomposición explícita: el modelo no puede violarlas porque no las ve.

### 5.3 Objetivo

```
min  Σ_{i,k} round(1000 · score(slot(k), i)) · x[i][k]
   + Σ_i 10⁹ · skip[i]
```

La penalización de `skip` es cinco órdenes de magnitud mayor que cualquier coste de ubicación
(los costes se escalan a enteros). Por eso el solver **primero maximiza la cantidad de
contenedores ubicados** y después minimiza los restows y la distancia. Además garantiza que el
modelo siempre sea factible (la solución "todo sin ubicar" existe), lo que evita que el solver
gaste el presupuesto de tiempo demostrando una infactibilidad provocada por el truncado de
candidatos.

El objetivo es **lineal** en las variables: los costes son constantes precalculadas. No se
modelan los restows entre contenedores del mismo lote (ver §9).

---

## 6. Descomposiciones (y por qué son necesarias)

El modelo completo (todos los pares factibles) tiene `|I| · |S|` variables: 2 000 × 5 000 =
10⁷ booleanos, inviable. Se aplican tres reducciones, todas medidas en
`EXP-004-stacking-benchmark.md`.

### 6.1 Poda de candidatos con diversidad

Para cada `i`, se ordenan sus slots factibles por `score` y se conservan `maxCandidates`
(por defecto `32`, `app.optimizer.max-candidates`):

- la **mitad más barata** (calidad);
- la otra mitad **repartida uniformemente** por el resto del ranking (diversidad);
- el slot elegido por la heurística se **fuerza** en la lista (§6.2).

Sin el reparto, todos los contenedores proponen los mismos pocos slots baratos: la unión de
candidatos es menor que el lote y el modelo se vuelve infactible (medido: en el escenario M,
500 contenedores compartían menos de 500 slots candidatos y CP-SAT devolvía `UNKNOWN` tras
agotar el tiempo). Con el reparto, la unión crece y el modelo vuelve a ser tratable.

### 6.2 Warm start desde la heurística

Antes de resolver, se ejecuta la heurística sobre una **copia** del patio y se pasan sus
decisiones como *hints* (`model.addHint`). CP-SAT arranca con una solución válida y la mejora
como conjunto. El slot elegido por la heurística se conserva siempre entre los candidatos para
que el hint sea consistente con el modelo.

### 6.3 Horizonte rodante (chunks)

El lote se ordena por **ETD descendente** (igual que la heurística: primero los que salen más
tarde, que quedan en el fondo de las pilas) y se parte en bloques de
`MAX_BATCH = 250` contenedores. Cada bloque se resuelve contra el patio que dejó el anterior y
recibe una parte del tiempo restante:

```
presupuesto(chunk) = max(1 s, min(tiempo_restante, ⌈tiempo_restante / chunks_restantes⌉))
```

Medido en el escenario L (2 000 contenedores, 15 s): sin chunks CP-SAT devolvía `UNKNOWN` con
0 ubicados; con chunks devuelve `FEASIBLE`, ubica 1 969 y añade 5 restows. El orden ETD es lo
que evita que el coste estático (§9) apile un contenedor que sale antes sobre uno que sale
después.

**Consecuencia:** el plan es una composición de óptimos locales, no un óptimo global. El
`status` que publica la API (`OPTIMAL`/`FEASIBLE`/`UNKNOWN`) se refiere al solver, no a una
garantía de optimalidad del lote completo.

### 6.4 Soporte de poda

`pruneUnsupported` elimina en punto fijo los candidatos de nivel `t > 1` cuyo apoyo libre no
aparece en la unión de candidatos de ningún contenedor: si nadie puede llenarlo, ese candidato
nunca será válido. Reduce el modelo y evita restricciones de apoyo imposibles.

---

## 7. Heurística ETD-aware y líneas base

### 7.1 Heurística (`HeuristicPlanner`)

```
ordenar I por ETD descendente (desempate por código)
para cada i en ese orden:
    evaluar todos los slots s con PlacementRules.canPlace(patio, s, i)
    ubicar i en el s de menor score
    si no hay ninguno: i queda sin ubicar
```

Complejidad `O(|I| · |S|)` evaluaciones de coste, sin estructuras auxiliares. Con 5 000 slots
el p95 medido es **2 ms** para el Top-3 de un contenedor y ~0,7 s para un lote de 2 000.

El Top-3 (`/recommendations`) usa el mismo scorer: se evalúan todos los slots factibles, se
ordenan y se devuelven los tres mejores con su explicación.

### 7.2 Líneas base (`BaselinePlanner`)

- **FIFO:** recorre los slots en orden físico (bloque, fila, nivel, bahía) y ubica en el primero
  factible. Modela la operación actual de muchos patios.
- **RANDOM:** elige al azar entre los slots factibles con semilla fija, para descartar que la
  mejora provenga solo de "no hacer lo peor".

Ambas respetan las mismas reglas físicas y parten del mismo patio que la heurística.

---

## 8. Métricas de comparación (`YardMetrics`)

| Métrica | Definición |
|---|---|
| `occupancy` | slots ocupados / slots totales |
| `expectedRestows` | pares (contenedor, contenedor encima) donde el de arriba sale **después**; el de abajo queda tapado |
| `averageDistanceMeters` | media de `d(s)` sobre los slots ocupados |

`expectedRestows` cuenta el patio completo, no solo la contribución del lote. Para comparar
estrategias el benchmark publica además `restows añadidos = restows finales − restows iniciales`
(patio vacío de lote), porque las estrategias pueden ubicar cantidades distintas.

---

## 9. Límites del modelo (lo que el optimizador **no** garantiza)

1. **No es un óptimo global del lote:** el horizonte rodante compone soluciones locales.
2. **Los restows son estáticos:** el coste de un par (contenedor, slot) se calcula contra el
   estado actual del patio. Si dos contenedores del mismo lote terminan en la misma pila, el
   modelo no ve el conflicto entre ellos (lo mitiga el orden ETD de los chunks y el warm start).
3. **No planifica movimientos de restow:** no propone mover un contenedor existente para liberar
   una pila; solo decide dónde va cada contenedor nuevo.
4. **No conoce grúas ni ventanas de atención:** el tiempo de cómputo es el único recurso
   limitado; el plan no está secuenciado contra la disponibilidad de equipos.
5. **La distancia es sintética** (§2): sirve para comparar y ordenar, no es un levantamiento
   topográfico del patio.
6. **Los ETD son la fuente de verdad:** si cambian, los restows esperados cambian con ellos; el
   plan no se reoptimiza automáticamente.

---

## 10. Reproducibilidad y verificación

- **Sin azar no fijado:** CP-SAT usa `numSearchWorkers=1`, `randomSeed=42`; las líneas base y
  los escenarios usan semillas explícitas (`ScenarioGenerator`, `SEED = 2026` en el benchmark).
  El mismo patio y el mismo lote producen el mismo plan (`StackingPropertyTest`).
- **Reglas:** `StackingPropertyTest` valida 200 escenarios generados, movimiento a movimiento,
  contra el estado del patio en ese instante.
- **Rendimiento:** `HeuristicPerformanceTest` mide el p95 con 5 000 slots y falla si supera
  200 ms.
- **Comparación:** `StackingBenchmarkTest` genera el CSV y la tabla de
  `docs/experiments/EXP-004-stacking-benchmark.md` en cada `./mvnw verify`.
