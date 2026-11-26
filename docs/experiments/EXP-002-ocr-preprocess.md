# Evaluacion OCR - modo `preprocess`

- Dataset: `data/synthetic`
- Imagenes evaluadas: 240

| Metrica | Valor |
|---|---|
| Latencia p50 (recorte + preprocesado) | 7.093 ms |
| Latencia p95 (recorte + preprocesado) | 11.456 ms |
| Latencia media | 8.25 ms |

> Mide la parte del pipeline que no depende de los pesos (recorte orientado,
> CLAHE, nitidez, binarizacion adaptativa y las cuatro rotaciones). La deteccion
> y el reconocimiento se miden en el modo `live` (NO MEDIDO sin los pesos).
