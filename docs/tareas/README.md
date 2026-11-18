# Traspaso de tareas a Antigravity

Esta carpeta es el canal entre el orquestador (Claude Code) y los agentes de Google
Antigravity. **No hay integración automática:** los términos de Antigravity no permiten que
un agente de terceros invoque su CLI en planes de consumo, así que la persona usuaria es quien
asigna cada tarea en el Agent Manager de Antigravity.

## Flujo

```
pendiente ──► asignada ──► entregada ──► en-revision ──► aprobada
                 ▲                            │
                 └──────── cambios ◄──────────┘
```

| Paso | Quién | Qué hace |
|---|---|---|
| 1 | Orquestador | Crea `docs/tareas/T<n>.md` desde `_plantilla.md` cuando sus dependencias están terminadas, con estado `asignada`. |
| 2 | Persona usuaria | Abre Antigravity en `D:\proyectos portafolio\pos-minimarket`, crea un agente en el Agent Manager y le da el prompt de abajo. |
| 3 | Agente de Antigravity | Ejecuta la tarea siguiendo `AGENTS.md`, completa la sección "Entrega" y pone el estado en `entregada`. |
| 4 | Persona usuaria | Avisa al orquestador ("T30 entregada"). |
| 5 | Orquestador | Pone `en-revision` y lanza al revisor. Si hay hallazgos, los agrega a la tarea y la devuelve como `cambios` → `asignada`; si no, `aprobada`. Máximo dos ciclos, como en `CLAUDE.md`. |

**Prompt para el agente de Antigravity** (cambia el número de tarea):

> Ejecuta la tarea `docs/tareas/T30.md` siguiendo las reglas de `AGENTS.md`. Toca solo los
> archivos permitidos, verifica y completa la sección "Entrega".

## Reparto (desde 2026-11-13)

Para ahorrar el uso semanal de Claude, **Antigravity implementa casi todas las tareas**
(backend, frontend y regeneración del contrato). Claude Code queda como orquestador:

- **Antigravity:** implementación de las tareas de cada oleada, incluidas las de "Regenerar
  contrato" (con `frontend/openapi/**` y `frontend/src/app/api/**` en sus archivos permitidos).
- **Claude Code:** preparar los archivos de tarea, revisar cada oleada con el `revisor`
  (más barato que implementar), resolver los cambios de base común (`shared/**`, `core/**`,
  pom, `package.json`, migraciones) cuando una tarea los pida, actualizar `docs/ESTADO.md` y
  hacer los commits que se autoricen.
- Las tareas de una misma oleada se pueden ejecutar en paralelo en el Agent Manager (un agente
  por tarea): sus archivos permitidos nunca se solapan. No lances una tarea antes de que sus
  dependencias estén `aprobada` (o `entregada` en modo autónomo).
- El reparto concreto de cada oleada queda en `docs/ESTADO.md`.

## Modo autónomo (sin Claude)

Si el uso semanal de Claude se agota, Antigravity puede seguir solo con `AGENTS.md` §6:

1. Sigue el índice de abajo en orden (fase → oleada). Antes de empezar una tarea, cambia su
   estado a `asignada`; al terminar, a `entregada`.
2. Prompt para cada tarea: *"Ejecuta la tarea `docs/tareas/T<n>.md` siguiendo `AGENTS.md`
   (modo autónomo, §6). Toca solo los archivos permitidos, verifica y completa la Entrega."*
3. Al terminar una oleada marcada con 🔍, lanza un agente revisor con el prompt de
   `_revision.md` y corrige los críticos e importantes antes de seguir.
4. Los cambios de base común inevitables se registran en `CAMBIOS_BASE.md`.
5. Cuando Claude vuelva: *"continúa"*. El orquestador revisa todo lo `entregada`,
   consolida `docs/ESTADO.md` y marca `aprobada`.

Recomendación: haz un commit al cerrar cada fase para tener puntos de restauración.

## Índice (orden de ejecución)

Ejecuta por fase y oleada, de arriba hacia abajo. Las tareas de una misma oleada van en paralelo. "🔍" marca las oleadas que cierran un bloque: al terminarlas, revisión con `_revision.md`. La columna Estado la actualiza quien ejecuta la tarea.


### Fase 1

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T17](T17.md) Módulo audit: AuditService (hash encadenado, advisory lock), GET /audi… | backend | entregada |
| O1 | [T18](T18.md) Organization y Files. Organization: company, stores (publica StoreCrea… | backend | entregada |
| O1 | [T19](T19.md) Auth núcleo: SecurityConfig, JWT HS256 (Nimbus), login con BCrypt (sin… | backend | entregada |
| O1 | [T20](T20.md) StockApi.apply (unidad, UPDATE condicional, consolidación y orden, kar… | backend | entregada |
| O2 | [T21](T21.md) GET /auth/me/stores, implementación de StoreAccessValidator (apoyada e… | backend | entregada |
| O2 | [T23](T23.md) Catalog A: categorías, marcas, impuestos | backend | entregada |
| O3 🔍 | [T22](T22.md) Usuarios, roles, permisos y límites; RoleLimitApi (devuelve RoleLimit,… | backend | entregada |
| O3 🔍 | [T25](T25.md) Catalog B: productos, barcodes, packs, impuestos del producto, precio … | backend | entregada |
| O4 | [T24](T24.md) Consultas de inventario: GET /stock, /stock/{id}, alerts, kardex, limi… | backend | entregada |
| O4 | [T26](T26.md) Login por PIN (scope POS, POST /auth/pin-login), PUT /auth/me/pin y ov… | backend | entregada |
| O4 | [T28](T28.md) Balanza: configs, implementación de resolveCode y de la búsqueda trigr… | backend | entregada |
| O5 | [T27](T27.md) Regenerar contrato (1) | contrato | entregada |
| O5 | [T29](T29.md) Caja backend: abrir, current, movimientos (límite y override), summary… | backend | entregada |
| O5 | [T32](T32.md) Pricing, quote y endpoints POS: PricingService (impuestos, descuentos,… | backend | entregada |
| O6 | [T30](T30.md) Frontend auth: login, selección de local (GET /auth/me/stores) y de te… | frontend | entregada |
| O6 | [T31](T31.md) Frontend seguridad: usuarios, roles y permisos, límites | frontend | entregada |
| O6 | [T33](T33.md) PaymentGateway simulado y /pos/card-payments (J-02) | backend | entregada |
| O6 | [T35](T35.md) Regenerar contrato (2) | contrato | entregada |
| O7 🔍 | [T34](T34.md) Frontend settings: empresa, locales, terminales, settings | frontend | entregada |
| O7 🔍 | [T36](T36.md) Crear venta: POST /sales (idempotente, pack → base, stock, pagos y red… | backend | entregada |
| O7 🔍 | [T37](T37.md) Ventas suspendidas y su job de expiración | backend | entregada |
| O7 🔍 | [T38](T38.md) Frontend catálogo: categorías, marcas, impuestos, balanza | frontend | entregada |
| O8 | [T39](T39.md) Frontend productos: lista y formulario (códigos, pack, impuestos, prec… | frontend | entregada |
| O8 | [T40](T40.md) Regenerar contrato (3) | contrato | entregada |
| O8 | [T41](T41.md) Frontend inventario: stock, alertas, kardex, límites | frontend | entregada |
| O8 | [T43](T43.md) Semilla demo F1: 2 locales, 3 terminales (L01: CAJA-1 y CAJA-2; L02: C… | backend | entregada |
| O9 | [T42](T42.md) Frontend caja: abrir, movimientos con override, cierre con arqueo y de… | frontend | entregada |
| O9 | [T44](T44.md) Frontend POS (parte 1): PosCartStore (signals + localStorage), PosKeyb… | frontend | entregada |
| O9 | [T45](T45.md) Flujo de venta de punta a punta (backend): SaleFlowIT (login → abrir →… | backend | entregada |
| O9 | [T46](T46.md) Frontend auditoría | frontend | entregada |
| O10 🔍 | [T47](T47.md) Frontend POS (parte 2): pagos mixtos (vuelto, redondeo, tarjeta), susp… | frontend | entregada |

### Fase 2

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T48](T48.md) Lotes en StockApi: FEFO con FOR UPDATE; implementación definitiva de L… | backend | entregada |
| O1 | [T49](T49.md) Ajustes con aprobación | backend | entregada |
| O1 | [T50](T50.md) Tomas de inventario (ciclo completo) | backend | entregada |
| O1 | [T51](T51.md) Transferencias, con StockApi.inTransitInbound (implementación real, A.… | backend | entregada |
| O2 🔍 | [T52](T52.md) Lotes, vencimientos y FEFO (endpoints) | backend | entregada |
| O3 | [T53](T53.md) Regenerar contrato (4) | contrato | entregada |
| O4 🔍 | [T54](T54.md) Frontend ajustes y lotes/vencimientos | frontend | entregada |
| O4 🔍 | [T55](T55.md) Frontend tomas (conteo masivo navegable con teclado) | frontend | entregada |
| O4 🔍 | [T56](T56.md) Frontend transferencias | frontend | entregada |

### Fase 3

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T57](T57.md) Proveedores y producto-proveedor (incluye ProductSupplierApi.upsert) | backend | entregada |
| O2 | [T58](T58.md) Órdenes de compra: ciclo de estados, PDF, correo vía outbox (crea el E… | backend | entregada |
| O3 🔍 | [T59](T59.md) Recepciones: lotes, costo promedio (applyAverageCost, con qty_received… | backend | entregada |
| O4 | [T60](T60.md) Regenerar contrato (5) | contrato | entregada |
| O5 🔍 | [T61](T61.md) Frontend proveedores y producto-proveedor | frontend | entregada |
| O5 🔍 | [T62](T62.md) Frontend OC y recepciones | frontend | entregada |

### Fase 4

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T63](T63.md) Mermas (J-01): implementación de TransferDifferenceHandler (definida p… | backend | entregada |
| O1 | [T64](T64.md) Importación y exportación del catálogo (dry-run, confirmación transacc… | backend | entregada |
| O2 | [T65](T65.md) Regenerar contrato (6) | contrato | entregada |
| O3 🔍 | [T66](T66.md) Frontend mermas | frontend | entregada |
| O3 🔍 | [T67](T67.md) Frontend importación y exportación | frontend | entregada |

### Fase 5

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T68](T68.md) Promociones: CRUD, GET /pos/promotions/active (la ruta es de promotion… | backend | pendiente |
| O1 | [T69](T69.md) Clientes y cuenta corriente: CRUD de clientes (incluye activar y desac… | backend | pendiente |
| O1 | [T70](T70.md) Billing simulado: listener, ElectronicInvoiceProvider, folios, endpoin… | backend | pendiente |
| O2 | [T71](T71.md) Anulación y devoluciones: reembolsos con gateway y fiado, reingreso o … | backend | pendiente |
| O3 🔍 | [T72](T72.md) Venta con CUSTOMER_CREDIT (quita el rechazo de la fase 1), override CR… | backend | pendiente |
| O4 | [T73](T73.md) Regenerar contrato (7) | contrato | pendiente |
| O5 🔍 | [T74](T74.md) Frontend promociones | frontend | pendiente |
| O5 🔍 | [T75](T75.md) Frontend clientes y fiado | frontend | pendiente |
| O5 🔍 | [T76](T76.md) Frontend POS: anulación y devolución | frontend | pendiente |
| O5 🔍 | [T77](T77.md) Frontend POS: cliente, fiado y correo en la venta; documentos tributar… | frontend | pendiente |

### Fase 6

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T78](T78.md) Agregado daily_product_sales (job 02:00, recálculo de 3 días, had_stoc… | backend | pendiente |
| O1 | [T79](T79.md) ReplenishmentCalculator puro (C.3 a C.6) | backend | pendiente |
| O2 🔍 | [T80](T80.md) Parámetros, corridas, líneas y borradores de OC | backend | pendiente |
| O3 | [T81](T81.md) Regenerar contrato (8) | contrato | pendiente |
| O4 🔍 | [T82](T82.md) Frontend reabastecimiento | frontend | pendiente |

### Fase 7

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T83](T83.md) Dashboard | backend | pendiente |
| O1 | [T84](T84.md) Reportes de venta: ventas, cajero, medio de pago, ranking, sin rotació… | backend | pendiente |
| O1 | [T85](T85.md) Margen, valorización, compras, merma, diferencias de caja | backend | pendiente |
| O2 🔍 | [T86](T86.md) Endpoints /export xlsx y pdf genéricos para todos los reportes, con au… | backend | pendiente |
| O3 | [T87](T87.md) Regenerar contrato (9) | contrato | pendiente |
| O4 🔍 | [T88](T88.md) Frontend dashboard | frontend | pendiente |
| O4 🔍 | [T89](T89.md) Frontend reportes con filtros y export | frontend | pendiente |

### Fase 8

| Oleada | Tarea | Tipo | Estado |
|---|---|---|---|
| O1 | [T91](T91.md) Semilla demo completa: 90 días de ventas sintéticas (generador de perf… | backend | pendiente |
| O1 | [T92](T92.md) e2e de humo con Playwright: venta solo con teclado y cierre de caja | frontend | pendiente |
| O2 | [T90](T90.md) Pruebas de rendimiento: índices (EXPLAIN de reportes), carga de 50 ven… | backend | pendiente |
| O2 | [T93](T93.md) README de portafolio, docs finales, revisión de OpenAPI y capturas | backend | pendiente |
| O3 | [T94](T94.md) Revisión OWASP Top 10 (checklist) | backend | pendiente |
| O4 🔍 | [T95](T95.md) Endurecimiento: headers de seguridad y CSP en nginx, CORS estricto en … | backend | pendiente |
