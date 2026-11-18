# API REST — POS Minimarket

> Parte del diseño de POS Minimarket. Las secciones conservan su letra (A–J) para que las referencias cruzadas funcionen entre documentos: A, C, E, H, I en [PLAN.md](PLAN.md) · B en [MODELO_DATOS.md](MODELO_DATOS.md) · D en [ROLES_PERMISOS.md](ROLES_PERMISOS.md) · F en [API.md](API.md) · G en [HISTORIAS_USUARIO.md](HISTORIAS_USUARIO.md).

## F. API REST

### F.1 Convenciones

- **Base path:** `/api/v1`. El versionado va por ruta: si hay un cambio incompatible se crea `/api/v2` y la versión anterior se mantiene una fase.
- **Autenticación:** `Authorization: Bearer <access>` (ADR-20). La cookie de refresh solo viaja a `/api/v1/auth/*`. Los endpoints públicos son solo `POST /auth/login` y `POST /auth/refresh`; este último exige además el header `X-Requested-With: XMLHttpRequest`.
- **Headers por tipo de endpoint:**
  - `X-Store-Id` es obligatorio por defecto en todo endpoint autenticado (ADR-12, RN-27). Los endpoints marcados **[G]** son globales y no lo requieren.
  - **[T]**: requiere `X-Terminal-Id`, que debe pertenecer al local.
  - **[I]**: requiere `Idempotency-Key` (ADR-14). Si falta o no es un UUID: 400 `VALIDATION_ERROR`.
  - **[O:ACCIÓN]**: admite `X-Override-Token` (ADR-19, D.4). Si falta y se necesita, la respuesta es 422 `OVERRIDE_REQUIRED` con `{action, context}`. **Regla general:** un endpoint que admite override responde 422 `OVERRIDE_REQUIRED` cuando falta el permiso o el límite de la acción que protege el override; la falta de otros permisos del endpoint da 403 `ACCESS_DENIED`. Con un token válido la operación se ejecuta.
- **Errores:** RFC 9457 (ADR-06, A.5). El catálogo `ErrorCode` lo crea **completo** la tarea T6, con el estado HTTP de la tabla de abajo:
  - Ya definidos en A.5: `VALIDATION_ERROR`, `NOT_FOUND`, `STOCK_INSUFFICIENT`, `CASH_SESSION_NOT_OPEN`, `DISCOUNT_LIMIT_EXCEEDED`, `OVERRIDE_REQUIRED`, `OVERRIDE_INVALID`, `IDEMPOTENCY_CONFLICT`, `CREDIT_LIMIT_EXCEEDED`, `STORE_ACCESS_DENIED`, `ACCOUNT_LOCKED`, `CONCURRENT_MODIFICATION`, `SEGREGATION_OF_DUTIES`.
  - Nuevos de la parte 2 (ver J-08): `INVALID_CREDENTIALS`, `PIN_LOCKED`, `TOKEN_INVALID`, `PASSWORD_POLICY`, `RATE_LIMITED`, `TERMINAL_INVALID`, `INVALID_STATE_TRANSITION`, `DUPLICATED_VALUE`, `RUT_INVALID`, `BARCODE_INVALID`, `CODE_NOT_FOUND`, `PRODUCT_INACTIVE`, `PRICE_NOT_SET`, `AGE_CONFIRMATION_REQUIRED`, `LOT_REQUIRED`, `RECEIPT_EXCEEDS_ORDER`, `CASH_SESSION_ALREADY_OPEN`, `CASH_OUT_EXCEEDS_AVAILABLE`, `PAYMENT_DECLINED`, `PAYMENT_MISMATCH`, `VOID_NOT_ALLOWED`, `RETURN_WINDOW_EXPIRED`, `RETURN_QUANTITY_EXCEEDED`, `CREDIT_NOT_ENABLED`, `PAYMENT_EXCEEDS_DEBT`, `SUSPENDED_LIMIT_REACHED`, `FILE_INVALID`, `IMPORT_INVALID`, `ENTITY_IN_USE`.
  - Nuevos de la revisión: `UNAUTHENTICATED` (falta o vence el access token), `ACCESS_DENIED` (el usuario autenticado no tiene el permiso), `IDEMPOTENCY_IN_PROGRESS` (la misma clave sigue en proceso), `OVERRIDE_LIMIT_EXCEEDED` (el supervisor existe y tiene el permiso, pero su límite no cubre el contexto).
  - Nuevos de la corrección de T6 (errores de protocolo y técnicos): `INTERNAL_ERROR`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`.
  - `DISCOUNT_LIMIT_EXCEEDED` cubre el descuento que deja una línea bajo $0 (RN-11) o que excede lo autorizado en el token usado; el descuento sobre el límite del rol sin token se informa con `OVERRIDE_REQUIRED`.
- **Estado HTTP por código (fuente de verdad; A.5 solo la resume):**

| HTTP | Códigos |
|---|---|
| 400 | `VALIDATION_ERROR` (incluye `Idempotency-Key` ausente o mal formada) |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `TOKEN_INVALID` |
| 403 | `ACCESS_DENIED`, `STORE_ACCESS_DENIED` |
| 404 | `NOT_FOUND`, `CODE_NOT_FOUND` |
| 409 | `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS`, `DUPLICATED_VALUE`, `ENTITY_IN_USE`, `CONCURRENT_MODIFICATION` |
| 422 | Reglas de negocio: `STOCK_INSUFFICIENT`, `CASH_SESSION_NOT_OPEN`, `CASH_SESSION_ALREADY_OPEN`, `CASH_OUT_EXCEEDS_AVAILABLE`, `DISCOUNT_LIMIT_EXCEEDED`, `OVERRIDE_REQUIRED`, `OVERRIDE_INVALID`, `OVERRIDE_LIMIT_EXCEEDED`, `SEGREGATION_OF_DUTIES`, `CREDIT_LIMIT_EXCEEDED`, `CREDIT_NOT_ENABLED`, `PAYMENT_EXCEEDS_DEBT`, `PAYMENT_DECLINED`, `PAYMENT_MISMATCH`, `ACCOUNT_LOCKED`, `PIN_LOCKED`, `PASSWORD_POLICY`, `TERMINAL_INVALID`, `RUT_INVALID`, `BARCODE_INVALID`, `PRODUCT_INACTIVE`, `PRICE_NOT_SET`, `AGE_CONFIRMATION_REQUIRED`, `LOT_REQUIRED`, `RECEIPT_EXCEEDS_ORDER`, `VOID_NOT_ALLOWED`, `RETURN_WINDOW_EXPIRED`, `RETURN_QUANTITY_EXCEEDED`, `SUSPENDED_LIMIT_REACHED`, `FILE_INVALID`, `IMPORT_INVALID` |
| 405 | `METHOD_NOT_ALLOWED` |
| 406 | `NOT_ACCEPTABLE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 429 | `RATE_LIMITED` |
| 500 | `INTERNAL_ERROR` (error no controlado; sin detalle interno ni stack trace) |
- **Paginación, filtros y orden:** según A.5. Todo `GET` de colección devuelve `PageResponse<…Summary|…Response>`. Los filtros se listan en "Notas" como `f:`.
- **Concurrencia de edición:** los `PUT` de entidades con `version` la exigen en el body. Si no coincide: 409 `CONCURRENT_MODIFICATION`.
- **Costos:** los campos marcados `*` salen `null` sin `catalog:cost:read` (nota de D.2). Excepción: el costo de merma y de ajuste es visible para quien tiene el permiso de aprobarlos.
- **OpenAPI:** springdoc publica en `/v3/api-docs` y `/swagger-ui` (solo perfiles dev y demo).
  - Un tag por módulo: Auth, Security, Audit, Organization, Files, Catalog, DataExchange, Purchasing, Inventory, Shrinkage, Promotions, Customers, Sales, Billing, Cash, Replenishment, Reporting.
  - Cada operación declara su permiso en la descripción (`Permiso: x`) y sus `ErrorCode` posibles.
  - El contrato se exporta a `frontend/openapi/openapi.json` con la prueba `OpenApiExportIT`.
- **Exportaciones:** `GET <reporte>/export?format=xlsx|pdf` acepta los mismos filtros que el reporte y responde con `Content-Disposition: attachment`. Exige `report:export:execute` además del permiso del reporte.

### F.2 Endpoints por módulo

Abreviaturas de error: STK=`STOCK_INSUFFICIENT`, VAL=`VALIDATION_ERROR`, ST=`INVALID_STATE_TRANSITION`, DUP=`DUPLICATED_VALUE`, SOD=`SEGREGATION_OF_DUTIES`.

**Auth** (todo [G], salvo donde se indica)
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| POST | /auth/login | público | LoginRequest | TokenResponse + cookie refresh | Rate limit (ADR-21); `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `RATE_LIMITED` |
| POST | /auth/refresh | cookie | — | TokenResponse | Rota el token (RN-32); `TOKEN_INVALID` |
| POST | /auth/logout | autenticado | — | 204 | Revoca la familia y borra la cookie |
| POST | /auth/pin-login | autenticado (sesión vigente ligada a esa terminal) | PinLoginRequest | TokenResponse (scope POS) | [T]; rate limit por IP y por usuario objetivo (ADR-21); `PIN_LOCKED`, `INVALID_CREDENTIALS`, `TERMINAL_INVALID`, `RATE_LIMITED` |
| POST | /auth/overrides | autenticado | OverrideRequest | OverrideResponse | Requiere `X-Store-Id` y [T] opcional; rate limit por IP y por usuario objetivo; los fallos con PASSWORD incrementan `failed_attempts`; `OVERRIDE_INVALID`, SOD, `PIN_LOCKED`, `ACCOUNT_LOCKED`, `RATE_LIMITED`, `OVERRIDE_LIMIT_EXCEEDED` |
| GET | /auth/me | autenticado | — | MeResponse | |
| GET | /auth/me/stores | autenticado | — | StoreSummary[] | Primer paso tras el login (D.4) |
| POST | /auth/session/terminal | autenticado | SessionTerminalRequest | 204 | Requiere `X-Store-Id`; liga el refresh token vigente a la terminal (`refresh_tokens.terminal_id`); `TERMINAL_INVALID`, `STORE_ACCESS_DENIED` |
| POST | /auth/me/password | autenticado | ChangePasswordRequest | 204 | `PASSWORD_POLICY`, `INVALID_CREDENTIALS`; revoca las otras sesiones |
| PUT | /auth/me/pin | autenticado | ChangePinRequest | 204 | Reinicia el contador de intentos del PIN |

**Security** ([G])
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /users | security:user:read | — | Page<UserSummary> | f: q, roleId, storeId, active, locked. El gerente ve solo usuarios de sus locales |
| GET | /users/{id} | security:user:read | — | UserResponse | |
| POST | /users | security:user:create | UserRequest | UserResponse + TemporaryPasswordResponse | Anti-escalada (nota ¹ de D.2 y D.4) → 403 `ACCESS_DENIED`; DUP |
| PUT | /users/{id} | security:user:update | UserRequest | UserResponse | Anti-escalada; nadie edita su propio registro (roles, locales, estado) → SOD (D.4) |
| POST | /users/{id}/disable · /enable | security:user:disable | — | 204 | Al desactivar se revocan sus tokens; no se desactiva al último ADMIN activo (`ENTITY_IN_USE`); anti-escalada |
| POST | /users/{id}/reset-password | security:user:reset-credentials | — | TemporaryPasswordResponse | Anti-escalada |
| POST | /users/{id}/reset-pin · /unlock | security:user:reset-credentials | — | 204 | Anti-escalada; `reset-pin` y `unlock` también levantan el bloqueo del PIN |
| GET | /users/{id}/sessions | security:session:revoke | — | SessionResponse[] | |
| DELETE | /users/{id}/sessions | security:session:revoke | — | 204 | Auditado (SESSIONS_REVOKED) |
| GET | /roles · /roles/{id} | security:role:read | — | Page<RoleResponse> / RoleResponse | |
| POST | /roles | security:role:manage | RoleRequest | RoleResponse | DUP; siembra todas las filas de `role_limits` del rol con 0 (fail-closed, D.3) |
| PUT | /roles/{id} | security:role:manage | RoleRequest | RoleResponse | `code` es inmutable |
| DELETE | /roles/{id} | security:role:manage | — | 204 | Si `system=true` o tiene usuarios → `ENTITY_IN_USE` |
| PUT | /roles/{id}/permissions | security:role:manage | RolePermissionsRequest | RoleResponse | Auditado (ROLE_PERMISSIONS_CHANGED) |
| GET | /permissions | security:role:read | — | PermissionResponse[] | |
| GET | /roles/{id}/limits | security:role:read | — | RoleLimitResponse[] | |
| PUT | /roles/{id}/limits | config:role-limit:manage | RoleLimitsRequest | RoleLimitResponse[] | |

**Audit** ([G])
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /audit-log | security:audit:read | — | Page<AuditLogResponse> | f: from, to, action, entityType, entityId, userId, storeId. El gerente ve solo sus locales |
| GET | /audit-log/{id} | security:audit:read | — | AuditLogResponse | |
| POST | /audit-log/verify | security:audit:verify | AuditVerifyRequest | AuditVerifyResponse | Recorre la cadena de hash (ADR-18) |

**Organization y Files**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /company | autenticado | — | CompanyResponse | [G] |
| PUT | /company | config:company:update | CompanyRequest | CompanyResponse | [G]; `RUT_INVALID`; auditado (COMPANY_UPDATED) |
| GET | /stores | autenticado | — | Page<StoreResponse> | [G]; solo los asignados, salvo all-access |
| GET | /stores/{id} | autenticado | — | StoreResponse | [G]; `STORE_ACCESS_DENIED` |
| POST | /stores | config:store:manage | StoreRequest | StoreResponse | [G]; publica `StoreCreatedEvent`: inventory crea las filas de `stock` (cero) para cada producto no PACK; auditado (STORE_CHANGED) |
| PUT | /stores/{id} | config:store:manage | StoreRequest | StoreResponse | [G]; auditado (STORE_CHANGED) |
| POST | /stores/{id}/deactivate · /activate | config:store:manage | — | 204 | [G]; al desactivar, si hay turnos abiertos → `ENTITY_IN_USE`; auditado (STORE_CHANGED) |
| GET | /stores/{storeId}/terminals | autenticado | — | TerminalResponse[] | [G]; valida que el usuario tenga acceso al local (`STORE_ACCESS_DENIED`); se usa en la selección de terminal al iniciar sesión (D.4) |
| POST | /stores/{storeId}/terminals | config:terminal:manage | TerminalRequest | TerminalResponse | DUP |
| PUT | /terminals/{id} | config:terminal:manage | TerminalRequest | TerminalResponse | |
| POST | /terminals/{id}/deactivate · /activate | config:terminal:manage | — | 204 | Al desactivar, si tiene turno abierto → `ENTITY_IN_USE` |
| GET | /settings | config:settings:read | — | SettingResponse[] | f: scope, prefix |
| GET | /settings/effective | autenticado | — | EffectiveSettingsResponse | Solo claves `pos.*`, `ticket.*`, `alerts.*`, `cash.*` |
| PUT | /settings/{key} | config:settings:update | SettingUpdateRequest | SettingResponse | El gerente solo puede usar scope STORE (nota ² de D.2); VAL |
| POST | /files | según la categoría (ver nota) | multipart: file, category | FileResponse | PRODUCT_IMAGE→catalog:product:update; SHRINKAGE_EVIDENCE→shrinkage:record:create; COMPANY_LOGO→config:company:update; IMPORT→catalog:product:import; `FILE_INVALID` |
| GET | /files/{id}/content | según la categoría | — | binario | PRODUCT_IMAGE y COMPANY_LOGO: cualquier usuario autenticado; SHRINKAGE_EVIDENCE: shrinkage:record:read |

**Catalog**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /categories | catalog:product:read | — | CategoryResponse[] | [G]; `?tree=true` devuelve el árbol |
| POST | /categories | catalog:category:manage | CategoryRequest | CategoryResponse | [G]; DUP; sin un tercer nivel → VAL |
| PUT | /categories/{id} | catalog:category:manage | CategoryRequest | CategoryResponse | [G] |
| POST | /categories/{id}/deactivate · /activate | catalog:category:manage | — | 204 | [G]; al desactivar, con productos activos → `ENTITY_IN_USE` |
| GET | /brands | catalog:product:read | — | Page<BrandResponse> | [G]; f: q, active |
| POST | /brands | catalog:brand:manage | BrandRequest | BrandResponse | [G] |
| PUT | /brands/{id} | catalog:brand:manage | BrandRequest | BrandResponse | [G] |
| POST | /brands/{id}/deactivate · /activate | catalog:brand:manage | — | 204 | [G] |
| GET | /taxes | catalog:product:read | — | TaxResponse[] | [G] |
| POST | /taxes | config:tax:manage | TaxRequest | TaxResponse | [G]; auditado (TAX_CHANGED) |
| PUT | /taxes/{id} | config:tax:manage | TaxRequest | TaxResponse | [G] |
| GET | /products | catalog:product:read | — | Page<ProductSummary> | [G]; f: q (nombre, SKU o código), categoryId (incluye subcategorías), brandId, saleType, active, restricted, lotControlled. El listado por proveedor es `GET /suppliers/{id}/products` (purchasing) |
| GET | /products/{id} | catalog:product:read | — | ProductResponse | [G] |
| POST | /products | catalog:product:create | ProductRequest | ProductResponse | [G]; DUP (SKU, código, atajo), `BARCODE_INVALID`; publica `ProductCreatedEvent` (inventory crea stock en cada local). Apartarse del IVA por defecto (`vatExempt=true` o `taxIds` distintos) exige además `config:tax:manage` → 403 `ACCESS_DENIED` |
| PUT | /products/{id} | catalog:product:update | ProductRequest | ProductResponse | [G]; ignora precio, costo, `vatExempt`, taxIds, barcodes y pack. Cambiar `lotControlled` pasa por `LotControlGuard` (RN-04) |
| POST | /products/{id}/deactivate · /activate | catalog:product:deactivate | — | 204 | [G] |
| PUT | /products/{id}/price | catalog:product:update-price | PriceChangeRequest | ProductResponse | [G]; RN-35 |
| PUT | /products/{id}/cost | catalog:cost:update | CostChangeRequest | ProductResponse | [G]; COST_MANUAL |
| GET | /products/{id}/price-history | catalog:product:read | — | Page<PriceHistoryResponse> | [G]; costos* |
| POST | /products/{id}/barcodes | catalog:product:update | BarcodeRequest | BarcodeResponse | [G]; DUP, `BARCODE_INVALID` |
| DELETE | /products/{id}/barcodes/{bid} | catalog:product:update | — | 204 | [G] |
| PUT | /products/{id}/barcodes/{bid}/primary | catalog:product:update | — | 204 | [G] |
| PUT | /products/{id}/pack | catalog:product:update | PackRequest | PackResponse | [G]; solo si `saleType=PACK` y no tiene ventas |
| PUT | /products/{id}/taxes | config:tax:manage | ProductTaxesRequest | TaxResponse[] | [G]; única vía para cambiar `vatExempt` y `taxIds`; `vatExempt=true` exige que `taxIds` no incluya el IVA; auditado (PRODUCT_TAXES_CHANGED) |
| PUT | /products/{id}/image | catalog:product:update | ProductImageRequest | ProductResponse | [G] |
| DELETE | /products/{id}/image | catalog:product:update | — | 204 | [G] |
| GET | /scale-barcode-configs | catalog:product:read | — | ScaleBarcodeConfigResponse[] | [G] |
| POST | /scale-barcode-configs | config:scale-barcode:manage | ScaleBarcodeConfigRequest | ScaleBarcodeConfigResponse | [G]; prefijo duplicado → DUP |
| PUT | /scale-barcode-configs/{id} | config:scale-barcode:manage | ScaleBarcodeConfigRequest | ScaleBarcodeConfigResponse | [G] |
| POST | /catalog/imports | catalog:product:import | multipart: file, importType, dryRun | ImportJobResponse | [G]; `dryRun=true` solo valida; `IMPORT_INVALID`, `FILE_INVALID` |
| GET | /catalog/imports/{id} | catalog:product:import | — | ImportJobResponse | [G] |
| POST | /catalog/imports/{id}/confirm | catalog:product:import | — | ImportJobResponse | [G]; solo si está VALIDATED y sin errores; aplica todo en una transacción; INITIAL_STOCK usa el local activo |
| GET | /catalog/imports/templates/{importType} | catalog:product:import | `?format=csv\|xlsx` | binario | [G] |
| GET | /products/export | catalog:product:export | filtros de /products + `format=csv\|xlsx` | binario | [G]; auditado (DATA_EXPORTED); costos* |

Las filas `/catalog/imports*` y `/products/export` pertenecen al módulo `dataexchange` (tag DataExchange), no a catalog; las rutas no cambian. Los endpoints `/pos/products/search` y `/pos/products/resolve` pertenecen al módulo `sales` (ver Sales): catalog solo expone `ProductCatalogApi.resolveCode` y la búsqueda trigram, y el `stockOnHand` se compone en sales con `StockApi.onHand`.

**Purchasing**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /suppliers | purchasing:supplier:read | — | Page<SupplierSummary> | [G]; f: q (RUT o nombre), active |
| GET | /suppliers/{id} | purchasing:supplier:read | — | SupplierResponse | [G] |
| POST | /suppliers | purchasing:supplier:manage | SupplierRequest | SupplierResponse | [G]; `RUT_INVALID`, DUP |
| PUT | /suppliers/{id} | purchasing:supplier:manage | SupplierRequest | SupplierResponse | [G] |
| POST | /suppliers/{id}/deactivate · /activate | purchasing:supplier:manage | — | 204 | [G] |
| GET | /suppliers/{id}/products | purchasing:supplier:read | — | Page<ProductSupplierResponse> | [G]; costos* |
| GET | /products/{id}/suppliers | purchasing:supplier:read | — | ProductSupplierResponse[] | [G]; costos* |
| POST | /product-suppliers | purchasing:product-supplier:manage | ProductSupplierRequest | ProductSupplierResponse | [G]; DUP; un PACK → VAL; auditado (PRODUCT_SUPPLIER_CHANGED) |
| PUT | /product-suppliers/{id} | purchasing:product-supplier:manage | ProductSupplierRequest | ProductSupplierResponse | [G]; auditado (PRODUCT_SUPPLIER_CHANGED) |
| POST | /product-suppliers/{id}/deactivate · /activate | purchasing:product-supplier:manage | — | 204 | [G]; auditado (PRODUCT_SUPPLIER_CHANGED) |
| PUT | /product-suppliers/{id}/preferred | purchasing:product-supplier:manage | — | 204 | [G]; desmarca al preferido anterior; auditado (PRODUCT_SUPPLIER_CHANGED) |
| GET | /purchase-orders | purchasing:order:read | — | Page<PurchaseOrderSummary> | f: status, supplierId, origin, from, to, q (número); costos* |
| GET | /purchase-orders/{id} | purchasing:order:read | — | PurchaseOrderResponse | costos* |
| POST | /purchase-orders | purchasing:order:create | PurchaseOrderRequest | PurchaseOrderResponse | Estado DRAFT |
| PUT | /purchase-orders/{id} | purchasing:order:create | PurchaseOrderRequest | PurchaseOrderResponse | Solo en DRAFT (RN-18) → ST |
| POST | /purchase-orders/{id}/send | purchasing:order:send | SendPurchaseOrderRequest | PurchaseOrderResponse | DRAFT→SENT; con `emailToSupplier` encola el PDF |
| POST | /purchase-orders/{id}/cancel | purchasing:order:cancel | CancelRequest | PurchaseOrderResponse | Si tiene recepción confirmada → ST |
| GET | /purchase-orders/{id}/pdf | purchasing:order:read | — | application/pdf | Plantilla Thymeleaf (ADR-07) |
| GET | /purchase-orders/{id}/receipt-draft | purchasing:receipt:create | — | GoodsReceiptRequest | Prellena lo pendiente de recibir |
| GET | /goods-receipts | purchasing:order:read | — | Page<GoodsReceiptSummary> | f: status, supplierId, purchaseOrderId, from, to; costos* |
| GET | /goods-receipts/{id} | purchasing:order:read | — | GoodsReceiptResponse | costos* |
| POST | /goods-receipts | purchasing:receipt:create (+ purchasing:receipt:without-order si no hay OC) | GoodsReceiptRequest | GoodsReceiptResponse | Estado DRAFT; OC no SENT ni PARTIALLY_RECEIVED → ST |
| PUT | /goods-receipts/{id} | purchasing:receipt:create | GoodsReceiptRequest | GoodsReceiptResponse | Solo DRAFT |
| POST | /goods-receipts/{id}/confirm | purchasing:receipt:confirm | — | GoodsReceiptResponse | [I]; mueve stock, crea lotes, recalcula el costo promedio (RN-16, RN-17); `LOT_REQUIRED`, `RECEIPT_EXCEEDS_ORDER` (salvo `purchasing:receipt:over-receive`, J-04); SOD si la diferencia supera 10 % (D.4); se audita GOODS_RECEIPT_CONFIRMED con la marca de sobre-recepción o de recepción sin OC |
| POST | /goods-receipts/{id}/cancel | purchasing:receipt:create | CancelRequest | 204 | Solo DRAFT |

**Inventory**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /stock | inventory:stock:read | — | Page<StockResponse> | f: q, categoryId, status (BELOW_MIN, ZERO, ABOVE_MAX), lotControlled. `valuation`* |
| GET | /stock/{productId} | inventory:stock:read | — | StockByStoreResponse | Locales asignados |
| PUT | /stock/{productId}/limits | inventory:stock-limits:update | StockLimitsRequest | StockResponse | RN-34 |
| GET | /stock/alerts | inventory:stock:read | — | StockAlertResponse | |
| GET | /kardex | inventory:kardex:read | — | Page<StockMovementResponse> | f: productId (obligatorio), from, to, movementType, lotId |
| GET | /stock-adjustments | inventory:adjustment:create | — | Page<StockAdjustmentResponse> | f: status, from, to |
| GET | /stock-adjustments/{id} | inventory:adjustment:create | — | StockAdjustmentResponse | |
| POST | /stock-adjustments | inventory:adjustment:create | StockAdjustmentRequest | StockAdjustmentResponse | [I]; estado PENDING_APPROVAL |
| POST | /stock-adjustments/{id}/approve | inventory:adjustment:approve | — | StockAdjustmentResponse | [O:STOCK_ADJUSTMENT_APPROVAL]; límite ADJUSTMENT_APPROVE_MAX_AMOUNT; SOD (RN-22); STK |
| POST | /stock-adjustments/{id}/reject | inventory:adjustment:approve | RejectRequest | StockAdjustmentResponse | |
| GET | /inventory-counts | inventory:count:execute | — | Page<InventoryCountResponse> | f: status, countType |
| GET | /inventory-counts/{id} | inventory:count:execute | — | InventoryCountResponse | |
| POST | /inventory-counts | inventory:count:manage | InventoryCountRequest | InventoryCountResponse | Genera las líneas (FULL: todos los productos con stock; CYCLIC: la categoría y sus hijas); si ya hay una toma abierta en conflicto → DUP |
| GET | /inventory-counts/{id}/lines | inventory:count:execute | — | Page<CountLineResponse> | f: counted (bool), q. Si `blind`, oculta `theoreticalQty` a quien no tiene inventory:count:approve |
| PUT | /inventory-counts/{id}/lines | inventory:count:execute | CountLinesRequest | CountLineResponse[] | Solo OPEN; guarda `theoretical_qty` = stock en ese momento |
| POST | /inventory-counts/{id}/submit | inventory:count:manage | — | InventoryCountResponse | Exige todas las líneas contadas → VAL |
| POST | /inventory-counts/{id}/approve | inventory:count:approve | — | InventoryCountResponse | Aplica los deltas (RN-20); SOD; STK por línea |
| POST | /inventory-counts/{id}/reject | inventory:count:approve | RejectRequest | InventoryCountResponse | Vuelve a OPEN para recontar |
| POST | /inventory-counts/{id}/cancel | inventory:count:manage | CancelRequest | 204 | |
| GET | /stock-transfers | inventory:transfer:read | — | Page<StockTransferResponse> | f: direction (in/out), status, from, to |
| GET | /stock-transfers/{id} | inventory:transfer:read | — | StockTransferResponse | |
| POST | /stock-transfers | inventory:transfer:send | StockTransferRequest | StockTransferResponse | El origen es el local activo; queda en DRAFT |
| PUT | /stock-transfers/{id} | inventory:transfer:send | StockTransferRequest | StockTransferResponse | Solo DRAFT |
| POST | /stock-transfers/{id}/send | inventory:transfer:send | — | StockTransferResponse | [I]; TRANSFER_OUT, FEFO si no viene lote; STK |
| POST | /stock-transfers/{id}/receive | inventory:transfer:receive | TransferReceiveRequest | StockTransferResponse | [I]; el local activo debe ser el destino; RN-19 |
| POST | /stock-transfers/{id}/cancel | inventory:transfer:cancel | CancelRequest | 204 | Solo DRAFT |
| GET | /lots | inventory:stock:read | — | Page<LotResponse> | f: productId, expiringWithinDays, withStock |
| PUT | /lots/{id} | inventory:lot:update | LotUpdateRequest | LotResponse | Auditado (LOT_UPDATED) |
| GET | /expiry-alerts | inventory:expiry:read | — | Page<ExpiryAlertResponse> | `?days=` (por defecto `alerts.expiry.days_ahead`) |
| GET | /lots/fefo | inventory:stock:read | `?productId&quantity` | FefoSuggestionResponse | |

**Shrinkage**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /shrinkages | shrinkage:record:read | — | Page<ShrinkageResponse> | f: status, shrinkageType, productId, from, to |
| GET | /shrinkages/{id} | shrinkage:record:read | — | ShrinkageResponse | |
| POST | /shrinkages | shrinkage:record:create | ShrinkageRequest | ShrinkageResponse | [I]; se aprueba sola si cabe en SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT (RN-21, J-01); `LOT_REQUIRED`, STK |
| POST | /shrinkages/{id}/approve | shrinkage:record:approve | — | ShrinkageResponse | [O:SHRINKAGE_APPROVAL]; límite SHRINKAGE_APPROVE_MAX_AMOUNT; SOD; STK |
| POST | /shrinkages/{id}/reject | shrinkage:record:approve | RejectRequest | ShrinkageResponse | |

**Promotions**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /promotions | promotions:promotion:read | — | Page<PromotionResponse> | [G]; f: active, type, vigentAt, productId |
| GET | /promotions/{id} | promotions:promotion:read | — | PromotionResponse | [G] |
| POST | /promotions | promotions:promotion:manage | PromotionRequest | PromotionResponse | [G]; CK de B.10 → VAL |
| PUT | /promotions/{id} | promotions:promotion:manage | PromotionRequest | PromotionResponse | [G]; auditado (PROMOTION_CHANGED) |
| POST | /promotions/{id}/deactivate · /activate | promotions:promotion:manage | — | 204 | [G]; auditado (PROMOTION_CHANGED) |
| GET | /pos/promotions/active | sales:pos:operate | — | ActivePromotionResponse[] | Vigentes en el local y la hora actuales |

**Customers**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /customers | customers:customer:read | — | Page<CustomerResponse> | [G]; f: q (RUT o nombre), withDebt, creditEnabled |
| GET | /customers/{id} | customers:customer:read | — | CustomerResponse | [G] |
| POST | /customers | customers:customer:manage | CustomerRequest | CustomerResponse | [G]; `RUT_INVALID`, DUP |
| PUT | /customers/{id} | customers:customer:manage | CustomerRequest | CustomerResponse | [G] |
| POST | /customers/{id}/deactivate · /activate | customers:customer:manage | — | 204 | [G]; al desactivar, con deuda mayor que 0 → `ENTITY_IN_USE` |
| PUT | /customers/{id}/credit | customers:credit:manage-limit | CreditSettingsRequest | CustomerResponse | [G]; límite CREDIT_LIMIT_MAX_ASSIGN → `CREDIT_LIMIT_EXCEEDED`; auditado |
| GET | /customers/{id}/account | customers:customer:read | — | AccountStatementResponse | f: from, to |
| POST | /customers/{id}/payments | customers:credit:payment | CustomerPaymentRequest | AccountMovementResponse | [I], [T] si se paga en efectivo (exige turno abierto); solo CASH o TRANSFER; `PAYMENT_EXCEEDS_DEBT` |
| POST | /customers/{id}/adjustments | customers:credit:manage-limit | CustomerAdjustmentRequest | AccountMovementResponse | [I]; `abs(amount)` ≤ CREDIT_ADJUSTMENT_MAX_AMOUNT (D.3), si no `CREDIT_LIMIT_EXCEEDED`; auditado (CUSTOMER_ACCOUNT_ADJUSTED) |

**Sales** (todos los de POS son [T]). Alcance "P": para el usuario cuyo `sales:sale:read` es P (cajero), `GET /sales*`, `GET /sales/{id}/ticket` y `GET /sales/{id}/returnable` filtran por `cashier_user_id` propio; una venta ajena responde 404 `NOT_FOUND`. El alcance P también aplica a `GET /sale-returns*`: el cajero ve solo las devoluciones de sus ventas (por el `cashier_user_id` de la venta); una devolución ajena responde 404 `NOT_FOUND`.
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /pos/products/search | sales:pos:operate | `?q=&limit≤20` | PosProductResult[] | Módulo sales (paquete `sales/pos`). Busca por nombre (trigram), SKU, atajo o prefijo de código con `ProductCatalogApi`; solo activos; `stockOnHand` con `StockApi.onHand` |
| GET | /pos/products/resolve | sales:pos:operate | `?code=` | ScanResultResponse | Módulo sales. Busca por código, luego balanza (B.3, `ProductCatalogApi.resolveCode`) y luego atajo; `stockOnHand` con `StockApi.onHand`; `CODE_NOT_FOUND`, `PRODUCT_INACTIVE` |
| POST | /pos/cart/quote | sales:pos:operate | CartQuoteRequest | CartQuoteResponse | No persiste nada; aplica promociones, impuestos, prorrateo y redondeo (A.5); informa `requiredOverrides` |
| POST | /sales | sales:pos:operate (+ customers:credit:sell si hay CUSTOMER_CREDIT) | CreateSaleRequest | SaleResponse | [I][T][O:DISCOUNT_ITEM_OVER_LIMIT, DISCOUNT_TOTAL_OVER_LIMIT, CREDIT_OVER_LIMIT]. Se aceptan varios tokens en `X-Override-Token` separados por coma. Errores: `CASH_SESSION_NOT_OPEN`, STK, `PRODUCT_INACTIVE`, `PRICE_NOT_SET`, `AGE_CONFIRMATION_REQUIRED`, `DISCOUNT_LIMIT_EXCEEDED`, `PAYMENT_MISMATCH`, `CREDIT_NOT_ENABLED`, `CREDIT_LIMIT_EXCEEDED`, `IDEMPOTENCY_CONFLICT` |
| GET | /sales | sales:sale:read | — | Page<SaleSummary> | f: from, to, cashierId, terminalId, status, paymentMethod, customerId, q (número). El cajero ve solo las propias |
| GET | /sales/{id} · /sales/by-number/{number} | sales:sale:read | — | SaleResponse | |
| GET | /sales/{id}/ticket | sales:pos:operate | — | TicketResponse | La primera impresión es libre. El documento tributario no viene aquí: el frontend lo obtiene con `GET /sales/{id}/tax-document` (billing) |
| POST | /sales/{id}/reprint | sales:receipt:reprint | — | TicketResponse | [O:RECEIPT_REPRINT]; auditado |
| POST | /sales/{id}/email | sales:pos:operate | EmailTicketRequest | 202 | Encola en la outbox |
| GET | /sales/{id}/returnable | sales:pos:operate | — | ReturnableLinesResponse | |
| POST | /sales/{id}/void | sales:sale:void | VoidSaleRequest | SaleReturnResponse | [I][T][O:SALE_VOID]; RN-12 → `VOID_NOT_ALLOWED`; ya anulada → ST |
| POST | /sales/{id}/returns | sales:return:create | SaleReturnRequest | SaleReturnResponse | [I][T][O:SALE_RETURN]; límite RETURN_MAX_AMOUNT; `RETURN_WINDOW_EXPIRED`, `RETURN_QUANTITY_EXCEEDED`, `CASH_SESSION_NOT_OPEN` |
| GET | /sale-returns | sales:sale:read | — | Page<SaleReturnResponse> | f: from, to, returnType, saleId |
| GET | /sale-returns/{id} | sales:sale:read | — | SaleReturnResponse | |
| GET | /pos/suspended-sales | sales:pos:operate | — | SuspendedSaleResponse[] | De la terminal |
| POST | /pos/suspended-sales | sales:pos:operate | SuspendSaleRequest | SuspendedSaleResponse | `SUSPENDED_LIMIT_REACHED` |
| POST | /pos/suspended-sales/{id}/resume | sales:pos:operate | — | SuspendedSaleResponse | Devuelve el carrito y borra la fila |
| DELETE | /pos/suspended-sales/{id} | sales:pos:operate (propia) o sales:suspended:delete-others | — | 204 | [O:SUSPENDED_SALE_DELETE] |
| POST | /pos/card-payments | sales:pos:operate | CardAuthorizationRequest | CardAuthorizationResponse | [I][T]; usa `PaymentGateway` simulado (J-02); `PAYMENT_DECLINED` |
| POST | /pos/card-payments/{id}/void | sales:pos:operate | — | CardAuthorizationResponse | Solo si no se usó en una venta |

**Billing**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /tax-documents | sales:tax-document:read | — | Page<TaxDocumentResponse> | f: status, documentType, from, to, folio |
| GET | /tax-documents/{id} | sales:tax-document:read | — | TaxDocumentResponse | |
| POST | /tax-documents/{id}/retry | sales:tax-document:retry | — | TaxDocumentResponse | Solo en ERROR o REJECTED |
| GET | /sales/{id}/tax-document | sales:pos:operate | — | TaxDocumentResponse \| 204 | Dueño: billing (sales no depende de billing). Devuelve el documento de la venta para el ticket; 204 si aún no se emitió (emisión asíncrona). Alcance P: billing valida la propiedad con `SaleLookupApi.cashierOf` |
| GET | /sale-returns/{id}/tax-document | sales:sale:read | — | TaxDocumentResponse \| 204 | Dueño: billing. Nota de crédito de la anulación o devolución; 204 si aún no se emitió |

**Cash**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| POST | /cash-sessions | cash:session:open | OpenCashSessionRequest | CashSessionResponse | [I][T]; `CASH_SESSION_ALREADY_OPEN` (RN-10) |
| GET | /cash-sessions/current | cash:session:open | — | CashSessionResponse \| 204 | [T] |
| GET | /cash-sessions | cash:session:read | — | Page<CashSessionResponse> | f: from, to, cashierId, terminalId, status, withDifference. El cajero ve solo las propias |
| GET | /cash-sessions/{id} | cash:session:read | — | CashSessionResponse | |
| GET | /cash-sessions/{id}/summary | cash:session:read | — | CashSessionSummaryResponse | Total esperado según B.8 |
| POST | /cash-sessions/{id}/movements | cash:movement:cash-in o cash:movement:cash-out (según tipo) | CashMovementRequest | CashMovementResponse | [I][O:CASH_OUT_OVER_LIMIT]; RN-24 → `CASH_OUT_EXCEEDS_AVAILABLE` |
| GET | /cash-sessions/{id}/movements | cash:session:read | — | CashMovementResponse[] | |
| POST | /cash-sessions/{id}/close | cash:session:close (propio) o cash:session:close-others | CloseCashSessionRequest | CashSessionResponse | [I][O:CASH_DIFFERENCE_APPROVAL]; si la diferencia supera la tolerancia y no hay token, cierra igual con `requiresDifferenceApproval=true` |
| POST | /cash-sessions/{id}/approve-difference | cash:difference:approve | — | CashSessionResponse | SOD (RN-23) |
| GET | /cash-sessions/{id}/closing-receipt | cash:session:read | `?format=pdf\|json` | PDF o CashClosingReceiptResponse | |

**Replenishment**
| Método | Ruta | Permiso | Request | Response | Notas |
|---|---|---|---|---|---|
| GET | /replenishment/params | replenishment:run:read | — | ReplenishmentParamsResponse[] | f: scope |
| PUT | /replenishment/params | replenishment:params:manage | ReplenishmentParamsRequest | ReplenishmentParamsResponse | Upsert por scope y clave (UQ de B.11) |
| DELETE | /replenishment/params/{id} | replenishment:params:manage | — | 204 | No se puede borrar el GLOBAL |
| POST | /replenishment/runs | replenishment:run:execute | RunRequest | ReplenishmentRunResponse | [I]; síncrono en el MVP; ejecuta antes el agregado diario del rango |
| GET | /replenishment/runs | replenishment:run:read | — | Page<ReplenishmentRunResponse> | |
| GET | /replenishment/runs/{id} | replenishment:run:read | — | ReplenishmentRunResponse | |
| GET | /replenishment/runs/{id}/lines | replenishment:run:read | — | Page<ReplenishmentLineResponse> | f: status, supplierId, flag, categoryId |
| PATCH | /replenishment/lines/{id} | replenishment:run:execute | ReplenishmentLineUpdateRequest | ReplenishmentLineResponse | Si ya está en una OC → ST |
| POST | /replenishment/runs/{id}/purchase-orders | replenishment:run:execute + purchasing:order:create | GenerateDraftsRequest | GeneratedOrdersResponse | Una OC DRAFT por proveedor (C.5) |

**Reporting.** Todos aceptan `ReportFilter` y `/export?format=xlsx|pdf` ([+report:export:execute]).
| Método | Ruta | Permiso | Response | Filtros extra |
|---|---|---|---|---|
| GET | /reports/dashboard | report:dashboard:read | DashboardResponse | `date` (por defecto hoy, local activo) |
| GET | /reports/sales | report:sales:read | ReportResponse<SalesReportRow> | groupBy: day, hour, weekday, category, store |
| GET | /reports/sales-by-cashier | report:sales:read | ReportResponse<CashierSalesRow> | |
| GET | /reports/sales-by-payment-method | report:sales:read | ReportResponse<PaymentMethodRow> | |
| GET | /reports/product-ranking | report:sales:read | ReportResponse<ProductRankingRow> | order: most, least; metric: qty, amount; limit ≤ 100; categoryId |
| GET | /reports/no-rotation | report:inventory:read | ReportResponse<NoRotationRow> | `days` (por defecto 30) |
| GET | /reports/abc | report:sales:read (metric=margin exige report:margin:read) | ReportResponse<AbcRow> | metric: amount, qty, margin |
| GET | /reports/margin | report:margin:read | ReportResponse<MarginRow> | groupBy: product, category, brand, day |
| GET | /reports/inventory-valuation | report:inventory:read | ReportResponse<InventoryValuationRow> | groupBy: category, store, product; usa el stock y el `avg_cost` actuales (sin rango) |
| GET | /reports/purchases-by-supplier | report:purchasing:read | ReportResponse<PurchasesBySupplierRow> | |
| GET | /reports/shrinkage | report:shrinkage:read | ReportResponse<ShrinkageReportRow> | groupBy: type, product, category, store, month |
| GET | /reports/shrinkage/top-products | report:shrinkage:read | ReportResponse<ShrinkageReportRow> | limit |
| GET | /reports/cash-differences | report:cash:read | ReportResponse<CashDifferenceRow> | cashierId |

### F.3 Catálogo de DTOs

**Tipos base:**
- `money` = entero CLP ≥ 0.
- `qty` = decimal(14,3) > 0; entero si la unidad es UNIT o PACK.
- `cost` = decimal(14,4) ≥ 0.
- `pct` = decimal(7,4) entre 0 y 100.
- `rut` = string con formato `12345678-K`, validado por módulo 11 (normalizado sin puntos).
- `datetime` = ISO-8601 con offset.
- Abreviaturas: `req` = obligatorio; `opt` = opcional (si no se indica, es opcional).

**Comunes:**
- `IdResponse { id: long }`
- `PageResponse<T>` (según A.5)
- `RejectRequest { reason: string(3..250) req }`; `CancelRequest` igual.
- `ReportFilter { from: date req; to: date req, ≥ from y rango ≤ 366 días; storeIds: long[] (por defecto el local activo; se validan contra los asignados) }`. `from` y `to` son **opcionales** en `/reports/dashboard` (usa `date`) y en `/reports/inventory-valuation` (usa el stock actual, sin rango); en los demás reportes son obligatorios.
- `ReportResponse<T> { filters; generatedAt; rows: T[]; totals: T }`

**Auth:**
- `LoginRequest { username: string(3..50) req; password: string(1..128) req }`. La terminal ya no viaja en el login: se liga después con `POST /auth/session/terminal` (D.4).
- `SessionTerminalRequest { terminalId: long req }` (debe pertenecer al local de `X-Store-Id`).
- `TokenResponse { accessToken; tokenType="Bearer"; expiresIn: int; scope: FULL|POS; mustChangePassword: bool; user: MeResponse }`
- `PinLoginRequest { username req; pin: ^\d{4,6}$ req }`
- `OverrideRequest { action: OverrideAction req; context: object req; supervisorUsername req; method: PIN|PASSWORD req; secret: string(1..128) req; reason: string(0..250) }`. Claves de `context` por acción: DISCOUNT_ITEM_OVER_LIMIT y DISCOUNT_TOTAL_OVER_LIMIT usan `{lineIndex, productId, percent, amount, cartHash}` (la venta aún no tiene ids; `lineIndex` se omite en el descuento al total y `cartHash` es el hash canónico de las líneas y sus cantidades, D.4); el resto usa `{saleId, saleLineId, customerId, cashSessionId, entityId, amount}` según la acción. El servidor calcula `context_hash` sobre el JSON canónico.
- `OverrideResponse { overrideToken; authorizationId; expiresAt }`
- `ChangePasswordRequest { currentPassword req; newPassword: string(security.password.min_length..128) req, con ≥1 letra y ≥1 dígito, distinta del username y de la actual }`
- `ChangePinRequest { currentPassword req; newPin: ^\d{4,6}$ req, que no sea todos iguales ni una secuencia }`
- `MeResponse { id; username; fullName; roles: string[]; permissions: string[]; stores: StoreSummary[]; defaultStoreId; limits: map<RoleLimitCode, decimal|null> (null = ∞ y una clave ausente = 0, D.3); scope; hasPin }`

**Security y Audit:**
- `UserRequest { username: ^[a-z0-9._-]{3,50}$ req; fullName(2..150) req; email; rut; roleIds: long[≥1] req; storeIds: long[] req (≥1, salvo que un rol tenga all-access); defaultStoreId ∈ storeIds }`
- `UserResponse { id; username; fullName; email; rut; active; locked; lockedUntil; hasPin; roles: RoleSummary{id,code,name}[]; stores: StoreSummary[]; defaultStoreId; lastLoginAt; createdAt; version }`
- `UserSummary { id; username; fullName; active; locked; roleCodes[] }`
- `TemporaryPasswordResponse { temporaryPassword (16 caracteres, se muestra una sola vez); mustChangePassword=true }`
- `SessionResponse { id; issuedAt; expiresAt; ip; userAgent; terminalId }`
- `RoleRequest { code: ^[A-Z_]{3,30}$ req; name(2..80) req; description(0..250) }`
- `RoleResponse { id; code; name; description; system; permissionCodes[]; userCount }`
- `RolePermissionsRequest { permissionCodes: string[] req, deben existir }`
- `PermissionResponse { code; module; description; sensitive }`
- `RoleLimitsRequest { limits: [{code: RoleLimitCode req; value: decimal ≥ 0 | null}] req }`: `null` = ∞; un código omitido conserva su valor.
- `RoleLimitResponse { code; value }`
- `AuditLogResponse { id; occurredAt; userId; username; storeId; terminalId; ip; action; entityType; entityId; oldValue; newValue; reason; authorizationId; traceId; hash }`
- `AuditVerifyRequest { fromId; toId }`
- `AuditVerifyResponse { checked: long; valid: bool; firstBrokenId }`

**Organization y Files:**
- `CompanyRequest { rut req; legalName(2..200) req; tradeName(2..200) req; businessActivity(2..200) req; address req; commune req; city req; phone(0..30); email; logoFileId; timezone: IANA req }`
- `CompanyResponse`: los mismos campos más `id` y `version`.
- `StoreRequest { code: ^[A-Z0-9]{2,10}$ req; name(2..120) req; address req; commune req; city req; phone; siiBranchCode(0..20) }`
- `StoreResponse`: campos + `id`, `active`, `version`. `StoreSummary { id; code; name; active }`.
- `TerminalRequest { code: ^[A-Z0-9-]{2,20}$ req; name(2..80) req }`
- `TerminalResponse { id; storeId; code; name; active; lastSeenAt; hasOpenSession }`
- `SettingResponse { key; scope; storeId; value; valueType; description }`
- `SettingUpdateRequest { scope req; value: json req }`. Se valida contra `valueType` y el rango de cada clave (tabla de B.1).
- `EffectiveSettingsResponse { values: map<string,json> }`
- `FileResponse { id; originalName; contentType; sizeBytes; category; url }`

**Catalog:**
- `CategoryRequest { name(2..100) req; code(0..20); parentId }`
- `CategoryResponse { id; name; code; parentId; active; children[] }`
- `BrandRequest { name(1..100) req }`; `BrandResponse { id; name; active }`
- `TaxRequest { code: ^[A-Z_]{2,30}$ req; name(2..100) req; taxType req; rate: pct req; siiCode; validFrom: date req; validTo: date > validFrom }`
- `TaxResponse`: campos + `id`, `active`.
- `ProductRequest { sku: ^[A-Za-z0-9._-]{1,40}$ req; name(2..200) req; description(0..2000); brandId; categoryId req; saleType req (inmutable si tiene ventas); unitOfMeasure req (coherente con saleType); vatExempt: bool (solo al crear; al actualizar se ignora, usar `PUT /products/{id}/taxes`; `true` exige `config:tax:manage`); restrictedType; lotControlled: bool; shelfLifeDays: 1..3650; shortcutCode: ^[A-Z0-9]{1,10}$; allowManualDiscount: bool; initialPriceGross: money (solo al crear; exige update-price); taxIds: long[] (solo al crear; por defecto IVA; apartarse del IVA exige `config:tax:manage`); barcodes: BarcodeRequest[] (solo al crear); pack: PackRequest (obligatorio si PACK, solo al crear); version (al actualizar) }`
- `ProductResponse { id; sku; name; description; brand{id,name}; category{id,name,parentId,parentName}; saleType; unitOfMeasure; priceGross; netPrice; avgCost*; lastCost*; marginPct*; vatExempt; restrictedType; lotControlled; shelfLifeDays; imageUrl; shortcutCode; allowManualDiscount; active; barcodes[]; taxes[]; pack; version }`. Fórmulas: `netPrice = round(priceGross/(1+R),0)` y `marginPct = (netPrice−avgCost)/netPrice×100`, a 2 decimales.
- `ProductSummary { id; sku; name; brandName; categoryName; saleType; priceGross; primaryBarcode; active; avgCost*; marginPct* }`
- `BarcodeRequest { code: ^[0-9A-Za-z]{3,20}$ req; barcodeType req; isPrimary }`; `BarcodeResponse { id; code; barcodeType; isPrimary }`
- `PackRequest { baseProductId req; factor: int 2..1000 req }`; `PackResponse { baseProductId; baseSku; baseName; factor }`
- `ProductTaxesRequest { vatExempt: bool req; taxIds: long[] req }`: con `vatExempt=true`, `taxIds` solo admite impuestos ADDITIONAL; con `false`, debe incluir el IVA vigente.
- `PriceChangeRequest { newPriceGross: money ≥ 1 req; reason(3..250) req; version req }`
- `CostChangeRequest { newAvgCost: cost req; reason req; version req }`
- `PriceHistoryResponse { id; changeType; oldPriceGross; newPriceGross; oldCost*; newCost*; reason; sourceType; sourceId; changedBy; changedAt }`
- `ProductImageRequest { fileId req }`
- `PosProductResult { productId; sku; name; saleType; unitOfMeasure; priceGross; primaryBarcode; shortcutCode; restricted; stockOnHand; packFactor }`
- `ScanResultResponse { product: PosProductResult; quantity: decimal|null; embeddedPrice: money|null; source: BARCODE|SCALE|SHORTCUT|SKU }`
- `ScaleBarcodeConfigRequest { name(2..60) req; prefix: ^\d{2,3}$ req; embeddedType req; pluStart 0..12; pluLength 1..6; valueStart 0..12; valueLength 1..6; valueDecimals 0..3 }`, todos req. `ScaleBarcodeConfigResponse`: campos + `id`, `active`.
- `ImportJobResponse { id; importType; status; dryRun; totalRows; okRows; errorRows; errors: [{row; field; message}]; createdAt; finishedAt }`
- Columnas de plantilla: PRODUCTS = sku, name, category, subcategory, brand, saleType, priceGross, barcodes (separados por `|`), taxes (códigos separados por `|`), restricted, lotControlled. PRODUCT_SUPPLIERS = sku, supplierRut, purchaseUnitName, units, cost, moq. INITIAL_STOCK = sku, quantity, unitCost, lotNumber, expiryDate.

**Purchasing:**
- `SupplierRequest { rut req; legalName(2..200) req; tradeName; businessActivity; address; commune; city; phone; email; contactName; contactPhone; contactEmail; paymentTermsType req; paymentTermsDays: 0..180; leadTimeDays: 0..90 req; deliveryWeekdays: int[1..7] sin repetir; notes(0..2000) }`
- `SupplierResponse`: campos + `id`, `active`, `version`. `SupplierSummary { id; rut; legalName; tradeName; leadTimeDays; active }`.
- `ProductSupplierRequest { productId req; supplierId req; supplierSku(0..40); purchaseUnitName(1..30) req; unitsPerPurchaseUnit: qty req; costPerPurchaseUnit: cost req; minOrderQty ≥ 1 req; leadTimeDaysOverride: 0..90; isPreferred }`
- `ProductSupplierResponse`: campos (con `costPerPurchaseUnit*`) + `id`, `productSku`, `productName`, `supplierName`, `unitCostNet*`, `active`.
- `PurchaseOrderRequest { supplierId req; expectedDate ≥ hoy; notes(0..2000); lines: [{productId req; qtyPurchaseUnits: qty req; unitCostNet: cost (por defecto el de product_suppliers)}] (1..500 líneas, productos sin repetir) req; version (al actualizar) }`
- `PurchaseOrderResponse { id; number; store; supplier: SupplierSummary; status; origin; replenishmentRunId; expectedDate; sentAt; netTotal*; taxTotal*; total*; notes; lines: [{id; productId; sku; name; purchaseUnitName; qtyPurchaseUnits; unitsPerPurchaseUnit; qtyOrdered; qtyReceived; unitCostNet*; lineNetTotal*}]; version }`
- `PurchaseOrderSummary { id; number; supplierName; status; origin; total*; createdAt; expectedDate }`
- `SendPurchaseOrderRequest { emailToSupplier: bool = false; version req }`
- `GoodsReceiptRequest { purchaseOrderId; supplierId (req si no hay OC); supplierDocumentType req; supplierDocumentNumber(1..30) (req si ≠ NONE); notes; lines: [{purchaseOrderLineId; productId req; qtyReceived ≥ 0 req; qtyRejected ≥ 0; differenceReason(0..250) (req si qtyReceived ≠ pendiente); unitCostNet: cost req; lotNumber(1..40) (req si lotControlled); expiryDate (req si lotControlled y shelfLifeDays)}] (≥1) req }`
- `GoodsReceiptResponse { id; number; purchaseOrderId; supplier; status; supplierDocumentType; supplierDocumentNumber; receivedAt; receivedBy; netTotal*; lines[… + qtyExpected, lotId; unitCostNet*]; costChanges: [{productId; oldAvgCost*; newAvgCost*}] }`
- `GoodsReceiptSummary { id; number; supplierName; purchaseOrderNumber; status; receivedAt; netTotal* }`

**Inventory:**
- `StockResponse { productId; sku; name; categoryName; unitOfMeasure; quantity; minQty; maxQty; status: OK|BELOW_MIN|ZERO|ABOVE_MAX; valuation*; lastMovementAt }`
- `StockByStoreResponse { productId; stores: [{storeId; storeName; quantity; minQty; maxQty}]; inTransit; companyQuantity }`
- `StockLimitsRequest { minQty ≥ 0; maxQty ≥ minQty }`
- `StockAlertResponse { belowMin: StockResponse[≤50]; zeroCount; expiringCount; expiring: ExpiryAlertResponse[≤20] }`
- `StockMovementResponse { id; occurredAt; movementType; quantityDelta; balanceAfter; unitCost*; lotNumber; sourceType; sourceId; reason; username }`
- `StockAdjustmentRequest { reasonCode req; notes(0..500); lines: [{productId req; quantityDelta ≠ 0 req; lotId (req si lotControlled y delta < 0); lotNumber, expiryDate (req si lotControlled y delta > 0)}] (1..200) req }`
- `StockAdjustmentResponse { id; number; status; reasonCode; notes; totalCostImpact*; requestedBy; approvedBy; approvedAt; rejectionReason; lines: [{productId; sku; name; lotNumber; quantityDelta; unitCost*}] }`
- `InventoryCountRequest { countType req; categoryId (req si CYCLIC); blind: bool = true; notes }`
- `InventoryCountResponse { id; number; countType; category; status; blind; startedBy; submittedBy; approvedBy; progress{total, counted}; differenceSummary*{lines, valueIn, valueOut} }`
- `CountLineResponse { id; productId; sku; name; lotId; lotNumber; theoreticalQty; countedQty; difference; countedBy; countedAt }`
- `CountLinesRequest { lines: [{lineId req; countedQty ≥ 0 req}] (1..500) }`
- `StockTransferRequest { destinationStoreId req, distinto del activo; notes; lines: [{productId req; qtySent: qty req; originLotId}] (1..300) }`
- `StockTransferResponse { id; number; origin; destination; status; sentAt; sentBy; receivedAt; receivedBy; lines: [{id; productId; sku; name; lotNumber; expiryDate; qtySent; qtyReceived; differenceReason}] }`
- `TransferReceiveRequest { lines: [{lineId req; qtyReceived: 0..qtySent req; differenceReason (req si difiere)}] }`: se deben informar todas las líneas.
- `LotResponse { id; productId; sku; name; lotNumber; expiryDate; quantity; daysToExpiry; status }`
- `LotUpdateRequest { lotNumber(1..40); expiryDate; reason(3..250) req; version req }`
- `ExpiryAlertResponse { lotId; productId; sku; name; lotNumber; expiryDate; daysToExpiry; quantity; valuation* }`
- `FefoSuggestionResponse { productId; requestedQty; allocations: [{lotId; lotNumber; expiryDate; quantity}]; shortage }`

**Shrinkage:**
- `ShrinkageRequest { productId req; lotId (req si lotControlled); shrinkageType req, distinto de TRANSFER_DIFFERENCE; quantity: qty req; reason(0..500) (req si THEFT_LOSS o INTERNAL_CONSUMPTION); evidenceFileId; responsibleUserId req; occurredAt ≤ ahora req }`
- `ShrinkageResponse { id; number; product; lot; shrinkageType; quantity; unitCost; totalCost; reason; evidenceUrl; responsible; reportedBy; status; autoApproved; affectsStock; approvedBy; approvedAt; rejectionReason; occurredAt }`. El costo lo ve quien tiene shrinkage:record:approve o catalog:cost:read.

**Promotions:**
- `PromotionRequest { name(3..120) req; description(0..500); promotionType req; priority 1..1000 = 100; stackable; validFrom req; validTo > validFrom; daysOfWeek: int[1..7]; timeFrom, timeTo (ambos o ninguno); allStores = true; storeIds (req si !allStores); percent | amount | buyQty | payQty | minQty | comboPrice (según los CK de B.10); maxApplicationsPerSale ≥ 1; items: [{targetType req; targetId req; requiredQty: qty = 1}] (≥1; COMBO exige ≥2 de tipo PRODUCT) req; version }`
- `PromotionResponse`: campos + `id`, `active`, `items[{…, targetName}]`.
- `ActivePromotionResponse { id; name; promotionType; label }`

**Customers:**
- `CustomerRequest { rut; name(2..150) req; email; phone(0..30); address(0..250); notes(0..2000); version }`
- `CustomerResponse { id; rut; name; email; phone; address; creditEnabled; creditLimit; creditBalance; availableCredit; active; version }`
- `CreditSettingsRequest { creditEnabled req; creditLimit: money req; reason(3..250) req }`. Puede quedar menor que la deuda: en ese caso bloquea cargos nuevos.
- `CustomerPaymentRequest { amount: money ≥ 1 y ≤ deuda req; paymentMethod: CASH|TRANSFER req; reference(0..60); notes(0..250) }`
- `CustomerAdjustmentRequest { amount ≠ 0 req; reason req }`
- `AccountMovementResponse { id; occurredAt; movementType; amount; balanceAfter; saleNumber; paymentMethod; storeName; username; notes }`
- `AccountStatementResponse { customer; movements: PageResponse<AccountMovementResponse> }`

**Sales y Billing:**
- `DiscountInput { kind: PERCENT|AMOUNT req; value > 0 req (≤ 100 si PERCENT); reason(0..250) }`
- `CartLineInput { productId req; quantity: qty ≤ 9999 req; scannedCode; embeddedPrice: money; manualDiscount: DiscountInput }`
- `PaymentInput { method req; amount: money ≥ 1 (req salvo CASH); tenderedAmount: money (req si CASH); cardAuthorizationId (req si DEBIT o CREDIT); transferReference(1..60) (req si TRANSFER) }`. Para el efectivo, el servidor calcula `amount` = saldo adeudado en efectivo ya redondeado (A.5) y el cliente informa `tenderedAmount` (lo entregado, ≥ `amount`); el vuelto es `tenderedAmount − amount`. En los demás medios `amount` es lo aplicado a la venta. Se admite un solo pago CASH.
- `CartQuoteRequest { lines: CartLineInput[1..300] req; totalDiscount; customerId; payments: PaymentInput[] }`
- `CartQuoteResponse { lines: [{lineNumber; productId; name; quantity; unitPriceGross; lineSubtotalGross; promotionDiscount; manualDiscount; proratedDiscount; lineTotalGross; lineNet; lineTax; lineExempt; restricted; appliedPromotion{id,name}}]; subtotalGross; discountTotal; totalGross; netTotal; taxTotal; exemptTotal; taxBreakdown: [{taxCode; rate; baseAmount; taxAmount}]; roundingAdjustment; totalPayable; changeTotal; requiresAgeConfirmation; requiredOverrides: [{action; context}] }`
- `CreateSaleRequest { lines req; totalDiscount; customerId (req si hay CUSTOMER_CREDIT); payments: PaymentInput[1..5] req; ageConfirmed: bool (debe ser true si hay restringidos); emailTo }`
- `SalePaymentResponse { method; amount (aplicado a la venta); tenderedAmount (solo CASH); changeAmount; status; cardBrand; last4; authorizationCode }`
- `SaleResponse { id; number; status; soldAt; businessDate; store; terminal; cashier; customer; lines[CartQuote line + returnedQuantity]; discounts[]; payments[]; subtotalGross; discountTotal; totalGross; netTotal; taxTotal; exemptTotal; roundingAdjustment; totalPayable; paidTotal; changeTotal; returns[{id, number, returnType, refundTotal}] }`. No incluye el documento tributario (sales no depende de billing): se obtiene con `GET /sales/{id}/tax-document`. `paidTotal` = Σ de lo entregado (`tenderedAmount` en efectivo, `amount` en el resto) y `paidTotal − changeTotal = totalPayable`.
- `SaleSummary { id; number; soldAt; cashierName; customerName; totalPayable; status; paymentMethods[] }`
- `TicketResponse { company{tradeName, rut, address, businessActivity}; store; terminal; number; soldAt; cashier; lines[{name; quantity; unitPrice; discount; total}]; totals; taxBreakdown; payments; change; rounding; headerLines; footerLines; reprintCount; copyLabel }`. El tipo, el folio y el timbre del documento tributario los agrega el frontend desde `GET /sales/{id}/tax-document`.
- `EmailTicketRequest { email req }`
- `VoidSaleRequest { reasonCode req; reasonText(0..500) }`
- `SaleReturnRequest { reasonCode req; reasonText(0..500); lines: [{saleLineId req; quantity ≤ lo devolvible req; restock = true}] (≥1) req; refunds: [{method req; amount req}] }`. Por defecto se reembolsa por los mismos medios de la venta, en proporción; la suma debe igualar el reembolso calculado, si no → `PAYMENT_MISMATCH`.
- `SaleReturnResponse { id; number; saleId; returnType; reasonCode; reasonText; refundTotal; lines[{saleLineId; name; quantity; restock; refundAmount}]; refunds[]; createdAt; createdBy }`. La nota de crédito se obtiene con `GET /sale-returns/{id}/tax-document`.
- `ReturnableLinesResponse { saleId; voidAllowed; returnDeadline; lines: [{saleLineId; name; quantity; returnedQuantity; returnableQuantity; unitRefundAmount}] }`
- `SuspendSaleRequest { label(0..60); customerId; cart{lines: CartLineInput[1..300]; totalDiscount} req }`
- `SuspendedSaleResponse { id; label; userName; customerId; cart; itemCount; estimatedTotal; createdAt; expiresAt }`
- `CardAuthorizationRequest { method: DEBIT|CREDIT req; amount: money ≥ 1 req }`
- `CardAuthorizationResponse { id; status; gatewayReference; authorizationCode; cardBrand; last4; message }`
- `TaxDocumentResponse { id; documentType; typeLabel; stampText; siiTypeCode; folio; status; saleId; saleReturnId; provider; providerTrackId; netAmount; exemptAmount; taxAmount; totalAmount; attempts; lastError; issuedAt }`

**Cash:**
- `OpenCashSessionRequest { openingAmount: money req }`
- `CashCountResponse { paymentMethod; expectedAmount; countedAmount; difference }`
- `CashSessionResponse { id; number; store; terminal; cashier; status; businessDate; openedAt; openingAmount; closedAt; closedBy; expectedTotal; countedTotal; differenceTotal; requiresDifferenceApproval; differenceApprovedBy; counts[] }`
- `CashMovementRequest { movementType req; amount: money ≥ 1 req; reason(3..250) req }`
- `CashMovementResponse { id; movementType; amount; reason; username; occurredAt; authorizationId }`
- `CashSessionSummaryResponse { sessionId; openingAmount; cashIn; cashOut; byMethod: [{method; sales; refunds; creditPayments; expected}]; expectedCash; salesCount; voidCount; returnCount }`
- `CloseCashSessionRequest { counts: [{paymentMethod req; countedAmount: money req; denominationDetail: map<int,int> (solo CASH)}] req; notes(0..500) }`. Deben venir todos los medios con movimiento y siempre CASH.
- `CashClosingReceiptResponse`: es `CashSessionResponse` + `summary` + `company/store` + `printedAt`.

**Replenishment:**
- `ReplenishmentParamsRequest { scope req; storeId | categoryId | productId (según scope); windowDays 7..365; minHistoryDays 1..90; serviceLevelZ 0..3.5; safetyMinDays 0..60; reviewPeriodDays 1..60; outlierK 1..10; shelfLifeFactor 0.1..1; enabled }`. Un valor null significa que se hereda del nivel superior.
- `ReplenishmentParamsResponse`: campos + `id`.
- `RunRequest { asOfDate ≤ hoy (por defecto hoy) }`
- `ReplenishmentRunResponse { id; storeId; asOfDate; status; linesCount; countsByStatus: map; startedAt; finishedAt; error }`
- `ReplenishmentLineResponse { id; product{id,sku,name}; supplier{id,name}; purchaseUnitName; unitsPerPurchaseUnit; validDays; censoredDays; avgDailyDemand; demandStd; leadTimeDays; reviewPeriodDays; onHand; inTransit; safetyStock; reorderPoint; targetLevel; suggestedQty; suggestedPurchaseUnits; finalPurchaseUnits; estimatedStockoutDate; daysOfCover; status; flags[]; unitCostNet*; estimatedCost* }`
- `ReplenishmentLineUpdateRequest { finalPurchaseUnits ≥ 0 (si > 0, ≥ MOQ); status: ACCEPTED|DISCARDED; productSupplierId }`
- `GenerateDraftsRequest { lineIds: long[] }`. Por defecto toma las ACCEPTED, o las SUGGESTED si no hay ninguna aceptada.
- `GeneratedOrdersResponse { purchaseOrders: [{id; number; supplierName; linesCount; netTotal}] }`

**Reporting** (filas):
- `DashboardResponse { date; salesTotal; transactions; avgTicket; vsPreviousDayPct; salesByHour[{hour; total; transactions}]; topProducts[{productId; name; qty; total}](10); paymentMix[{method; total}]; alerts{belowMin; zeroStock; expiringLots; pendingShrinkages; pendingAdjustments; pendingCounts; pendingCashDifferences} }`
- `SalesReportRow { key; label; transactions; grossTotal; netTotal; taxTotal; discountTotal; returnsTotal; avgTicket }`
- `CashierSalesRow { userId; name; transactions; total; avgTicket; voids; returns; discountTotal; overridesCount }`
- `PaymentMethodRow { method; transactions; total; pct }`
- `MarginRow { key; label; qty; netSales; cost; grossMargin; marginPct }`
- `ProductRankingRow { productId; sku; name; category; qty; netSales; transactions; rank }`
- `NoRotationRow { productId; sku; name; stock; valuation*; lastSaleAt; daysWithoutSale }`
- `AbcRow { productId; sku; name; metricValue; pct; cumulativePct; abcClass }`: A hasta el 80 % acumulado, B hasta el 95 %, C el resto.
- `InventoryValuationRow { key; label; qty; valuation; pct }`
- `PurchasesBySupplierRow { supplierId; name; orders; receipts; netTotal; avgLeadTimeDays; fillRatePct }`
- `ShrinkageReportRow { key; label; qty; cost; pctOfSales; count }`. `pctOfSales` = costo de merma / ventas netas del mismo filtro × 100.
- `CashDifferenceRow { sessionId; number; businessDate; cashier; terminal; expected; counted; difference; approvedBy }`

### F.4 Flujos críticos

1. **Venta con pago mixto y redondeo del efectivo.**
   1. `GET /pos/products/resolve` por cada ítem escaneado.
   2. `POST /pos/cart/quote` en cada cambio del carrito.
   3. Ejemplo: total $12.347. El cliente paga $5.000 con débito: se llama `POST /pos/card-payments {DEBIT, 5000}` con [I] y se obtiene APPROVED, id 77.
   4. Se repite el quote con `payments=[DEBIT 5000, CASH tendered 10000]`. El efectivo adeudado es $7.347; termina en 7, así que sube a $7.350 (`roundingAdjustment=+3`), `totalPayable=12.350` y el vuelto es $2.650.
   5. `POST /sales` con [I][T] y `payments=[{DEBIT,5000,cardAuthorizationId:77},{CASH,tendered:10000}]`. Si la venta falla, el frontend llama `POST /pos/card-payments/77/void`.
      Se persiste: pago DEBIT `amount=5000`; pago CASH `amount=7350` (lo aplicado = entregado − vuelto), `tendered_amount=10000`, `change_amount=2650`. Cuadre (RN-07): `Σ amount = 5000 + 7350 = 12.350 = total_payable`; `paid_total = 5000 + 10000 = 15.000` y `paid_total − change_total = 15.000 − 2.650 = 12.350`.
   6. `GET /sales/{id}/ticket` y se imprime. Después del commit `billing` emite la boleta de forma asíncrona; el ticket incluye el folio y el timbre si el frontend los obtiene con `GET /sales/{id}/tax-document` (204 mientras no esté emitida).

2. **Descuento sobre el límite.**
   1. El cajero (límite del 5 %) aplica 12 % a una línea. El quote devuelve `requiredOverrides=[{DISCOUNT_ITEM_OVER_LIMIT,{lineIndex, productId, percent:12, amount, cartHash}}]`.
   2. Se abre el diálogo de supervisor y se llama `POST /auth/overrides` con PIN. El supervisor tiene límite del 15 %, así que se obtiene un token.
   3. `POST /sales` con `X-Override-Token`. El servicio consume el token (D.4: valida también solicitante, local, terminal y `cartHash`) y audita SALE_DISCOUNT_OVER_LIMIT con `authorization_id`.
   4. Si el porcentaje fuera 20 %, que supera el límite del supervisor, `/auth/overrides` respondería 422 `OVERRIDE_LIMIT_EXCEEDED`.

3. **Venta de producto restringido.**
   1. Al escanear, `resolve` devuelve `restricted=true`. El POS muestra el aviso y bloquea el pago hasta que se confirme la edad con la tecla F7 o el botón.
   2. `POST /sales` con `ageConfirmed=true`. Sin esa confirmación responde 422 `AGE_CONFIRMATION_REQUIRED`.
   3. Se audita SALE_RESTRICTED_AGE_CONFIRMED y se guarda `sales.age_confirmed=true`.

4. **Devolución parcial.**
   1. `GET /sales/by-number/{n}` y luego `GET /sales/{id}/returnable`.
   2. `POST /sales/{id}/returns` con [I][T]: 1 de 3 unidades, `restock=true`.
   3. Si el usuario no tiene permiso, responde 422 `OVERRIDE_REQUIRED`. Se pide el override SALE_RETURN y se reintenta.
   4. En una sola transacción: se bloquea la venta (`FOR UPDATE`); se valida RN-13; se calcula el reembolso proporcional (RN-14); SALE_RETURN reingresa al stock (al lote original vía `sale_lot_allocations`); se registra el reembolso y la reversa de tarjeta o fiado; la venta pasa a PARTIALLY_RETURNED; se audita.
   5. Después del commit se emite la nota de crédito 61.

5. **Cierre de caja con arqueo.**
   1. `GET /cash-sessions/current` y luego `GET /{id}/summary` para ver lo esperado por medio de pago.
   2. El cajero cuenta e ingresa `POST /{id}/close` con [I], `counts` y la denominación del efectivo.
   3. Si `|diff| ≤ cash.difference_tolerance`: estado CLOSED.
   4. Si la supera y viene un `X-Override-Token` CASH_DIFFERENCE_APPROVAL de otra persona: CLOSED y aprobada. Si no: CLOSED con `requiresDifferenceApproval=true` y luego `POST /{id}/approve-difference` por un supervisor (SOD).
   5. `GET /{id}/closing-receipt?format=pdf`.

6. **Recepción de OC con lotes y costo promedio.**
   1. `GET /purchase-orders/{id}/receipt-draft`.
   2. `POST /goods-receipts` con lotes y vencimientos.
   3. `POST /goods-receipts/{id}/confirm` con [I]. Por línea, ordenando por `product_id` y con `q = qty_received − qty_rejected` (lo rechazado no entra): se crea o suma el lote; se aplica `StockApi.apply(PURCHASE_RECEIPT)`; se recalcula `avg' = (Q·avg + q·c)/(Q+q)` con Q = `companyQuantity` antes de la entrada; se llama `catalog.applyAverageCost` y se registra en `price_history` (COST_RECEIPT).
   4. Se actualiza `qty_received` y la OC pasa a PARTIALLY_RECEIVED o RECEIVED. Se audita GOODS_RECEIPT_CONFIRMED (con la marca de sobre-recepción o de recepción sin OC).

7. **Toma de inventario con aprobación.**
   1. `POST /inventory-counts {CYCLIC, categoryId, blind}`.
   2. Los contadores registran con `PUT /{id}/lines` en lotes de hasta 500 líneas.
   3. `POST /{id}/submit` y luego `GET /{id}/lines`, donde el supervisor ve las diferencias.
   4. `POST /{id}/approve` por alguien distinto de quien inició y de quienes contaron (si no, SOD). Se aplican los deltas como ADJUSTMENT_IN o ADJUSTMENT_OUT con `source_type=INVENTORY_COUNT` y se audita.

8. **Reabastecimiento a borradores de OC.**
   1. `PUT /replenishment/params` (opcional).
   2. `POST /replenishment/runs` con [I]: el agregado diario y luego el cálculo C.5.
   3. `GET /runs/{id}/lines?status=SUGGESTED` y `PATCH /replenishment/lines/{id}` para editar las unidades finales o aceptar o descartar.
   4. `POST /runs/{id}/purchase-orders` crea OC DRAFT por proveedor con `origin=REPLENISHMENT`.
   5. Revisión en Compras y luego `POST /purchase-orders/{id}/send`.

---
