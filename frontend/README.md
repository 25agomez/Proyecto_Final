# frontend — Interfaz de operacion (React 18 + Vite + TypeScript + Tailwind)

UI para operadores de puerta, supervisores de patio y gerencia.

## Pantallas

1. **Panel de control** — KPIs del patio, semaforo de congestion por puerta con pronostico 15/30/60 min.
2. **Puertas / Monitor de lecturas** — eventos OCR en vivo, cola de revision manual con atajos de teclado.
3. **Mapa del patio** — bloques/bahias/filas/niveles con color por ETD y panel de recomendaciones Top-3.
4. **Flota en tiempo real** — vehiculos en movimiento, geocercas, alertas y KPIs de utilizacion.
5. **Bitacora digital** — visitas filtrables, linea de tiempo, comprobante y exportacion CSV.
6. **Trazabilidad de contenedor** — linea de tiempo puerta -> patio -> vehiculos -> salida.
7. **Administracion** — usuarios/roles, puertas, parametros de optimizacion y umbrales.

## Convenciones

- TypeScript en modo `strict`; ESLint + Prettier; componentes funcionales.
- Textos de UI en **es-CR**; el codigo y los comentarios en ingles.
- Accesibilidad WCAG AA: foco visible, navegacion por teclado, roles ARIA, contraste.
- Datos con TanStack Query; tiempo real con STOMP/WebSocket y reconexion automatica.
- Cliente API tipado generado desde `docs/api/openapi.yaml`.

## Ejecutar

```bash
npm install
npm run dev        # http://localhost:5173
npm run lint
npm run test
npm run build
docker compose -f infra/docker-compose.yml up -d frontend
```

Los endpoints del backend se configuran con `VITE_API_BASE_URL` (ver `.env.example`).
