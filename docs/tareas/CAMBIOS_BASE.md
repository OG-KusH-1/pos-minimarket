# Cambios de base común hechos por Antigravity

Registro obligatorio en modo autónomo (`AGENTS.md` §6). Cualquier cambio en
`backend/.../shared/**`, `frontend/src/app/{core,shared}/**`, `application*.yml`,
`backend/pom.xml`, `frontend/package.json` o una migración nueva va aquí. El orquestador los
revisa todos al volver.

| Fecha | Tarea | Archivo | Motivo | Qué cambió |
|---|---|---|---|---|
| 2026-11-13 | T21 | `organization/StoreApi.java`, `organization/application/StoreService.java` | Resolver `// TODO(T21)` en `AuthQueryRepository` evitando N+1 | Se agregó `StoreApi.findActiveByIds(Collection<Long> ids)` y su implementación en `StoreService` |
| 2026-11-13 | T22 | `security/RoleLimitApi.java`, `security/RoleLimit.java`, `security/RoleLimitCode.java` | Exponer la API pública de límites por rol en el paquete raíz de `security` para cumplir con Spring Modulith (`ModularityTest`) | Se crearon `RoleLimitApi`, `RoleLimit` (`Limited`/`Unlimited`) y `RoleLimitCode` en `cl.minimarket.pos.security` |
| 2026-11-13 | T26 | `security/SupervisorOverrideApi.java`, `security/OverrideAction.java`, `security/AuthorizationRef.java` | Exponer la API pública de overrides de supervisor en el paquete raíz de `security` para cumplir con Spring Modulith (`ModularityTest`) | Se crearon `SupervisorOverrideApi`, `OverrideAction` y `AuthorizationRef` en `cl.minimarket.pos.security` |
| 2026-11-14 | T30 | `frontend/src/app/core/layout/shell.ts`, `shell.html`, `shell.spec.ts` | Cumplir requisito L-06 de `docs/PLAN.md` (*"Agregar botón de cerrar sesión en el shell"*) | Se agregó botón `Cerrar sesión` en `mat-toolbar` (visible solo con sesión activa) que invoca `AuthService.logout()` y redirige a `/login`, junto con su prueba en `shell.spec.ts` |
| 2026-11-14 | T29 / T36 | `cash/CashSessionMetricsContributor.java`, `sales/application/sale/SaleCashFlowContributor.java`, `sales/application/sale/SaleJdbcRepository.java` | Eliminar SQL directo sobre `sales` y `customers` en `CashLookupJdbcRepository` mediante inversión de dependencias | Se agregó el puerto SPI `CashSessionMetricsContributor` en `cl.minimarket.pos.cash` y su implementación en `SaleCashFlowContributor` / `SaleJdbcRepository` |
| 2026-11-14 | T25 / T32 | `catalog/ProductCatalogApi.java`, `catalog/product/application/ProductCatalogApiImpl.java` | Eliminar SQL directo sobre tablas de `catalog` en `PosProductService` (`existsInactiveProductForCode`) | Se agregó `ProductCatalogApi.isInactiveCodeMatch(String code)` y su implementación en `ProductCatalogApiImpl` |
| 2026-11-14 | T48 | `backend/src/test/java/cl/minimarket/pos/inventory/StockConcurrencyIT.java` | En Fase 2 (`T48`), `StockApi.apply` implementa soporte real de lotes y ya no rechaza líneas con lote mediante stub de Fase 1 | Se actualizó `apply_lotDataForLotControlledProduct_*` para verificar la asignación real de lote y el saldo resultante en Fase 2 |
| 2026-11-18 | T49 | `catalog/ProductCatalogApi.java`, `catalog/product/application/ProductSnapshotMapper.java` | Eliminar SQL directo sobre `products` en `StockAdjustmentJdbcRepository.findProductsByIds` | Se agregó `Integer shelfLifeDays` a `ProductCatalogApi.ProductSnapshot` (con constructor de compatibilidad de 17 argumentos) y se pobló `p.shelf_life_days` en `ProductSnapshotMapper` |
