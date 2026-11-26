# Evaluacion OCR - modo `corrupt`

- Dataset: `data/synthetic`
- Imagenes evaluadas: 240

| Metrica | Valor |
|---|---|
| Exactitud por codigo completo (raw) | 60.0 % |
| Exactitud por codigo completo (post-proceso) | 74.58 % |
| CER (raw) | 0.047222 |
| CER (post-proceso) | 0.034722 |
| Check digit valido | 95.83 % |
| Lecturas corregidas | 16.67 % |
| Lecturas invalidas (revision manual) | 4.17 % |
| Latencia p50 (post-proceso) | 0.003 ms |
| Latencia p95 (post-proceso) | 0.018 ms |

> El modo `corrupt` mide la capa de post-procesamiento con un modelo de error
> simulado; no mide la precision del OCR neuronal (modo `live`).

## Muestra de errores (15 de 61)

| id | tipo | verdad | lectura simulada | salida | check digit | corregido |
|---|---|---|---|---|---|---|
| 0001 | PLATE | P340114 | P940114 | P940114 | None | False |
| 0005 | PLATE | AB58860 | AB58868 | AB58868 | None | False |
| 0021 | PLATE | AB92082 | A892082 | A892082 | None | False |
| 0023 | PLATE | ABC1192 | ABC1197 | ABC1197 | None | False |
| 0025 | PLATE | P181265 | P18126S | P18126S | None | False |
| 0027 | PLATE | AB05502 | ABD5502 | ABD5502 | None | False |
| 0029 | PLATE | AB35356 | AB35355 | AB35355 | None | False |
| 0033 | PLATE | ABC3628 | A9C3628 | A9C3628 | None | False |
| 0039 | PLATE | AB59873 | AB59823 | AB59823 | None | False |
| 0044 | CONTAINER | TROJ6352015 | TROJ6357015 | TROJ6367015 | True | True |
| 0051 | PLATE | ABC1289 | ABC289 | ABC289 | None | False |
| 0052 | CONTAINER | URPJ3008438 | URPJ3006438 | URPJ8006438 | True | True |
| 0056 | CONTAINER | CSPZ5619236 | CSPZ5619796 | GSPZ5619796 | True | True |
| 0057 | PLATE | P147157 | 147157 | 147157 | None | False |
| 0059 | PLATE | AB37467 | A37467 | A37467 | None | False |
