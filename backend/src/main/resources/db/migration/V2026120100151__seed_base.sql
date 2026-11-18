-- =============================================================================
-- T15: semilla base (datos maestros indispensables para arrancar el sistema)
--
-- Contenido (en este orden, por las FK):
--   1. permissions       catalogo de 95 permisos (ROLES_PERMISOS D.1) con modulo y bandera sensible.
--   2. roles             los 5 roles base, marcados system = true (no se borran).
--   3. role_permissions  matriz por rol (D.2): una fila por cada celda que no es denegada
--                        (check, <= y P significan "tiene el permiso"; el alcance fino, el
--                        limite o "solo lo propio" lo aplica el servicio).
--   4. role_limits       5 roles x 10 codigos de D.3 = 50 filas. Toda combinacion tiene fila:
--                        el 0 es explicito (fail-closed) y NULL significa sin limite (infinito).
--   5. taxes             IVA e impuestos adicionales (ILA / IABA), ADR-30.
--   6. settings          los 19 parametros de MODELO_DATOS B.1 con su default, scope GLOBAL.
--   7. scale_barcode_configs  una configuracion de balanza por defecto (prefijo 20, peso).
--   8. document_sequences     solo las secuencias globales de folios tributarios (ver nota).
--   9. users / user_roles     el usuario admin con rol ADMIN.
--
-- Fuente de verdad: docs/ROLES_PERMISOS.md (D.1 a D.3) y docs/MODELO_DATOS.md (B.1 a B.3).
-- La clase Permissions (shared/security) debe coincidir con la lista de la seccion 1;
-- PermissionsSeedTest lo verifica leyendo este archivo desde el classpath.
--
-- Decisiones:
--   * company NO se siembra: ningun objeto de esta semilla la referencia (users no
--     tiene FK a company) y sus datos (RUT, razon social, giro) son de la empresa
--     demo (T43, db/demo). La app arranca sin empresa hasta que el administrador la
--     configure o se cargue la demo.
--   * document_sequences por local (SALE, SALE_RETURN, PURCHASE_ORDER, GOODS_RECEIPT,
--     TRANSFER, INVENTORY_COUNT, STOCK_ADJUSTMENT, SHRINKAGE, CASH_SESSION) dependen de
--     un local que aun no existe, por lo que se omiten: las crea quien crea el local
--     (servicio de locales, o T43 para la demo). Aqui solo van las globales
--     (store_id NULL): FOLIO_BOLETA y FOLIO_CREDIT_NOTE (RN-29).
--   * El hash de la clave del admin llega por el placeholder Flyway admin_password_hash
--     (application.yml, spring.flyway.placeholders). Nunca se escribe una clave aqui.
--     must_change_password = true: el primer inicio de sesion obliga a cambiarla.
--
-- Flyway versionado la ejecuta una sola vez, por eso los INSERT no son idempotentes.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. permissions (D.1). Columnas: codigo, modulo (primer segmento del codigo),
--    descripcion, sensible (columna S).
-- -----------------------------------------------------------------------------
INSERT INTO permissions (code, module, description, sensitive) VALUES
    ('security:user:read', 'security', 'Ver usuarios', false),
    ('security:user:create', 'security', 'Crear usuarios', true),
    ('security:user:update', 'security', 'Editar usuarios, roles y locales', true),
    ('security:user:disable', 'security', 'Desactivar usuarios', true),
    ('security:user:reset-credentials', 'security', 'Resetear clave o PIN y desbloquear', true),
    ('security:role:read', 'security', 'Ver roles y permisos', false),
    ('security:role:manage', 'security', 'Crear, editar y borrar roles', true),
    ('security:session:revoke', 'security', 'Revocar sesiones de otros', true),
    ('security:store:all-access', 'security', 'Acceso a todos los locales', true),
    ('security:audit:read', 'security', 'Ver auditoría', true),
    ('security:audit:verify', 'security', 'Verificar la cadena de hash', true),
    ('config:company:update', 'config', 'Editar la empresa', true),
    ('config:store:manage', 'config', 'Crear y editar locales', true),
    ('config:terminal:manage', 'config', 'Gestionar terminales', false),
    ('config:tax:manage', 'config', 'Gestionar impuestos', true),
    ('config:settings:read', 'config', 'Ver parámetros', false),
    ('config:settings:update', 'config', 'Editar parámetros globales y del local', true),
    ('config:role-limit:manage', 'config', 'Editar límites por rol', true),
    ('config:scale-barcode:manage', 'config', 'Configurar códigos de balanza', false),
    ('catalog:product:read', 'catalog', 'Ver productos (sin costos)', false),
    ('catalog:product:create', 'catalog', 'Crear productos', false),
    ('catalog:product:update', 'catalog', 'Editar datos no económicos', false),
    ('catalog:product:deactivate', 'catalog', 'Activar o desactivar', false),
    ('catalog:product:update-price', 'catalog', 'Cambiar el precio de venta', true),
    ('catalog:cost:read', 'catalog', 'Ver costos y márgenes', true),
    ('catalog:cost:update', 'catalog', 'Costo manual o inicial', true),
    ('catalog:category:manage', 'catalog', 'Categorías', false),
    ('catalog:brand:manage', 'catalog', 'Marcas', false),
    ('catalog:product:import', 'catalog', 'Importación masiva', true),
    ('catalog:product:export', 'catalog', 'Exportar el catálogo', true),
    ('promotions:promotion:read', 'promotions', 'Ver promociones', false),
    ('promotions:promotion:manage', 'promotions', 'Crear y editar promociones', true),
    ('purchasing:supplier:read', 'purchasing', 'Ver proveedores', false),
    ('purchasing:supplier:manage', 'purchasing', 'Gestionar proveedores', false),
    ('purchasing:product-supplier:manage', 'purchasing', 'Producto-proveedor y costos', true),
    ('purchasing:order:read', 'purchasing', 'Ver órdenes de compra', false),
    ('purchasing:order:create', 'purchasing', 'Crear y editar borradores', false),
    ('purchasing:order:send', 'purchasing', 'Aprobar y enviar la OC', true),
    ('purchasing:order:cancel', 'purchasing', 'Cancelar la OC', true),
    ('purchasing:receipt:create', 'purchasing', 'Registrar recepción', false),
    ('purchasing:receipt:confirm', 'purchasing', 'Confirmar recepción (mueve stock y costo)', true),
    ('purchasing:receipt:without-order', 'purchasing', 'Recepción sin OC', true),
    ('purchasing:receipt:over-receive', 'purchasing', 'Recibir más de lo pedido', true),
    ('replenishment:run:execute', 'replenishment', 'Ejecutar sugerencia', false),
    ('replenishment:run:read', 'replenishment', 'Ver sugerencias', false),
    ('replenishment:params:manage', 'replenishment', 'Parámetros de reabastecimiento', false),
    ('inventory:stock:read', 'inventory', 'Ver stock', false),
    ('inventory:kardex:read', 'inventory', 'Ver kardex', false),
    ('inventory:stock-limits:update', 'inventory', 'Mínimos y máximos', false),
    ('inventory:adjustment:create', 'inventory', 'Solicitar ajuste', false),
    ('inventory:adjustment:approve', 'inventory', 'Aprobar ajuste', true),
    ('inventory:count:manage', 'inventory', 'Crear o cancelar toma', false),
    ('inventory:count:execute', 'inventory', 'Registrar conteos', false),
    ('inventory:count:approve', 'inventory', 'Aprobar toma', true),
    ('inventory:transfer:read', 'inventory', 'Ver transferencias', false),
    ('inventory:transfer:send', 'inventory', 'Crear y enviar transferencia', false),
    ('inventory:transfer:receive', 'inventory', 'Recibir transferencia', false),
    ('inventory:transfer:cancel', 'inventory', 'Cancelar transferencia en DRAFT', false),
    ('inventory:lot:update', 'inventory', 'Corregir datos de lote', true),
    ('inventory:expiry:read', 'inventory', 'Alertas de vencimiento', false),
    ('shrinkage:record:read', 'shrinkage', 'Ver mermas', false),
    ('shrinkage:record:create', 'shrinkage', 'Registrar merma', false),
    ('shrinkage:record:approve', 'shrinkage', 'Aprobar merma', true),
    ('sales:pos:operate', 'sales', 'Operar el POS (vender, suspender, retomar)', false),
    ('sales:sale:read', 'sales', 'Ver ventas', false),
    ('sales:discount:apply', 'sales', 'Descuento manual dentro del límite', false),
    ('sales:discount:override', 'sales', 'Autorizar descuento sobre el límite', true),
    ('sales:sale:void', 'sales', 'Anular venta / autorizar anulación', true),
    ('sales:return:create', 'sales', 'Devolución / autorizarla', true),
    ('sales:suspended:delete-others', 'sales', 'Borrar suspendidas ajenas', false),
    ('sales:receipt:reprint', 'sales', 'Reimprimir comprobante', false),
    ('sales:tax-document:read', 'sales', 'Ver documentos tributarios', false),
    ('sales:tax-document:retry', 'sales', 'Reintentar emisión', false),
    ('customers:customer:read', 'customers', 'Ver clientes', false),
    ('customers:customer:manage', 'customers', 'Crear y editar clientes', false),
    ('customers:credit:sell', 'customers', 'Vender a fiado', false),
    ('customers:credit:payment', 'customers', 'Registrar abono', false),
    ('customers:credit:manage-limit', 'customers', 'Habilitar o editar el cupo', true),
    ('customers:credit:override', 'customers', 'Autorizar sobre el cupo', true),
    ('cash:session:open', 'cash', 'Abrir turno', false),
    ('cash:session:close', 'cash', 'Cerrar el turno propio', false),
    ('cash:session:close-others', 'cash', 'Cerrar turno ajeno', true),
    ('cash:session:read', 'cash', 'Ver turnos', false),
    ('cash:movement:cash-in', 'cash', 'Ingreso de efectivo', false),
    ('cash:movement:cash-out', 'cash', 'Retiro de efectivo', true),
    ('cash:movement:override', 'cash', 'Autorizar retiro sobre el límite', true),
    ('cash:difference:approve', 'cash', 'Aprobar diferencia de cierre', true),
    ('report:dashboard:read', 'report', 'Dashboard', false),
    ('report:sales:read', 'report', 'Reportes de venta, cajero y medio de pago', false),
    ('report:margin:read', 'report', 'Margen, utilidad y ABC por margen', true),
    ('report:inventory:read', 'report', 'Rotación, sin rotación y valorización', true),
    ('report:purchasing:read', 'report', 'Compras por proveedor', false),
    ('report:shrinkage:read', 'report', 'Reportes de merma', false),
    ('report:cash:read', 'report', 'Diferencias de caja', false),
    ('report:export:execute', 'report', 'Exportar a Excel o PDF', true);

-- -----------------------------------------------------------------------------
-- 2. roles (D.1 cabecera). system = true: no se pueden borrar, sus permisos si se editan.
-- -----------------------------------------------------------------------------
INSERT INTO roles (code, name, description, system) VALUES
    ('ADMIN',      'Administrador', 'Acceso total al sistema, a todos los locales y sin limites numericos.', true),
    ('MANAGER',    'Gerente',       'Gerente o encargado: opera y gestiona sus locales con limites acotados.', true),
    ('SUPERVISOR', 'Supervisor',    'Autoriza overrides y aprobaciones dentro de limites; no gestiona maestros.', true),
    ('CASHIER',    'Cajero',        'Opera el POS y su propio turno de caja.', true),
    ('WAREHOUSE',  'Bodeguero',     'Gestiona inventario, compras y recepciones; no opera el POS.', true);

-- -----------------------------------------------------------------------------
-- 3. role_permissions (D.2). Se explicitan todos los permisos de cada rol (ADMIN incluido)
--    para que la matriz sea auditable directamente en este archivo.
-- -----------------------------------------------------------------------------
-- ADMIN: 95 permisos
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'security:user:read',
    'security:user:create',
    'security:user:update',
    'security:user:disable',
    'security:user:reset-credentials',
    'security:role:read',
    'security:role:manage',
    'security:session:revoke',
    'security:store:all-access',
    'security:audit:read',
    'security:audit:verify',
    'config:company:update',
    'config:store:manage',
    'config:terminal:manage',
    'config:tax:manage',
    'config:settings:read',
    'config:settings:update',
    'config:role-limit:manage',
    'config:scale-barcode:manage',
    'catalog:product:read',
    'catalog:product:create',
    'catalog:product:update',
    'catalog:product:deactivate',
    'catalog:product:update-price',
    'catalog:cost:read',
    'catalog:cost:update',
    'catalog:category:manage',
    'catalog:brand:manage',
    'catalog:product:import',
    'catalog:product:export',
    'promotions:promotion:read',
    'promotions:promotion:manage',
    'purchasing:supplier:read',
    'purchasing:supplier:manage',
    'purchasing:product-supplier:manage',
    'purchasing:order:read',
    'purchasing:order:create',
    'purchasing:order:send',
    'purchasing:order:cancel',
    'purchasing:receipt:create',
    'purchasing:receipt:confirm',
    'purchasing:receipt:without-order',
    'purchasing:receipt:over-receive',
    'replenishment:run:execute',
    'replenishment:run:read',
    'replenishment:params:manage',
    'inventory:stock:read',
    'inventory:kardex:read',
    'inventory:stock-limits:update',
    'inventory:adjustment:create',
    'inventory:adjustment:approve',
    'inventory:count:manage',
    'inventory:count:execute',
    'inventory:count:approve',
    'inventory:transfer:read',
    'inventory:transfer:send',
    'inventory:transfer:receive',
    'inventory:transfer:cancel',
    'inventory:lot:update',
    'inventory:expiry:read',
    'shrinkage:record:read',
    'shrinkage:record:create',
    'shrinkage:record:approve',
    'sales:pos:operate',
    'sales:sale:read',
    'sales:discount:apply',
    'sales:discount:override',
    'sales:sale:void',
    'sales:return:create',
    'sales:suspended:delete-others',
    'sales:receipt:reprint',
    'sales:tax-document:read',
    'sales:tax-document:retry',
    'customers:customer:read',
    'customers:customer:manage',
    'customers:credit:sell',
    'customers:credit:payment',
    'customers:credit:manage-limit',
    'customers:credit:override',
    'cash:session:open',
    'cash:session:close',
    'cash:session:close-others',
    'cash:session:read',
    'cash:movement:cash-in',
    'cash:movement:cash-out',
    'cash:movement:override',
    'cash:difference:approve',
    'report:dashboard:read',
    'report:sales:read',
    'report:margin:read',
    'report:inventory:read',
    'report:purchasing:read',
    'report:shrinkage:read',
    'report:cash:read',
    'report:export:execute'
)
WHERE r.code = 'ADMIN';

-- MANAGER: 87 permisos
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'security:user:read',
    'security:user:create',
    'security:user:update',
    'security:user:disable',
    'security:user:reset-credentials',
    'security:role:read',
    'security:session:revoke',
    'security:audit:read',
    'config:terminal:manage',
    'config:settings:read',
    'config:settings:update',
    'config:scale-barcode:manage',
    'catalog:product:read',
    'catalog:product:create',
    'catalog:product:update',
    'catalog:product:deactivate',
    'catalog:product:update-price',
    'catalog:cost:read',
    'catalog:category:manage',
    'catalog:brand:manage',
    'catalog:product:import',
    'catalog:product:export',
    'promotions:promotion:read',
    'promotions:promotion:manage',
    'purchasing:supplier:read',
    'purchasing:supplier:manage',
    'purchasing:product-supplier:manage',
    'purchasing:order:read',
    'purchasing:order:create',
    'purchasing:order:send',
    'purchasing:order:cancel',
    'purchasing:receipt:create',
    'purchasing:receipt:confirm',
    'purchasing:receipt:without-order',
    'purchasing:receipt:over-receive',
    'replenishment:run:execute',
    'replenishment:run:read',
    'replenishment:params:manage',
    'inventory:stock:read',
    'inventory:kardex:read',
    'inventory:stock-limits:update',
    'inventory:adjustment:create',
    'inventory:adjustment:approve',
    'inventory:count:manage',
    'inventory:count:execute',
    'inventory:count:approve',
    'inventory:transfer:read',
    'inventory:transfer:send',
    'inventory:transfer:receive',
    'inventory:transfer:cancel',
    'inventory:lot:update',
    'inventory:expiry:read',
    'shrinkage:record:read',
    'shrinkage:record:create',
    'shrinkage:record:approve',
    'sales:pos:operate',
    'sales:sale:read',
    'sales:discount:apply',
    'sales:discount:override',
    'sales:sale:void',
    'sales:return:create',
    'sales:suspended:delete-others',
    'sales:receipt:reprint',
    'sales:tax-document:read',
    'sales:tax-document:retry',
    'customers:customer:read',
    'customers:customer:manage',
    'customers:credit:sell',
    'customers:credit:payment',
    'customers:credit:manage-limit',
    'customers:credit:override',
    'cash:session:open',
    'cash:session:close',
    'cash:session:close-others',
    'cash:session:read',
    'cash:movement:cash-in',
    'cash:movement:cash-out',
    'cash:movement:override',
    'cash:difference:approve',
    'report:dashboard:read',
    'report:sales:read',
    'report:margin:read',
    'report:inventory:read',
    'report:purchasing:read',
    'report:shrinkage:read',
    'report:cash:read',
    'report:export:execute'
)
WHERE r.code = 'MANAGER';

-- SUPERVISOR: 48 permisos
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'config:settings:read',
    'catalog:product:read',
    'catalog:product:update',
    'promotions:promotion:read',
    'purchasing:supplier:read',
    'purchasing:order:read',
    'purchasing:receipt:create',
    'purchasing:receipt:confirm',
    'inventory:stock:read',
    'inventory:kardex:read',
    'inventory:adjustment:create',
    'inventory:adjustment:approve',
    'inventory:count:manage',
    'inventory:count:execute',
    'inventory:count:approve',
    'inventory:transfer:read',
    'inventory:transfer:receive',
    'inventory:expiry:read',
    'shrinkage:record:read',
    'shrinkage:record:create',
    'shrinkage:record:approve',
    'sales:pos:operate',
    'sales:sale:read',
    'sales:discount:apply',
    'sales:discount:override',
    'sales:sale:void',
    'sales:return:create',
    'sales:suspended:delete-others',
    'sales:receipt:reprint',
    'sales:tax-document:read',
    'customers:customer:read',
    'customers:customer:manage',
    'customers:credit:sell',
    'customers:credit:payment',
    'customers:credit:manage-limit',
    'customers:credit:override',
    'cash:session:open',
    'cash:session:close',
    'cash:session:close-others',
    'cash:session:read',
    'cash:movement:cash-in',
    'cash:movement:cash-out',
    'cash:movement:override',
    'cash:difference:approve',
    'report:dashboard:read',
    'report:sales:read',
    'report:shrinkage:read',
    'report:cash:read'
)
WHERE r.code = 'SUPERVISOR';

-- CASHIER: 17 permisos
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'catalog:product:read',
    'promotions:promotion:read',
    'inventory:stock:read',
    'inventory:count:execute',
    'shrinkage:record:create',
    'sales:pos:operate',
    'sales:sale:read',
    'sales:discount:apply',
    'customers:customer:read',
    'customers:customer:manage',
    'customers:credit:sell',
    'customers:credit:payment',
    'cash:session:open',
    'cash:session:close',
    'cash:session:read',
    'cash:movement:cash-in',
    'cash:movement:cash-out'
)
WHERE r.code = 'CASHIER';

-- WAREHOUSE: 29 permisos
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'catalog:product:read',
    'catalog:product:create',
    'catalog:product:update',
    'catalog:cost:read',
    'catalog:brand:manage',
    'purchasing:supplier:read',
    'purchasing:product-supplier:manage',
    'purchasing:order:read',
    'purchasing:order:create',
    'purchasing:receipt:create',
    'purchasing:receipt:confirm',
    'replenishment:run:execute',
    'replenishment:run:read',
    'inventory:stock:read',
    'inventory:kardex:read',
    'inventory:stock-limits:update',
    'inventory:adjustment:create',
    'inventory:count:manage',
    'inventory:count:execute',
    'inventory:transfer:read',
    'inventory:transfer:send',
    'inventory:transfer:receive',
    'inventory:transfer:cancel',
    'inventory:expiry:read',
    'shrinkage:record:read',
    'shrinkage:record:create',
    'report:inventory:read',
    'report:purchasing:read',
    'report:shrinkage:read'
)
WHERE r.code = 'WAREHOUSE';

-- -----------------------------------------------------------------------------
-- 4. role_limits (D.3). Columnas: rol, codigo, valor. NULL = sin limite (infinito);
--    0 = denegado. Montos en CLP, porcentajes de 0 a 100.
-- -----------------------------------------------------------------------------
INSERT INTO role_limits (role_id, limit_code, value)
SELECT r.id, v.limit_code, v.value
FROM (VALUES
    ('ADMIN', 'DISCOUNT_ITEM_MAX_PCT', NULL),
    ('ADMIN', 'DISCOUNT_TOTAL_MAX_PCT', NULL),
    ('ADMIN', 'DISCOUNT_TOTAL_MAX_AMOUNT', NULL),
    ('ADMIN', 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT', NULL),
    ('ADMIN', 'SHRINKAGE_APPROVE_MAX_AMOUNT', NULL),
    ('ADMIN', 'ADJUSTMENT_APPROVE_MAX_AMOUNT', NULL),
    ('ADMIN', 'CASH_OUT_MAX_AMOUNT', NULL),
    ('ADMIN', 'RETURN_MAX_AMOUNT', NULL),
    ('ADMIN', 'CREDIT_LIMIT_MAX_ASSIGN', NULL),
    ('ADMIN', 'CREDIT_ADJUSTMENT_MAX_AMOUNT', NULL),
    ('MANAGER', 'DISCOUNT_ITEM_MAX_PCT', 30),
    ('MANAGER', 'DISCOUNT_TOTAL_MAX_PCT', 20),
    ('MANAGER', 'DISCOUNT_TOTAL_MAX_AMOUNT', 50000),
    ('MANAGER', 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT', 100000),
    ('MANAGER', 'SHRINKAGE_APPROVE_MAX_AMOUNT', NULL),
    ('MANAGER', 'ADJUSTMENT_APPROVE_MAX_AMOUNT', NULL),
    ('MANAGER', 'CASH_OUT_MAX_AMOUNT', 1000000),
    ('MANAGER', 'RETURN_MAX_AMOUNT', NULL),
    ('MANAGER', 'CREDIT_LIMIT_MAX_ASSIGN', 300000),
    ('MANAGER', 'CREDIT_ADJUSTMENT_MAX_AMOUNT', 50000),
    ('SUPERVISOR', 'DISCOUNT_ITEM_MAX_PCT', 15),
    ('SUPERVISOR', 'DISCOUNT_TOTAL_MAX_PCT', 10),
    ('SUPERVISOR', 'DISCOUNT_TOTAL_MAX_AMOUNT', 20000),
    ('SUPERVISOR', 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT', 20000),
    ('SUPERVISOR', 'SHRINKAGE_APPROVE_MAX_AMOUNT', 100000),
    ('SUPERVISOR', 'ADJUSTMENT_APPROVE_MAX_AMOUNT', 50000),
    ('SUPERVISOR', 'CASH_OUT_MAX_AMOUNT', 300000),
    ('SUPERVISOR', 'RETURN_MAX_AMOUNT', 50000),
    ('SUPERVISOR', 'CREDIT_LIMIT_MAX_ASSIGN', 50000),
    ('SUPERVISOR', 'CREDIT_ADJUSTMENT_MAX_AMOUNT', 0),
    ('CASHIER', 'DISCOUNT_ITEM_MAX_PCT', 5),
    ('CASHIER', 'DISCOUNT_TOTAL_MAX_PCT', 3),
    ('CASHIER', 'DISCOUNT_TOTAL_MAX_AMOUNT', 3000),
    ('CASHIER', 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT', 0),
    ('CASHIER', 'SHRINKAGE_APPROVE_MAX_AMOUNT', 0),
    ('CASHIER', 'ADJUSTMENT_APPROVE_MAX_AMOUNT', 0),
    ('CASHIER', 'CASH_OUT_MAX_AMOUNT', 100000),
    ('CASHIER', 'RETURN_MAX_AMOUNT', 0),
    ('CASHIER', 'CREDIT_LIMIT_MAX_ASSIGN', 0),
    ('CASHIER', 'CREDIT_ADJUSTMENT_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'DISCOUNT_ITEM_MAX_PCT', 0),
    ('WAREHOUSE', 'DISCOUNT_TOTAL_MAX_PCT', 0),
    ('WAREHOUSE', 'DISCOUNT_TOTAL_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'SHRINKAGE_AUTO_APPROVE_MAX_AMOUNT', 10000),
    ('WAREHOUSE', 'SHRINKAGE_APPROVE_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'ADJUSTMENT_APPROVE_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'CASH_OUT_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'RETURN_MAX_AMOUNT', 0),
    ('WAREHOUSE', 'CREDIT_LIMIT_MAX_ASSIGN', 0),
    ('WAREHOUSE', 'CREDIT_ADJUSTMENT_MAX_AMOUNT', 0)
) AS v (role_code, limit_code, value)
JOIN roles r ON r.code = v.role_code;

-- -----------------------------------------------------------------------------
-- 5. taxes (B.3, ADR-30). code, nombre, tipo, tasa en %, codigo SII, vigencia.
--    ATENCION (R9): las tasas son datos de semilla y DEBEN verificarse contra la normativa
--    vigente antes de produccion (IVA 19 %; ILA destilados 31,5 %; ILA vinos y cervezas
--    20,5 %; IABA bebidas analcoholicas 10 %; IABA alto contenido de azucar 18 %).
--    Se pueden corregir luego con config:tax:manage sin tocar codigo.
--    sii_code: solo se informa el del IVA (14); los codigos SII de los adicionales
--    se dejan NULL hasta confirmarlos con el proveedor tributario.
--    valid_from es una fecha de inicio de vigencia convencional para la semilla.
-- -----------------------------------------------------------------------------
INSERT INTO taxes (code, name, tax_type, rate, sii_code, valid_from, valid_to, active) VALUES
    ('IVA',                       'IVA 19%',                                        'VAT',        19.0000, '14', DATE '2020-01-01', NULL, true),
    ('ILA_DESTILADOS',            'ILA destilados 31,5%',                           'ADDITIONAL', 31.5000, NULL, DATE '2020-01-01', NULL, true),
    ('ILA_VINOS_CERVEZAS',        'ILA vinos y cervezas 20,5%',                     'ADDITIONAL', 20.5000, NULL, DATE '2020-01-01', NULL, true),
    ('ILA_BEBIDAS_ANALCOHOLICAS', 'IABA bebidas analcoholicas 10%',                 'ADDITIONAL', 10.0000, NULL, DATE '2020-01-01', NULL, true),
    ('ILA_BEBIDAS_ALTO_AZUCAR',   'IABA bebidas analcoholicas alto en azucar 18%',  'ADDITIONAL', 18.0000, NULL, DATE '2020-01-01', NULL, true);

-- -----------------------------------------------------------------------------
-- 6. settings (B.1). Todos GLOBAL (store_id NULL); un local puede sobreescribir los
--    de scope GLOBAL+STORE insertando su propia fila STORE. Tipo y rango: tabla de B.1.
--    Columnas: scope, clave, valor jsonb, tipo, descripcion.
-- -----------------------------------------------------------------------------
INSERT INTO settings (scope, key, value, value_type, description) VALUES
    ('GLOBAL', 'pos.cash_rounding.enabled', 'true'::jsonb, 'BOOLEAN', 'Redondeo legal del efectivo al multiplo de 10 (RN-28). Si es false se cobra el efectivo exacto.'),
    ('GLOBAL', 'pos.suspended.max_per_terminal', '5'::jsonb, 'NUMBER', 'Maximo de ventas suspendidas por terminal (1 a 20).'),
    ('GLOBAL', 'pos.suspended.ttl_hours', '24'::jsonb, 'NUMBER', 'Horas de vida de una venta suspendida antes de expirar (1 a 72).'),
    ('GLOBAL', 'pos.return.max_days', '30'::jsonb, 'NUMBER', 'Dias maximos desde la venta para aceptar una devolucion (1 a 365).'),
    ('GLOBAL', 'ticket.header_lines', '[]'::jsonb, 'JSON', 'Lineas del encabezado del ticket (arreglo de string; max. 6 lineas de 48 caracteres).'),
    ('GLOBAL', 'ticket.footer_lines', '[]'::jsonb, 'JSON', 'Lineas del pie del ticket (arreglo de string; max. 6 lineas de 48 caracteres).'),
    ('GLOBAL', 'ticket.show_tax_breakdown', 'true'::jsonb, 'BOOLEAN', 'Mostrar el desglose de impuestos en el ticket.'),
    ('GLOBAL', 'ticket.paper_width_mm', '80'::jsonb, 'NUMBER', 'Ancho del papel termico en mm (58 a 80).'),
    ('GLOBAL', 'alerts.expiry.days_ahead', '15'::jsonb, 'NUMBER', 'Dias de anticipacion para las alertas de vencimiento (1 a 90).'),
    ('GLOBAL', 'cash.difference_tolerance', '500'::jsonb, 'NUMBER', 'Diferencia de cierre de caja tolerada sin aprobacion, en CLP (0 a 50000).'),
    ('GLOBAL', 'cash.default_opening_amount', '50000'::jsonb, 'NUMBER', 'Monto de apertura de turno sugerido, en CLP (0 a 1000000).'),
    ('GLOBAL', 'security.password.min_length', '10'::jsonb, 'NUMBER', 'Largo minimo de la contrasena (8 a 64).'),
    ('GLOBAL', 'security.lockout.max_attempts', '5'::jsonb, 'NUMBER', 'Intentos fallidos de clave antes del bloqueo (3 a 20).'),
    ('GLOBAL', 'security.lockout.minutes', '15'::jsonb, 'NUMBER', 'Minutos de bloqueo de la cuenta por intentos fallidos de clave (1 a 1440).'),
    ('GLOBAL', 'security.pin.max_attempts', '5'::jsonb, 'NUMBER', 'Intentos fallidos de PIN antes del bloqueo (3 a 10).'),
    ('GLOBAL', 'security.pin.lockout_minutes', '15'::jsonb, 'NUMBER', 'Minutos de bloqueo del PIN; vencido, se desbloquea solo (1 a 1440).'),
    ('GLOBAL', 'security.refresh.ttl_hours', '12'::jsonb, 'NUMBER', 'Vida deslizante del refresh token, en horas (1 a 72).'),
    ('GLOBAL', 'security.refresh.absolute_days', '7'::jsonb, 'NUMBER', 'Tope absoluto de la sesion, en dias (1 a 30).'),
    ('GLOBAL', 'email.ticket.enabled', 'true'::jsonb, 'BOOLEAN', 'Permitir el envio del ticket por correo.');

-- -----------------------------------------------------------------------------
-- 7. scale_barcode_configs (B.3). Configuracion por defecto de balanza: EAN-13 con
--    peso embebido, formato 20PPPPPWWWWWC (HU-CAT-05): prefijo 20, PLU de 5 digitos desde
--    la posicion 2, peso de 5 digitos desde la posicion 7 con 3 decimales (gramos -> kg) y
--    un digito verificador al final. Las posiciones se cuentan desde 0 (API.md: 0..12).
-- -----------------------------------------------------------------------------
INSERT INTO scale_barcode_configs
    (name, prefix, embedded_type, plu_start, plu_length, value_start, value_length, value_decimals, active)
VALUES
    ('Balanza peso embebido (20)', '20', 'WEIGHT', 2, 5, 7, 5, 3, true);

-- -----------------------------------------------------------------------------
-- 8. document_sequences (B.1): solo las globales (ver "Decisiones" en la cabecera).
--    Formato del folio: {prefix}-{next_value:08d}.
-- -----------------------------------------------------------------------------
INSERT INTO document_sequences (sequence_type, store_id, prefix, next_value) VALUES
    ('FOLIO_BOLETA',      NULL, 'BOL', 1),
    ('FOLIO_CREDIT_NOTE', NULL, 'NC',  1);

-- -----------------------------------------------------------------------------
-- 9. Usuario administrador inicial. password_hash viene del placeholder Flyway
--    admin_password_hash (hash BCrypt costo 12, nunca la clave en claro). Sin PIN, sin
--    email ni RUT (se completan al primer ingreso). Sin locales (user_stores): el ADMIN
--    tiene security:store:all-access.
-- -----------------------------------------------------------------------------
-- Falla temprano si el hash no es un BCrypt costo 12 (p. ej. vacio o el marcador de
-- .env.example): asi no se despliega un admin con una clave inutilizable.
DO $chk$
BEGIN
    IF '${admin_password_hash}' !~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'admin_password_hash invalido: se esperaba un hash BCrypt de costo 12 ($2a$12$... de 60 caracteres). Defina POS_ADMIN_PASSWORD_HASH con un hash valido.';
    END IF;
END
$chk$;

INSERT INTO users (username, full_name, password_hash, must_change_password, active) VALUES
    ('admin', 'Administrador', '${admin_password_hash}', true, true);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'ADMIN'
WHERE lower(u.username) = 'admin';
