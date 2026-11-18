# Estado del proyecto — POS Minimarket

> **Para retomar el trabajo** (nueva sesión, contexto compactado u otro agente): lee este
> archivo primero, luego `CLAUDE.md` y la sección del plan de la fase en curso
> (`docs/PLAN.md` H.3). Este archivo lo actualiza el orquestador al cerrar cada oleada.

**Última actualización:** 2026-11-13 · **Fase en curso:** 1 (MVP), sin iniciar · **Commits:** ninguno todavía

## Decisiones vigentes

| Tema | Decisión | Dónde |
|---|---|---|
| Plan | Aprobado con las recomendaciones por defecto (D-01 a D-10) | `docs/PLAN.md` I.2 |
| Spring Boot | 4.1.2 (no 4.0.x) | `docs/adr/0001-versiones.md` |
| Frontend | Angular 22.2, Node ≥ 22.22.3 (local: v26.13.1) | ídem |
| Repositorio | Git propio en `pos-minimarket`; el repo padre no se toca | — |
| Antigravity | Traspaso manual por archivos (sus términos prohíben invocarlo desde otro agente) | `docs/tareas/README.md`, `AGENTS.md` |
| Notas para Fase 1+ | Obligatorias (globales `@GlobalEndpoint`, `StoreAccessValidator`, idempotencia, etc.) | `docs/PLAN.md` L |

## Avance por fase

| Fase | Estado | Notas |
|---|---|---|
| 0 Cimientos | **Terminada y revisada** (T1–T16) | Backend: 201 pruebas unitarias + 68 IT. Frontend: 307 pruebas. CI y Docker Compose listos. |
| 1 MVP | En curso | O1 (T17–T20) entregada; revisión 1 devolvió cambios a Antigravity. |
| 2–8 | Pendientes | — |

## Fase 1: tareas y ejecutor

Desde 2026-11-13 Antigravity implementa casi todo (ver `docs/tareas/README.md`); Claude Code
prepara tareas, revisa y resuelve cambios de base común.

| Tarea | Ejecutor | Estado |
|---|---|---|
| T17, T18, T19, T20 (O1) | Antigravity | revisión 1: cambios pedidos (T17 crítico; T18–T20 observaciones) |
| T21–T95 | Antigravity (salvo cambios de base común) | pendiente: archivos de tarea ya escritos en `docs/tareas/` |

## Pendientes y avisos

- **Modo autónomo disponible:** si Claude no está, Antigravity sigue el índice de `docs/tareas/README.md` con `AGENTS.md` §6, se autorrevisa con `docs/tareas/_revision.md` y registra cambios de base común en `docs/tareas/CAMBIOS_BASE.md`. Al volver, el orquestador revisa todo lo `entregada`.

- Primer commit pendiente de autorización.
- Borrar a mano `D:\proyectos portafolio\verify.log` (fuera del proyecto).
- Entorno: con el JDK 22 de Oracle, Maven necesita `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`; instalar Temurin 21 lo evita.
- Docker Desktop debe estar corriendo para las pruebas de integración (Testcontainers). Se ha detenido solo dos veces: desactivar "Resource Saver".
- Deuda marcada: `inventory` lee `products` por SQL hasta T25 (`TODO(T25)`); `security` debe migrar consultas de `stores`/`settings` a `StoreApi`/`SettingsApi` (T19/T21).

## Bitácora

| Fecha | Hito |
|---|---|
| 2026-11-12 | Plan y diseño aprobados (`docs/`). |
| 2026-11-13 | Fase 0 terminada: esquema completo, semilla, kernel, infraestructura, esqueleto Angular, CI. |
| 2026-11-13 | Definido el traspaso de tareas a Antigravity. |
| 2026-11-13 | Antigravity pasa a implementar casi todo; O1 de Fase 1 (T17–T20) asignada. |
| 2026-11-13 | Escritas las 75 tareas restantes (T21–T95) y el modo autónomo para Antigravity. |
| 2026-11-13 | O1 entregada por Antigravity; revisión 1 pide cambios (falsa alarma IPv6 en audit, validación de emisor JWT, pruebas sobre BD compartida). |
