# Historias de usuario — POS Minimarket

> Parte del diseño de POS Minimarket. Las secciones conservan su letra (A–J) para que las referencias cruzadas funcionen entre documentos: A, C, E, H, I en [PLAN.md](PLAN.md) · B en [MODELO_DATOS.md](MODELO_DATOS.md) · D en [ROLES_PERMISOS.md](ROLES_PERMISOS.md) · F en [API.md](API.md) · G en [HISTORIAS_USUARIO.md](HISTORIAS_USUARIO.md).

## G. Historias de usuario

**Formato:**
- Prioridad: M = Must, S = Should, C = Could. Fase (F) según H.2.
- En los criterios, D = Dado, C = Cuando, E = Entonces.
- Todas las historias suponen implícitamente el alcance por local de HU-SEG-06.

### Catálogo (CAT)
| ID | Historia | P | F | Permisos | Criterios de aceptación |
|---|---|---|---|---|---|
| HU-CAT-01 | Como gerente quiero crear, editar y desactivar productos para mantener el catálogo | M | 1 | catalog:product:create/update/deactivate | (1) D datos válidos, C POST /products, E 201 y filas de stock en 0 para cada local. (2) D un SKU ya existente, C crear, E 409 `DUPLICATED_VALUE`. (3) D un producto con ventas, C desactivarlo, E queda `active=false` y no se vende (`PRODUCT_INACTIVE`) |
| HU-CAT-02 | Como bodeguero quiero asignar varios códigos de barras (EAN-13, EAN-8, internos) para escanear cualquier presentación | M | 1 | catalog:product:update | (1) D un EAN-13 válido, C agregarlo, E se resuelve en el POS. (2) D un dígito verificador inválido, E 422 `BARCODE_INVALID`. (3) D un código usado por otro producto, E 409 `DUPLICATED_VALUE` |
| HU-CAT-03 | Como gerente quiero cambiar el precio de venta con historial | M | 1 | catalog:product:update-price | (1) C PUT /price, E se crea `price_history` y la auditoría PRODUCT_PRICE_CHANGED. (2) D una versión obsoleta, E 409 `CONCURRENT_MODIFICATION`. (3) D un usuario sin permiso, E 403 |
| HU-CAT-04 | Como gerente quiero ver el costo promedio y el margen calculado | M | 1 | catalog:cost:read | (1) D tiene permiso, E `avgCost` y `marginPct = (neto − costo)/neto`. (2) D un supervisor sin permiso, E esos campos llegan null |
| HU-CAT-05 | Como gerente quiero productos pesables con códigos de balanza para vender por kg | M | 1 | catalog:product:create, config:scale-barcode:manage | (1) D prefijo 20 de tipo WEIGHT, C escanear `20PPPPPWWWWWC`, E cantidad = W/1000 kg. (2) D tipo PRICE, E cantidad = precio/price_gross a 3 decimales. (3) D un dígito verificador inválido, E 404 `CODE_NOT_FOUND` |
| HU-CAT-06 | Como gerente quiero definir un pack que descuente N unidades del producto base | M | 1 | catalog:product:create | (1) D un pack de 6 con stock base de 12, C vender 1 pack, E el base queda en 6 (RN-05). (2) D un base que también es pack, E 400 `VALIDATION_ERROR` |
| HU-CAT-07 | Como administrador quiero impuestos configurables por producto (IVA, ILA, IABA) | M | 1 | config:tax:manage | (1) D una bebida con IVA e IABA 18 %, C cotizar $1.370, E neto = round(1370/1.37) = $1.000 y el desglose suma 370. (2) D un cambio de tasa, E auditoría TAX_CHANGED |
| HU-CAT-08 | Como gerente quiero marcar productos de venta restringida | M | 1 | catalog:product:update | (1) D `restrictedType=ALCOHOL`, E el POS exige confirmar la edad (ver HU-POS-13) |
| HU-CAT-09 | Como gerente quiero activar el control por lote y vencimiento | S | 2 | catalog:product:update | (1) D stock existente, C activar el control, E se crea el lote `SIN-LOTE` con todo el stock (RN-04). (2) D lotes con saldo, C desactivarlo, E 409 `ENTITY_IN_USE` (lo decide `LotControlGuard`, A.4) |
| HU-CAT-10 | Como gerente quiero importar productos desde CSV o Excel con validación previa | S | 4 | catalog:product:import | (1) D un archivo con 2 filas inválidas, C `dryRun`, E `errorRows=2` con fila y campo, sin escribir nada. (2) D un archivo válido, C confirmar, E todo se aplica en una sola transacción. (3) D un archivo de más de 20 MB, E `FILE_INVALID` |
| HU-CAT-11 | Como gerente quiero exportar el catálogo | S | 4 | catalog:product:export | (1) C exportar xlsx, E se respetan los filtros y la auditoría DATA_EXPORTED. (2) D sin `catalog:cost:read`, E el archivo sale sin columnas de costo |
| HU-CAT-12 | Como gerente quiero categorías, subcategorías y marcas | M | 1 | catalog:category:manage, catalog:brand:manage | (1) C crear una subcategoría, E aparece en el árbol. (2) D un intento de tercer nivel, E 400 `VALIDATION_ERROR`. (3) D una categoría con productos activos, C desactivarla, E 409 `ENTITY_IN_USE` |
| HU-CAT-13 | Como bodeguero quiero subir la imagen del producto | C | 1 | catalog:product:update | (1) D un PNG ≤ 5 MB, E queda la `imageUrl`. (2) D un .exe renombrado a .png, E `FILE_INVALID` (magic bytes) |

### Proveedores, compras y reabastecimiento (PRV)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-PRV-01 | Como gerente quiero gestionar proveedores con RUT, condiciones, lead time y días de despacho | M | 3 | purchasing:supplier:manage | (1) D un RUT con dígito verificador inválido, E 422 `RUT_INVALID`. (2) D `deliveryWeekdays` = [1,4], E se usa R = 4 en el reabastecimiento |
| HU-PRV-02 | Como bodeguero quiero asociar productos a proveedores con costo, código, unidad de compra y mínimo | M | 3 | purchasing:product-supplier:manage | (1) D caja de 12 a $6.000, E `unitCostNet` = 500. (2) D un producto PACK, E 400 `VALIDATION_ERROR`. (3) D marcar un preferido, E el preferido anterior se desmarca |
| HU-PRV-03 | Como bodeguero quiero crear órdenes de compra en borrador | M | 3 | purchasing:order:create | (1) C crear, E queda DRAFT con los totales calculados. (2) D una OC SENT, C editarla, E 409 `INVALID_STATE_TRANSITION` |
| HU-PRV-04 | Como gerente quiero enviar o cancelar órdenes y exportarlas a PDF | M | 3 | purchasing:order:send/cancel/read | (1) C enviar con correo, E pasa a SENT y el correo queda en la outbox (visible en Mailpit). (2) D una recepción confirmada, C cancelar, E 409 `INVALID_STATE_TRANSITION`. (3) C pedir el PDF, E application/pdf |
| HU-PRV-05 | Como bodeguero quiero recibir mercadería contra la orden con sus diferencias | M | 3 | purchasing:receipt:create/confirm | (1) D 10 pedidas y 8 recibidas, E la OC queda PARTIALLY_RECEIVED y la diferencia con motivo. (2) D 12 recibidas sin permiso de sobre-recepción, E `RECEIPT_EXCEEDS_ORDER`. (3) D un reintento con la misma Idempotency-Key, E no se duplica |
| HU-PRV-06 | Como bodeguero quiero registrar lotes y vencimientos al recibir | M | 3 | purchasing:receipt:confirm | (1) D un producto con lote sin `lotNumber`, E 422 `LOT_REQUIRED`. (2) D datos válidos, E se crea el lote y su movimiento PURCHASE_RECEIPT |
| HU-PRV-07 | Como gerente quiero que la recepción actualice el costo promedio ponderado | M | 3 | purchasing:receipt:confirm | (1) D Q=10 a $500 y llegan 30 a $600, E avg = 575.0000 y `price_history` COST_RECEIPT. (2) D Q=0, E avg = costo de entrada |
| HU-PRV-08 | Como gerente quiero recibir mercadería sin orden de compra | C | 3 | purchasing:receipt:without-order | (1) D sin permiso, E 403. (2) D con permiso, E la recepción se confirma igual |
| HU-PRV-09 | Como bodeguero quiero una sugerencia automática de reabastecimiento | M | 6 | replenishment:run:execute | (1) D los datos del ejemplo 1 de C.7, E 6 cajas, quiebre el 2026-11-14 y flags CENSORED_DAYS y CAPPED_BY_UPPER. (2) D los ejemplos 2 y 3, E los resultados de C.7. (3) D un producto sin proveedor, E queda NO_SUPPLIER y no entra en la OC |
| HU-PRV-10 | Como gerente quiero revisar y editar la sugerencia y generar borradores por proveedor | M | 6 | replenishment:run:execute, purchasing:order:create | (1) C editar las unidades finales y generar, E una OC DRAFT por proveedor con `origin=REPLENISHMENT`. (2) D una línea ya incluida en una OC, C editarla, E 409 `INVALID_STATE_TRANSITION` |
| HU-PRV-11 | Como gerente quiero configurar los parámetros por nivel | S | 6 | replenishment:params:manage | (1) D W=60 en la categoría y W=30 global, E un producto de esa categoría usa 60. (2) C borrar el GLOBAL, E 400 `VALIDATION_ERROR` |

### Inventario (INV)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-INV-01 | Como encargado quiero ver el stock por local | M | 1 | inventory:stock:read | (1) E filtros por estado BELOW_MIN y ZERO. (2) D un local no asignado en `X-Store-Id`, E 403 `STORE_ACCESS_DENIED` |
| HU-INV-02 | Como encargado quiero el kardex completo con usuario, fecha y motivo | M | 1 | inventory:kardex:read | (1) D una venta de 3 unidades, E movimiento SALE con −3 y el `balance_after` correcto. (2) E el kardex no se puede editar (no hay endpoint de escritura) |
| HU-INV-03 | Como dueño quiero que el stock nunca quede negativo, incluso con ventas simultáneas | M | 1 | — | (1) D stock 5 y dos ventas concurrentes de 3, E una sola tiene éxito y la otra recibe 422 `STOCK_INSUFFICIENT`. (2) D ventas cruzadas A+B y B+A en paralelo, E no hay deadlock (orden por `product_id`) |
| HU-INV-04 | Como encargado quiero stock mínimo y máximo con alertas | M | 1 | inventory:stock-limits:update | (1) D mínimo 10 y stock 8, E aparece en /stock/alerts y en el dashboard. (2) D máximo menor que el mínimo, E 400 `VALIDATION_ERROR` |
| HU-INV-05 | Como bodeguero quiero solicitar ajustes que apruebe un supervisor | M | 2 | inventory:adjustment:create/approve | (1) C solicitar, E queda PENDING_APPROVAL sin mover stock. (2) D el mismo usuario intenta aprobar, E 422 `SEGREGATION_OF_DUTIES`. (3) D un monto sobre el límite del supervisor, E se requiere override |
| HU-INV-06 | Como encargado quiero tomas de inventario total o cíclica con teórico contra contado | M | 2 | inventory:count:* | (1) D una toma ciega, E el contador no ve el teórico. (2) D se vendió durante la toma, C aprobar, E se aplica solo el delta (RN-20). (3) D quien contó intenta aprobar, E SOD |
| HU-INV-07 | Como bodeguero quiero transferir stock entre locales | M | 2 | inventory:transfer:* | (1) C enviar, E TRANSFER_OUT en el origen y el destino lo ve en tránsito. (2) D 10 enviadas y 9 recibidas, E TRANSFER_IN de lo recibido y se invoca `TransferDifferenceHandler` (la merma se verifica en la HU de mermas, fase 4). (3) D recibir desde el local de origen, E 403 `ACCESS_DENIED` |
| HU-INV-08 | Como encargado quiero alertas de productos próximos a vencer | M | 2 | inventory:expiry:read | (1) D 15 días configurados y un lote que vence en 10, E aparece en la alerta. (2) D un lote con saldo 0, E no aparece |
| HU-INV-09 | Como bodeguero quiero salida sugerida por FEFO | S | 2 | inventory:stock:read | (1) D lotes que vencen el 1/12 y el 15/12, C vender, E se consume primero el del 1/12. (2) C pedir /lots/fefo, E las asignaciones y el faltante |

### Mermas (MER)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-MER-01 | Como bodeguero quiero registrar mermas con tipo, cantidad, lote y responsable | M | 4 | shrinkage:record:create | (1) D $8.000 con límite de $10.000, E se aprueba sola, `autoApproved=true` y movimiento SHRINKAGE. (2) D un producto con lote sin lote informado, E `LOT_REQUIRED` |
| HU-MER-02 | Como supervisor quiero aprobar o rechazar mermas sobre el monto configurado | M | 4 | shrinkage:record:approve | (1) D pendiente de $50.000, C aprobar, E se descuenta el stock y se audita. (2) D el mismo que la reportó, E SOD. (3) D supera el límite del supervisor, E se requiere override. (4) D una merma sistémica TRANSFER_DIFFERENCE en `PENDING_APPROVAL`, C aprobar quien no la reportó, E queda APPROVED sin movimiento de stock; D rechazarla, E solo queda la anotación y no cuenta en los reportes |
| HU-MER-03 | Como bodeguero quiero adjuntar una foto de evidencia | S | 4 | shrinkage:record:create | (1) D un jpg, E la `evidenceUrl` es visible solo con shrinkage:record:read |
| HU-MER-04 | Como gerente quiero reportes de merma en pesos y en % de ventas por tipo, producto, categoría, local y período | M | 7 | report:shrinkage:read | (1) D merma $10.000 y ventas netas $1.000.000, E `pctOfSales` = 1,00. (2) C exportar, E xlsx |
| HU-MER-05 | Como gerente quiero ver los productos con más merma | S | 7 | report:shrinkage:read | (1) E ranking por costo con `limit` |

### Punto de venta (POS)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-POS-01 | Como cajero quiero vender escaneando con lector | M | 1 | sales:pos:operate | (1) D una ráfaga de menos de 30 ms terminada en Enter, E se agrega el ítem sin hacer clic. (2) D el mismo código dos veces, E sube la cantidad. (3) D un código desconocido, E aviso `CODE_NOT_FOUND` sin perder el foco |
| HU-POS-02 | Como cajero quiero buscar por nombre, código o atajo | M | 1 | sales:pos:operate | (1) D "coca" (con o sin tildes), E resultados en menos de 300 ms con 20 ítems. (2) D un atajo "PAN", E se agrega directamente |
| HU-POS-03 | Como cajero quiero editar cantidades y quitar líneas | M | 1 | sales:pos:operate | (1) D la tecla F4 y cantidad 3, E la línea queda en 3. (2) D una cantidad decimal en un producto UNIT, E 400 `VALIDATION_ERROR` |
| HU-POS-04 | Como cajero quiero suspender y retomar ventas | M | 1 | sales:pos:operate | (1) C suspender, E aparece en la lista de la terminal sin afectar el stock. (2) D 5 suspendidas, C la sexta, E `SUSPENDED_LIMIT_REACHED`. (3) D vencida la TTL, E se borra |
| HU-POS-05 | Como cajero quiero aplicar descuentos dentro de mi límite | M | 1 | sales:discount:apply | (1) D 5 % con límite de 5 %, E se aplica. (2) D un producto con `allowManualDiscount=false`, E 400 `VALIDATION_ERROR` |
| HU-POS-06 | Como cajero quiero que un supervisor autorice descuentos sobre mi límite | M | 1 | sales:discount:override | Flujo 2 de F.4. (1) D 12 %, E 422 `OVERRIDE_REQUIRED`. (2) D un token usado dos veces, E `OVERRIDE_INVALID` |
| HU-POS-07 | Como cajero quiero que las promociones se apliquen solas (%, monto, N×M, precio por cantidad, combo) con vigencia | M | 5 | promotions:promotion:read | (1) D un 3×2 a $1.000, C llevar 3, E descuento $1.000. (2) D fuera del horario, E no se aplica. (3) D dos promociones no acumulables, E gana la de mayor beneficio |
| HU-POS-08 | Como cajero quiero cobrar en efectivo con vuelto y redondeo legal | M | 1 | sales:pos:operate | (1) D total $1.995 en efectivo, E paga $1.990 (ajuste −5). (2) D $1.996, E $2.000 (+4). (3) D lo entregado menor que lo adeudado, E `PAYMENT_MISMATCH` |
| HU-POS-09 | Como cajero quiero cobrar con débito, crédito, transferencia o un pago mixto | M | 1 | sales:pos:operate | Flujo 1 de F.4. (1) D un monto terminado en 13, E `PAYMENT_DECLINED`. (2) D una suma que no cuadra, E `PAYMENT_MISMATCH`. (3) E nunca se guarda el PAN, solo `last4` |
| HU-POS-10 | Como cajero quiero imprimir el ticket de 80 mm y reimprimirlo con autorización | M | 1 | sales:receipt:reprint | (1) C finalizar, E se abre el diálogo de impresión con el ancho de 80 mm. (2) D un cajero reimprime, E se pide override RECEIPT_REPRINT y el ticket dice "COPIA" |
| HU-POS-11 | Como cajero quiero enviar el comprobante por correo | C | 5 | sales:pos:operate | (1) C enviar, E 202 y el correo aparece en Mailpit. (2) D un SMTP caído, E la venta no falla y se reintenta |
| HU-POS-12 | Como dueño quiero una boleta electrónica simulada desacoplada | M | 5 | sales:tax-document:read/retry | (1) D una venta completada, E un documento 39 ISSUED con folio correlativo. (2) D el proveedor falla, E queda en ERROR y el reintento funciona |
| HU-POS-13 | Como cajero quiero confirmar la mayoría de edad al vender alcohol o tabaco | M | 1 | sales:pos:operate | Flujo 3 de F.4. (1) D sin confirmación, E `AGE_CONFIRMATION_REQUIRED` |
| HU-POS-14 | Como supervisor quiero anular ventas con motivo y reingreso de stock | M | 5 | sales:sale:void | (1) D el turno original abierto, E la venta queda VOIDED, el stock reingresa (SALE_VOID) y se reversa el pago. (2) D el turno cerrado, E `VOID_NOT_ALLOWED`. (3) D un cajero, E se pide override SALE_VOID |
| HU-POS-15 | Como supervisor quiero devoluciones parciales o totales | M | 5 | sales:return:create | Flujo 4 de F.4. (1) D más de lo devolvible, E `RETURN_QUANTITY_EXCEEDED`. (2) D 31 días con máximo 30, E `RETURN_WINDOW_EXPIRED`. (3) D `restock=false`, E merma sistémica DAMAGED en `PENDING_APPROVAL`, sin movimiento (J-01, B.6) |
| HU-POS-16 | Como cajero quiero asociar un cliente y vender a fiado dentro del cupo | S | 5 | customers:credit:sell | (1) D cupo $50.000 y deuda $45.000, C fiado de $10.000, E 422 `CREDIT_LIMIT_EXCEEDED` u override CREDIT_OVER_LIMIT. (2) D un cliente sin crédito habilitado, E `CREDIT_NOT_ENABLED` |
| HU-POS-17 | Como cajero quiero registrar abonos y ver el estado de deuda | S | 5 | customers:credit:payment | (1) D deuda $20.000, C abonar $25.000, E `PAYMENT_EXCEEDS_DEBT`. (2) D un abono en efectivo, E suma al esperado del turno |
| HU-POS-18 | Como dueño quiero que una venta no se duplique si se reintenta | M | 1 | — | (1) D la misma `Idempotency-Key` y el mismo cuerpo, E la misma respuesta sin una segunda venta. (2) D la misma clave con otro cuerpo, E `IDEMPOTENCY_CONFLICT` |
| HU-POS-19 | Como cajero quiero vender productos pesables | M | 1 | sales:pos:operate | (1) D 0,750 kg a $8.990/kg, E línea = round(6742,5) = $6.743. (2) D peso escrito a mano en un producto UNIT, E 400 `VALIDATION_ERROR` |
| HU-POS-20 | Como cajero quiero operar todo el POS solo con teclado | M | 1 | sales:pos:operate | (1) E atajos: F1 ayuda, F2 buscar, F4 cantidad, F6 descuento, F7 confirmar edad, F8 suspender, F9 retomar, F10 pagar, F12 finalizar, Esc cancelar, Supr quitar línea. (2) E después de cada acción el foco vuelve al input de escaneo. (3) E una venta completa sin mouse (prueba e2e) |

### Caja y turnos (CAJ)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-CAJ-01 | Como cajero quiero abrir caja con un monto inicial en mi terminal | M | 1 | cash:session:open | (1) C abrir, E queda OPEN. (2) D la terminal ya tiene un turno abierto, E `CASH_SESSION_ALREADY_OPEN`. (3) D sin turno abierto, C vender, E `CASH_SESSION_NOT_OPEN` |
| HU-CAJ-02 | Como cajero quiero registrar ingresos y retiros de efectivo con motivo | M | 1 | cash:movement:* | (1) D un retiro de $150.000 con límite de $100.000, E se pide override. (2) D un retiro mayor que el efectivo esperado, E `CASH_OUT_EXCEEDS_AVAILABLE` |
| HU-CAJ-03 | Como cajero quiero cerrar con arqueo por medio de pago | M | 1 | cash:session:close | Flujo 5 de F.4. (1) E esperado contra contado por medio de pago y diferencia = contado − esperado. (2) D un CASH sin informar, E 400 `VALIDATION_ERROR` |
| HU-CAJ-04 | Como supervisor quiero aprobar las diferencias que superan la tolerancia | M | 1 | cash:difference:approve | (1) D tolerancia $500 y diferencia −$2.000, E `requiresDifferenceApproval`. (2) D el propio cajero aprueba, E SOD |
| HU-CAJ-05 | Como cajero quiero un comprobante de cierre imprimible | M | 1 | cash:session:read | (1) E un PDF con los totales y la firma |
| HU-CAJ-06 | Como encargado quiero el historial de turnos | S | 1 | cash:session:read | (1) E filtros por cajero, terminal y diferencia. (2) D un cajero, E solo ve los propios |
| HU-CAJ-07 | Como supervisor quiero cerrar la caja de otro cajero | S | 1 | cash:session:close-others | (1) D sin permiso, E 403. (2) D con permiso, E se audita CASH_SESSION_CLOSED con `closed_by` distinto del cajero |

### Usuarios, roles y seguridad (SEG)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-SEG-01 | Como usuario quiero iniciar sesión con JWT y refresh rotativo | M | 1 | — | (1) C login, E access de 15 min y cookie HttpOnly. (2) D un refresh reutilizado, E se revoca la familia y `TOKEN_INVALID` |
| HU-SEG-02 | Como dueño quiero bloqueo tras intentos fallidos y límite de intentos de login | M | 1 | — | (1) D 5 fallos, E 422 `ACCOUNT_LOCKED` por 15 min. (2) D 11 intentos por minuto desde una IP, E 429 `RATE_LIMITED`. (3) D 5 fallos de PASSWORD en `/auth/overrides` contra un supervisor, E su cuenta queda bloqueada igual que en el login |
| HU-SEG-03 | Como cajero quiero cambiar de usuario con PIN en la terminal | M | 1 | sales:pos:operate | (1) E un token de scope POS que no permite /users (403 `ACCESS_DENIED`). (2) D 5 PIN erróneos, E 422 `PIN_LOCKED` y se desbloquea solo tras `security.pin.lockout_minutes`. (3) D una sesión sin terminal ligada (`POST /auth/session/terminal`), C pin-login, E 422 `TERMINAL_INVALID` |
| HU-SEG-04 | Como gerente quiero gestionar usuarios, roles y locales asignados | M | 1 | security:user:* | (1) D un gerente intenta asignar ADMIN o un rol con permisos que no tiene, E 403 `ACCESS_DENIED`. (2) D crear un usuario, E clave temporal y `mustChangePassword`. (3) D un usuario edita su propio registro, E 422 `SEGREGATION_OF_DUTIES`. (4) D el último ADMIN activo, C desactivarlo, E 409 `ENTITY_IN_USE` |
| HU-SEG-05 | Como administrador quiero roles editables con permisos granulares | M | 1 | security:role:manage | (1) C quitar sales:sale:void a SUPERVISOR, E se aplica desde el siguiente refresh (≤ 15 min). (2) D un rol con usuarios, C borrarlo, E `ENTITY_IN_USE` |
| HU-SEG-06 | Como dueño quiero que cada usuario opere solo en sus locales | M | 1 | security:store:all-access | (1) D `X-Store-Id` no asignado, E 403 `STORE_ACCESS_DENIED`. (2) D un administrador, E accede a todos |
| HU-SEG-07 | Como supervisor quiero autorizar acciones con mi PIN en la caja del cajero | M | 1 | según D.4 / J-03 | (1) D un supervisor sin el permiso de la acción, E `OVERRIDE_INVALID`. (2) D un token vencido (más de 5 min), E `OVERRIDE_INVALID`. (3) E se registra en `supervisor_authorizations` y en la auditoría |
| HU-SEG-08 | Como administrador quiero consultar la auditoría inmutable | M | 1 | security:audit:read | (1) E filtros por acción, entidad y usuario. (2) D un UPDATE directo en la BD, E el trigger lo rechaza |
| HU-SEG-09 | Como administrador quiero verificar la cadena de hash | S | 1 | security:audit:verify | (1) D la cadena intacta, E `valid=true`. (2) D una fila manipulada por el superusuario, E `firstBrokenId` |
| HU-SEG-10 | Como usuario quiero cambiar mi contraseña (obligatorio la primera vez) | M | 1 | — | (1) D una clave débil, E `PASSWORD_POLICY`. (2) D `mustChangePassword`, E el frontend bloquea la navegación hasta cambiarla |
| HU-SEG-11 | Como gerente quiero revocar las sesiones de un usuario | S | 1 | security:session:revoke | (1) C revocar, E el siguiente refresh responde `TOKEN_INVALID` |

### Reportes y dashboard (REP)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-REP-01 | Como encargado quiero un dashboard del día | M | 7 | report:dashboard:read | (1) E ventas, ticket promedio, transacciones, ventas por hora, top 10 y alertas. (2) E carga en menos de 1 s con la semilla de demo |
| HU-REP-02 | Como gerente quiero ventas por rango y local | M | 7 | report:sales:read | (1) D groupBy=day, E las filas cuadran con la suma de ventas menos devoluciones. (2) D un rango de 400 días, E 400 `VALIDATION_ERROR` |
| HU-REP-03 | Como gerente quiero ventas por cajero y por medio de pago | M | 7 | report:sales:read | (1) E voids, returns y overrides por cajero |
| HU-REP-04 | Como gerente quiero margen y utilidad | M | 7 | report:margin:read | (1) E margen = neto − costo de la línea (snapshot). (2) D un supervisor, E 403 |
| HU-REP-05 | Como gerente quiero los más y menos vendidos y los productos sin rotación | M | 7 | report:sales:read, report:inventory:read | (1) D days=30, E productos con stock y sin venta en 30 días |
| HU-REP-06 | Como gerente quiero un análisis ABC | S | 7 | report:sales:read | (1) E clases según el 80 % y 95 % acumulado. (2) D metric=margin sin permiso, E 403 |
| HU-REP-07 | Como gerente quiero la valorización del inventario | M | 7 | report:inventory:read | (1) E Σ stock × avg_cost por categoría y local |
| HU-REP-08 | Como gerente quiero compras por proveedor | S | 7 | report:purchasing:read | (1) E fill rate = recibido / pedido |
| HU-REP-09 | Como gerente quiero diferencias de caja por cajero | M | 7 | report:cash:read | (1) E solo los turnos con diferencia distinta de 0 si `withDifference` |
| HU-REP-10 | Como gerente quiero exportar cualquier reporte a Excel o PDF | M | 7 | report:export:execute | (1) E el archivo respeta los filtros y queda la auditoría DATA_EXPORTED. (2) D sin permiso, E 403 |

### Configuración (CFG)
| ID | Historia | P | F | Permisos | Criterios |
|---|---|---|---|---|---|
| HU-CFG-01 | Como administrador quiero editar los datos de la empresa | M | 1 | config:company:update | (1) D un RUT inválido, E `RUT_INVALID`. (2) E los datos salen en el ticket |
| HU-CFG-02 | Como administrador quiero gestionar locales y terminales | M | 1 | config:store:manage, config:terminal:manage | (1) C crear un local, E stock en 0 para todos los productos. (2) D una terminal con turno abierto, C desactivarla, E `ENTITY_IN_USE` |
| HU-CFG-03 | Como administrador quiero gestionar impuestos | M | 1 | config:tax:manage | Ver HU-CAT-07 |
| HU-CFG-04 | Como gerente quiero configurar el formato del ticket por local | S | 1 | config:settings:update | (1) C cambiar `ticket.footer_lines` del local, E el ticket lo refleja. (2) D un gerente cambia una clave GLOBAL, E 403 |
| HU-CFG-05 | Como gerente quiero los umbrales de alertas (vencimiento, tolerancia de caja) | S | 1 | config:settings:update | (1) D un valor fuera de rango, E 422 |
| HU-CFG-06 | Como administrador quiero límites de descuento, merma y retiro por rol | M | 1 | config:role-limit:manage | (1) C cambiar DISCOUNT_ITEM_MAX_PCT del CAJERO a 10, E el quote lo respeta. (2) E auditoría ROLE_LIMIT_CHANGED |
| HU-CFG-07 | Como gerente quiero configurar los prefijos de balanza | M | 1 | config:scale-barcode:manage | Ver HU-CAT-05 |

### Resumen y trazabilidad

| Módulo | Must | Should | Could | Total |
|---|---|---|---|---|
| CAT | 9 | 3 | 1 | 13 |
| PRV | 9 | 1 | 1 | 11 |
| INV | 8 | 1 | 0 | 9 |
| MER | 3 | 2 | 0 | 5 |
| POS | 17 | 2 | 1 | 20 |
| CAJ | 5 | 2 | 0 | 7 |
| SEG | 9 | 2 | 0 | 11 |
| REP | 8 | 2 | 0 | 10 |
| CFG | 5 | 2 | 0 | 7 |
| **Total** | **73** | **17** | **3** | **93** |

No hay historias Won't en este alcance; lo excluido está listado en A.1.

| Requisito del enunciado | HU |
|---|---|
| CRUD producto, SKU, códigos, marca, categoría, imagen, estado | CAT-01, 02, 12, 13 |
| Precio, costo, margen, historial | CAT-03, 04; PRV-07 |
| Unidad, pesable, pack | CAT-05, 06; POS-19 |
| Impuestos configurables | CAT-07; CFG-03 |
| Venta restringida | CAT-08; POS-13 |
| Lote y vencimiento opcional | CAT-09; INV-08, 09 |
| Import y export | CAT-10, 11 |
| Proveedores, producto-proveedor | PRV-01, 02 |
| OC con estados y PDF | PRV-03, 04 |
| Recepción, diferencias, lotes, costo promedio | PRV-05, 06, 07, 08 |
| Sugerencia de reabastecimiento | PRV-09, 10, 11 |
| Stock por local, kardex, no negativo, atomicidad | INV-01, 02, 03 |
| Mínimo y máximo, alertas | INV-04 |
| Tomas de inventario | INV-06 |
| Ajustes aprobados | INV-05 |
| Transferencias | INV-07 |
| Vencimientos y FEFO | INV-08, 09 |
| Mermas: registro, aprobación, evidencia, reportes | MER-01 a 05 |
| POS con teclado y lector, búsqueda, carrito | POS-01, 02, 03, 20 |
| Suspender y retomar | POS-04 |
| Descuentos y override | POS-05, 06; SEG-07 |
| Promociones | POS-07 |
| Medios de pago, vuelto, mixto, tarjeta simulada | POS-08, 09 |
| Ticket 80 mm y correo | POS-10, 11 |
| Boleta simulada | POS-12 |
| Anulación y devoluciones | POS-14, 15 |
| Clientes y fiado | POS-16, 17 |
| Turnos: apertura, movimientos, cierre, arqueo, historial | CAJ-01 a 07 |
| Roles y RBAC, usuarios y locales | SEG-04, 05, 06 |
| JWT, BCrypt, bloqueo, PIN | SEG-01, 02, 03, 10, 11 |
| Auditoría | SEG-08, 09 |
| Dashboard y reportes, exportación | REP-01 a 10; CAJ-06 |
| Configuración | CFG-01 a 07; PRV-11 |
| Idempotencia (no funcional) | POS-18 |

---
