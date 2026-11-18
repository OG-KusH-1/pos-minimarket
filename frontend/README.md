# Frontend - POS Minimarket

Angular 22 (standalone, zoneless, signals) con Angular Material 3. Se comunica con la API
(`/api/v1`) mediante un cliente generado desde OpenAPI.

Requiere Node `^22.22.3 || ^24.15.0 || >=26` (ver `docs/adr/0001-versiones.md`).

## Scripts

| Comando           | Descripción                                                                                                |
| ----------------- | ---------------------------------------------------------------------------------------------------------- |
| `npm start`       | Servidor de desarrollo en `http://localhost:4200` (proxy `/api` a `localhost:8080`, ver `proxy.conf.json`) |
| `npm run build`   | Compilación de producción en `dist/frontend/browser`                                                       |
| `npm test`        | Pruebas con Vitest (`npm test -- --watch=false` para una sola corrida)                                     |
| `npm run lint`    | ESLint (angular-eslint)                                                                                    |
| `npm run format`  | Aplica Prettier (`format:check` solo verifica)                                                             |
| `npm run api:gen` | Regenera `src/app/api` desde `openapi/openapi.json` (ng-openapi-gen)                                       |

## Estructura

- `src/app/core`: layout (`core/layout`), y más adelante auth, interceptores y guards.
- `src/app/shared`: componentes, pipes y validadores reutilizables.
- `src/app/api`: **generado, no editar** (excluido de lint y Prettier). `openapi/openapi.json` lo
  produce una prueba del backend; luego se ejecuta `npm run api:gen`.
- `src/app/features/<feature>/<sub>`: pantallas. Cada sub-feature tiene `<sub>.routes.ts` y se carga
  de forma diferida desde `src/app/app.routes.ts`. El menú sale de `core/layout/nav-items.ts`.
- Tema Material 3 en `src/styles.scss` y `src/_theme-colors.scss` (paleta generada con
  `ng generate @angular/material:m3-theme`).
