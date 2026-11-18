# Roles y permisos — POS Minimarket

> Parte del diseño de POS Minimarket. Las secciones conservan su letra (A–J) para que las referencias cruzadas funcionen entre documentos: A, C, E, H, I en [PLAN.md](PLAN.md) · B en [MODELO_DATOS.md](MODELO_DATOS.md) · D en [ROLES_PERMISOS.md](ROLES_PERMISOS.md) · F en [API.md](API.md) · G en [HISTORIAS_USUARIO.md](HISTORIAS_USUARIO.md).

## D. Roles y permisos

Roles base (código): `ADMIN` (Administrador), `MANAGER` (Gerente o Encargado), `SUPERVISOR`, `CASHIER` (Cajero), `WAREHOUSE` (Bodeguero).

### D.1 y D.2 Catálogo de permisos y matriz por rol

Leyenda:
- ✔ = permitido, **acotado a los locales asignados** (regla general para todo rol sin `security:store:all-access`).
- ≤ = permitido hasta el límite del rol (D.3).
- P = solo sobre lo propio.
- ✖ = denegado.
- S = permiso sensible (se audita su uso).

| Código | Descripción | S | ADM | GER | SUP | CAJ | BOD |
|---|---|---|---|---|---|---|---|
| security:user:read | Ver usuarios | | ✔ | ✔ | ✖ | ✖ | ✖ |
| security:user:create | Crear usuarios | S | ✔ | ✔¹ | ✖ | ✖ | ✖ |
| security:user:update | Editar usuarios, roles y locales | S | ✔ | ✔¹ | ✖ | ✖ | ✖ |
| security:user:disable | Desactivar usuarios | S | ✔ | ✔¹ | ✖ | ✖ | ✖ |
| security:user:reset-credentials | Resetear clave o PIN y desbloquear | S | ✔ | ✔¹ | ✖ | ✖ | ✖ |
| security:role:read | Ver roles y permisos | | ✔ | ✔ | ✖ | ✖ | ✖ |
| security:role:manage | Crear, editar y borrar roles | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| security:session:revoke | Revocar sesiones de otros | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| security:store:all-access | Acceso a todos los locales | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| security:audit:read | Ver auditoría | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| security:audit:verify | Verificar la cadena de hash | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| config:company:update | Editar la empresa | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| config:store:manage | Crear y editar locales | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| config:terminal:manage | Gestionar terminales | | ✔ | ✔ | ✖ | ✖ | ✖ |
| config:tax:manage | Gestionar impuestos | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| config:settings:read | Ver parámetros | | ✔ | ✔ | ✔ | ✖ | ✖ |
| config:settings:update | Editar parámetros globales y del local | S | ✔ | ✔² | ✖ | ✖ | ✖ |
| config:role-limit:manage | Editar límites por rol | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| config:scale-barcode:manage | Configurar códigos de balanza | | ✔ | ✔ | ✖ | ✖ | ✖ |
| catalog:product:read | Ver productos (sin costos) | | ✔ | ✔ | ✔ | ✔ | ✔ |
| catalog:product:create | Crear productos | | ✔ | ✔ | ✖ | ✖ | ✔ |
| catalog:product:update | Editar datos no económicos | | ✔ | ✔ | ✔ | ✖ | ✔ |
| catalog:product:deactivate | Activar o desactivar | | ✔ | ✔ | ✖ | ✖ | ✖ |
| catalog:product:update-price | Cambiar el precio de venta | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| catalog:cost:read | Ver costos y márgenes | S | ✔ | ✔ | ✖ | ✖ | ✔ |
| catalog:cost:update | Costo manual o inicial | S | ✔ | ✖ | ✖ | ✖ | ✖ |
| catalog:category:manage | Categorías | | ✔ | ✔ | ✖ | ✖ | ✖ |
| catalog:brand:manage | Marcas | | ✔ | ✔ | ✖ | ✖ | ✔ |
| catalog:product:import | Importación masiva | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| catalog:product:export | Exportar el catálogo | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| promotions:promotion:read | Ver promociones | | ✔ | ✔ | ✔ | ✔ | ✖ |
| promotions:promotion:manage | Crear y editar promociones | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| purchasing:supplier:read | Ver proveedores | | ✔ | ✔ | ✔ | ✖ | ✔ |
| purchasing:supplier:manage | Gestionar proveedores | | ✔ | ✔ | ✖ | ✖ | ✖ |
| purchasing:product-supplier:manage | Producto-proveedor y costos | S | ✔ | ✔ | ✖ | ✖ | ✔ |
| purchasing:order:read | Ver órdenes de compra | | ✔ | ✔ | ✔ | ✖ | ✔ |
| purchasing:order:create | Crear y editar borradores | | ✔ | ✔ | ✖ | ✖ | ✔ |
| purchasing:order:send | Aprobar y enviar la OC | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| purchasing:order:cancel | Cancelar la OC | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| purchasing:receipt:create | Registrar recepción | | ✔ | ✔ | ✔ | ✖ | ✔ |
| purchasing:receipt:confirm | Confirmar recepción (mueve stock y costo) | S | ✔ | ✔ | ✔ | ✖ | ✔ |
| purchasing:receipt:without-order | Recepción sin OC | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| purchasing:receipt:over-receive | Recibir más de lo pedido | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| replenishment:run:execute | Ejecutar sugerencia | | ✔ | ✔ | ✖ | ✖ | ✔ |
| replenishment:run:read | Ver sugerencias | | ✔ | ✔ | ✖ | ✖ | ✔ |
| replenishment:params:manage | Parámetros de reabastecimiento | | ✔ | ✔ | ✖ | ✖ | ✖ |
| inventory:stock:read | Ver stock | | ✔ | ✔ | ✔ | ✔ | ✔ |
| inventory:kardex:read | Ver kardex | | ✔ | ✔ | ✔ | ✖ | ✔ |
| inventory:stock-limits:update | Mínimos y máximos | | ✔ | ✔ | ✖ | ✖ | ✔ |
| inventory:adjustment:create | Solicitar ajuste | | ✔ | ✔ | ✔ | ✖ | ✔ |
| inventory:adjustment:approve | Aprobar ajuste | S | ✔ | ✔ | ✔≤ | ✖ | ✖ |
| inventory:count:manage | Crear o cancelar toma | | ✔ | ✔ | ✔ | ✖ | ✔ |
| inventory:count:execute | Registrar conteos | | ✔ | ✔ | ✔ | ✔ | ✔ |
| inventory:count:approve | Aprobar toma | S | ✔ | ✔ | ✔ | ✖ | ✖ |
| inventory:transfer:read | Ver transferencias | | ✔ | ✔ | ✔ | ✖ | ✔ |
| inventory:transfer:send | Crear y enviar transferencia | | ✔ | ✔ | ✖ | ✖ | ✔ |
| inventory:transfer:receive | Recibir transferencia | | ✔ | ✔ | ✔ | ✖ | ✔ |
| inventory:transfer:cancel | Cancelar transferencia en DRAFT | | ✔ | ✔ | ✖ | ✖ | ✔ |
| inventory:lot:update | Corregir datos de lote | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| inventory:expiry:read | Alertas de vencimiento | | ✔ | ✔ | ✔ | ✖ | ✔ |
| shrinkage:record:read | Ver mermas | | ✔ | ✔ | ✔ | ✖ | ✔ |
| shrinkage:record:create | Registrar merma | | ✔ | ✔ | ✔ | ✔ | ✔ |
| shrinkage:record:approve | Aprobar merma | S | ✔ | ✔ | ✔≤ | ✖ | ✖ |
| sales:pos:operate | Operar el POS (vender, suspender, retomar) | | ✔ | ✔ | ✔ | ✔ | ✖ |
| sales:sale:read | Ver ventas | | ✔ | ✔ | ✔ | P | ✖ |
| sales:discount:apply | Descuento manual dentro del límite | | ✔ | ✔≤ | ✔≤ | ✔≤ | ✖ |
| sales:discount:override | Autorizar descuento sobre el límite | S | ✔ | ✔≤ | ✔≤ | ✖ | ✖ |
| sales:sale:void | Anular venta / autorizar anulación | S | ✔ | ✔ | ✔ | ✖ | ✖ |
| sales:return:create | Devolución / autorizarla | S | ✔ | ✔ | ✔≤ | ✖ | ✖ |
| sales:suspended:delete-others | Borrar suspendidas ajenas | | ✔ | ✔ | ✔ | ✖ | ✖ |
| sales:receipt:reprint | Reimprimir comprobante | | ✔ | ✔ | ✔ | ✖ | ✖ |
| sales:tax-document:read | Ver documentos tributarios | | ✔ | ✔ | ✔ | ✖ | ✖ |
| sales:tax-document:retry | Reintentar emisión | | ✔ | ✔ | ✖ | ✖ | ✖ |
| customers:customer:read | Ver clientes | | ✔ | ✔ | ✔ | ✔ | ✖ |
| customers:customer:manage | Crear y editar clientes | | ✔ | ✔ | ✔ | ✔³ | ✖ |
| customers:credit:sell | Vender a fiado | | ✔ | ✔ | ✔ | ✔ | ✖ |
| customers:credit:payment | Registrar abono | | ✔ | ✔ | ✔ | ✔ | ✖ |
| customers:credit:manage-limit | Habilitar o editar el cupo | S | ✔ | ✔≤ | ✔≤ | ✖ | ✖ |
| customers:credit:override | Autorizar sobre el cupo | S | ✔ | ✔ | ✔ | ✖ | ✖ |
| cash:session:open | Abrir turno | | ✔ | ✔ | ✔ | ✔ | ✖ |
| cash:session:close | Cerrar el turno propio | | ✔ | ✔ | ✔ | P | ✖ |
| cash:session:close-others | Cerrar turno ajeno | S | ✔ | ✔ | ✔ | ✖ | ✖ |
| cash:session:read | Ver turnos | | ✔ | ✔ | ✔ | P | ✖ |
| cash:movement:cash-in | Ingreso de efectivo | | ✔ | ✔ | ✔ | ✔ | ✖ |
| cash:movement:cash-out | Retiro de efectivo | S | ✔ | ✔≤ | ✔≤ | ✔≤ | ✖ |
| cash:movement:override | Autorizar retiro sobre el límite | S | ✔ | ✔ | ✔≤ | ✖ | ✖ |
| cash:difference:approve | Aprobar diferencia de cierre | S | ✔ | ✔ | ✔ | ✖ | ✖ |
| report:dashboard:read | Dashboard | | ✔ | ✔ | ✔ | ✖ | ✖ |
| report:sales:read | Reportes de venta, cajero y medio de pago | | ✔ | ✔ | ✔ | ✖ | ✖ |
| report:margin:read | Margen, utilidad y ABC por margen | S | ✔ | ✔ | ✖ | ✖ | ✖ |
| report:inventory:read | Rotación, sin rotación y valorización | S | ✔ | ✔ | ✖ | ✖ | ✔ |
| report:purchasing:read | Compras por proveedor | | ✔ | ✔ | ✖ | ✖ | ✔ |
| report:shrinkage:read | Reportes de merma | | ✔ | ✔ | ✔ | ✖ | ✔ |
| report:cash:read | Diferencias de caja | | ✔ | ✔ | ✔ | ✖ | ✖ |
| report:export:execute | Exportar a Excel o PDF | S | ✔ | ✔ | ✖ | ✖ | ✖ |

Notas:
- ¹ Anti-escalada (reglas completas en D.4): un actor sin `security:store:all-access` solo crea, edita, resetea o desactiva usuarios cuyos permisos efectivos sean un subconjunto de los suyos y cuyos `storeIds` estén contenidos en sus propios locales. Por tanto, el gerente no puede asignar el rol ADMIN ni roles con permisos que él no tenga.
- ² Solo los settings con scope STORE de sus locales.
- ³ Datos básicos; no el cupo.
- Los datos de costo dentro de cualquier respuesta se omiten si el usuario no tiene `catalog:cost:read`.
- `catalog:product:read` ya cubre los precios de `price-history`. El costo de la merma y del ajuste es visible para quien tiene el permiso de aprobar (`shrinkage:record:approve` e `inventory:adjustment:approve`), como excepción a la nota anterior sobre costos.

### D.3 Límites por rol (`role_limits`)

| Código | ADM | GER | SUP | CAJ | BOD |
|---|---|---|---|---|---|
| DISCOUNT_ITEM_MAX_PCT | ∞ | 30 | 15 | 5 | 0 |
| DISCOUNT_TOTAL_MAX_PCT | ∞ | 20 | 10 | 3 | 0 |
| DISCOUNT_TOTAL_MAX_AMOUNT (CLP) | ∞ | 50 000 | 20 000 | 3 000 | 0 |
| SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT | ∞ | 100 000 | 20 000 | 0 | 10 000 |
| SHRINKAGE_APPROVE_MAX_AMOUNT | ∞ | ∞ | 100 000 | 0 | 0 |
| ADJUSTMENT_APPROVE_MAX_AMOUNT | ∞ | ∞ | 50 000 | 0 | 0 |
| CASH_OUT_MAX_AMOUNT | ∞ | 1 000 000 | 300 000 | 100 000 | 0 |
| RETURN_MAX_AMOUNT | ∞ | ∞ | 50 000 | 0 | 0 |
| CREDIT_LIMIT_MAX_ASSIGN | ∞ | 300 000 | 50 000 | 0 | 0 |
| CREDIT_ADJUSTMENT_MAX_AMOUNT (CLP, valor absoluto) | ∞ | 50 000 | 0 | 0 | 0 |

`CREDIT_ADJUSTMENT_MAX_AMOUNT` se aplica a `POST /customers/{id}/adjustments`: si `abs(amount)` lo supera, responde 422 `CREDIT_LIMIT_EXCEEDED`.

**Semántica fail-closed (regla de seguridad):**
- Una fila ausente en `role_limits` equivale a **0** (denegar). Solo una fila con `value = NULL` significa ∞ (sin límite).
- `RoleLimitApi.limitFor(userId, code)` devuelve el tipo explícito `RoleLimit` = `LIMITED(x)` | `UNLIMITED`; nunca un valor nulo ni un `Optional` vacío con significado de "sin límite".
- Al crear un rol se siembran todas sus filas, una por cada código de esta tabla, con `value = 0`. El administrador las sube después con `PUT /roles/{id}/limits`.
- Con varios roles se aplica el límite más permisivo (`UNLIMITED` gana a cualquier `LIMITED`; entre `LIMITED` gana el mayor). Para descuentos se cumplen a la vez el porcentaje y el monto.
- ∞ se guarda como `value = NULL`.

### D.4 Alcance, override y segregación

**Alcance por local:**
- Todo endpoint con datos de local exige `X-Store-Id` válido: el usuario tiene `user_stores` o `security:store:all-access`. Los listados filtran por ese local.
- Los reportes multi-local aceptan `storeIds[]`, restringidos a los asignados.
- La terminal debe pertenecer al local.
- **Sesión y terminal:** el flujo de inicio es login (`POST /auth/login`, sin terminal) → `GET /auth/me/stores` → el usuario elige el local → `GET /stores/{storeId}/terminals` (autenticado; valida que el usuario tenga acceso al local, si no `STORE_ACCESS_DENIED`) → `POST /auth/session/terminal {terminalId}`, que liga el refresh token vigente a la terminal (`refresh_tokens.terminal_id`). La rotación del refresh conserva el `terminal_id`. Esa terminal es la "sesión vigente" que exige `POST /auth/pin-login`.
- El token de un login con **PIN** tiene `scope=POS`: solo `sales:*`, `cash:*` y `customers:*` en la terminal de la sesión. Para el resto exige un login con clave.
- **Fuerza bruta:** `POST /auth/login`, `POST /auth/overrides` y `POST /auth/pin-login` tienen rate limit Bucket4j por IP y por usuario objetivo (ADR-21). Los fallos de PIN incrementan `pin_failed_attempts`; los fallos de clave (login u override con `method=PASSWORD`) incrementan `failed_attempts` y activan el bloqueo de cuenta. El bloqueo del PIN se levanta solo tras `security.pin.lockout_minutes` (por defecto 15) o con `reset-credentials`.

**Anti-escalada de usuarios y roles (servicio):**
- Un actor sin `security:store:all-access` solo crea, edita, resetea o desactiva usuarios cuyos permisos efectivos sean un subconjunto de los suyos y cuyos `storeIds` estén contenidos en sus propios locales. La violación responde 403 `ACCESS_DENIED`.
- Nadie edita su propio registro de usuario (roles, locales ni estado): responde 422 `SEGREGATION_OF_DUTIES`.
- No se puede desactivar ni dejar sin permisos al último ADMIN activo (tampoco quitando permisos a su rol): responde 409 `ENTITY_IN_USE`.

**Override de supervisor:**
- Acciones admitidas: todas las de `override_action`.
- **Regla general:** un endpoint que admite override (marcado `[O:…]` en F.2) responde 422 `OVERRIDE_REQUIRED` cuando falta el permiso o el límite de la acción que protege el override; la falta de otros permisos del endpoint da 403 `ACCESS_DENIED`. Con un token válido la operación se ejecuta.
- Flujo:
  1. El endpoint de negocio responde 422 `OVERRIDE_REQUIRED` con `{action, context}`.
  2. El frontend abre el diálogo de supervisor (usuario + PIN o clave).
  3. `POST /auth/overrides` valida: credencial, supervisor activo y asignado al local, permiso de la acción, límite suficiente para el contexto (ej. % de descuento; si no alcanza responde 422 `OVERRIDE_LIMIT_EXCEEDED`), supervisor distinto del solicitante, y que no esté bloqueado. Los intentos fallidos cuentan para el bloqueo (PIN → `pin_failed_attempts`; clave → `failed_attempts`) y para el rate limit.
  4. Se crea `supervisor_authorizations` GRANTED con `token_hash`.
  5. Se devuelve el token.
  6. El frontend repite la operación con `X-Override-Token`.
  7. El servicio lo consume atómicamente (`UPDATE … SET consumed_at=now(), entity_* WHERE token_hash=? AND consumed_at IS NULL AND expires_at>now() AND action=? AND context_hash=? AND requested_by=<usuario actual> AND store_id=<local activo> AND (terminal_id IS NULL OR terminal_id=<terminal actual>)`).
  8. El evento de auditoría guarda `authorization_id`.
- Los rechazos se guardan como DENIED.
- **`context` de los descuentos en `POST /sales`:** como la venta aún no tiene ids, el contexto es `{lineIndex, productId, percent, amount, cartHash}` (`lineIndex` se omite en DISCOUNT_TOTAL_OVER_LIMIT); `cartHash` es el hash canónico (SHA-256 del JSON ordenado) de las líneas y sus cantidades. Si el carrito cambia después de autorizar, el hash no coincide y el token es `OVERRIDE_INVALID`.
- Mapa de `override_action` a permiso y límite:

| override_action | Permiso del supervisor | Límite (D.3) |
|---|---|---|
| DISCOUNT_ITEM_OVER_LIMIT, DISCOUNT_TOTAL_OVER_LIMIT | `sales:discount:override` | DISCOUNT_* |
| SALE_VOID | `sales:sale:void` | — |
| SALE_RETURN | `sales:return:create` | RETURN_MAX_AMOUNT |
| CASH_OUT_OVER_LIMIT | `cash:movement:override` | CASH_OUT_MAX_AMOUNT |
| CREDIT_OVER_LIMIT | `customers:credit:override` | — |
| SUSPENDED_SALE_DELETE | `sales:suspended:delete-others` | — |
| RECEIPT_REPRINT | `sales:receipt:reprint` | — |
| SHRINKAGE_APPROVAL | `shrinkage:record:approve` | SHRINKAGE_APPROVE_MAX_AMOUNT |
| STOCK_ADJUSTMENT_APPROVAL | `inventory:adjustment:approve` | ADJUSTMENT_APPROVE_MAX_AMOUNT |
| CASH_DIFFERENCE_APPROVAL | `cash:difference:approve` | — |

- `POST /auth/pin-login` exige una sesión vigente ligada a esa terminal (`POST /auth/session/terminal`).

**Segregación de funciones (servicio + CK):**
- La toma la aprueba alguien distinto de `started_by` y de todo `counted_by`.
- El ajuste lo aprueba alguien distinto del solicitante.
- La merma pendiente la aprueba alguien distinto de quien la reportó.
- La diferencia de caja la aprueba alguien distinto del cajero del turno.
- El override nunca es del propio solicitante.
- La recepción con diferencias sobre el 10 % la confirma alguien distinto de quien creó la OC. La comparación usa `purchase_orders.created_by` (columna AUD existente; sin cambio de esquema).
- Nadie modifica sus propios roles ni límites, ni edita su propio registro de usuario (anti-escalada, ver arriba).

### D.5 Eventos auditados (`audit_log.action`)

Todos guardan usuario, fecha, IP, local, terminal y traceId.

| Acción | Entidad | old_value / new_value / extra |
|---|---|---|
| AUTH_LOGIN_SUCCESS / AUTH_LOGIN_FAILED / AUTH_LOCKED / AUTH_PIN_LOCKED | user | método, motivo, IP |
| AUTH_TOKEN_REUSE_DETECTED | user | family_id |
| USER_CREATED / USER_UPDATED / USER_DISABLED / USER_CREDENTIALS_RESET | user | campos antes y después (sin hashes) |
| SESSIONS_REVOKED | user | usuario afectado y cantidad de sesiones revocadas |
| COMPANY_UPDATED | company | campos antes y después |
| STORE_CHANGED | store | creación, edición, activación o desactivación: antes y después |
| ROLE_PERMISSIONS_CHANGED / ROLE_LIMIT_CHANGED | role | lista de permisos o límites antes y después |
| SETTING_CHANGED / TAX_CHANGED | setting/tax | valor antes y después |
| PRODUCT_PRICE_CHANGED | product | price_gross antes y después, motivo |
| PRODUCT_COST_CHANGED | product | avg_cost antes y después, origen |
| PRODUCT_TAXES_CHANGED / PRODUCT_DEACTIVATED | product | antes y después |
| PROMOTION_CHANGED | promotion | antes y después |
| SALE_DISCOUNT_OVER_LIMIT | sale | línea, % y monto, autorización |
| SALE_VOIDED | sale | estado y totales, motivo, autorización |
| SALE_RETURNED | sale_return | líneas, montos, reingreso, autorización |
| SALE_RESTRICTED_AGE_CONFIRMED | sale | productos restringidos |
| RECEIPT_REPRINTED | sale | número de reimpresión |
| SUSPENDED_SALE_DELETED | suspended_sale | carrito |
| STOCK_ADJUSTMENT_REQUESTED / APPROVED / REJECTED | stock_adjustment | líneas, deltas, costo |
| INVENTORY_COUNT_APPROVED / REJECTED | inventory_count | diferencias y valorización |
| TRANSFER_SENT / TRANSFER_RECEIVED | stock_transfer | cantidades y diferencias |
| LOT_UPDATED | lot | número y vencimiento antes y después, motivo |
| SHRINKAGE_CREATED / APPROVED / REJECTED | shrinkage | tipo, cantidad, costo, evidencia |
| GOODS_RECEIPT_CONFIRMED | goods_receipt | diferencias y costos; marca si hubo sobre-recepción o si no tiene OC |
| PRODUCT_SUPPLIER_CHANGED | product_supplier | alta, edición, preferido y desactivación: antes y después (incluye costo) |
| PURCHASE_ORDER_SENT / CANCELLED | purchase_order | estado y total |
| CASH_SESSION_OPENED / CLOSED | cash_session | montos y arqueo |
| CASH_OUT / CASH_IN | cash_movement | monto, motivo, autorización |
| CASH_DIFFERENCE_APPROVED | cash_session | diferencia |
| CUSTOMER_CREDIT_LIMIT_CHANGED / CREDIT_OVER_LIMIT | customer | cupo antes y después, deuda |
| CUSTOMER_ACCOUNT_ADJUSTED | customer | monto, motivo, deuda antes y después |
| OVERRIDE_GRANTED / OVERRIDE_DENIED | supervisor_authorization | acción y contexto |
| DATA_EXPORTED / DATA_IMPORTED | report/import_job | tipo, filtros, filas |

