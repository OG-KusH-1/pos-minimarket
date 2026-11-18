package cl.minimarket.pos.shared.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Catálogo de códigos de permiso del sistema (ROLES_PERMISOS D.1): constantes para usar en
 * {@code @PreAuthorize("hasAuthority(T(...Permissions).CATALOG_PRODUCT_READ)")} o en
 * comprobaciones programáticas, de modo que ningún código de permiso se escriba como literal.
 *
 * <p><b>Dueña única:</b> la tarea T15 (PLAN H.1). Esta clase y las filas de {@code permissions}
 * sembradas en {@code V2026120100151__seed_base.sql} deben coincidir exactamente; lo verifica
 * {@code PermissionsSeedTest}. El catálogo es de solo lectura en runtime (MODELO_DATOS B.2).
 *
 * <p><b>Cómo agregar un permiso:</b> (1) añadirlo a D.1 y D.2, (2) declarar la constante en su
 * módulo y sumarla a {@link #ALL}, (3) crear una migración nueva que inserte la fila en
 * {@code permissions} y la asigne a los roles que corresponda. Nunca editar una migración ya
 * aplicada.
 *
 * <p><b>Convención de nombre:</b> el código {@code modulo:recurso:accion} se convierte a
 * {@code MODULO_RECURSO_ACCION} (guiones a guion bajo).
 *
 * <p>Los límites numéricos por rol (D.3) no son permisos: viven en {@code role_limits}.
 */
public final class Permissions {

    private Permissions() {
    }

    // ------------------------------------------------------------------
    // Módulo Seguridad
    // ------------------------------------------------------------------

    /** Ver usuarios. */
    public static final String SECURITY_USER_READ = "security:user:read";

    /** Crear usuarios (permiso sensible: su uso se audita). */
    public static final String SECURITY_USER_CREATE = "security:user:create";

    /** Editar usuarios, roles y locales (permiso sensible: su uso se audita). */
    public static final String SECURITY_USER_UPDATE = "security:user:update";

    /** Desactivar usuarios (permiso sensible: su uso se audita). */
    public static final String SECURITY_USER_DISABLE = "security:user:disable";

    /** Resetear clave o PIN y desbloquear (permiso sensible: su uso se audita). */
    public static final String SECURITY_USER_RESET_CREDENTIALS = "security:user:reset-credentials";

    /** Ver roles y permisos. */
    public static final String SECURITY_ROLE_READ = "security:role:read";

    /** Crear, editar y borrar roles (permiso sensible: su uso se audita). */
    public static final String SECURITY_ROLE_MANAGE = "security:role:manage";

    /** Revocar sesiones de otros (permiso sensible: su uso se audita). */
    public static final String SECURITY_SESSION_REVOKE = "security:session:revoke";

    /** Acceso a todos los locales (permiso sensible: su uso se audita). */
    public static final String SECURITY_STORE_ALL_ACCESS = "security:store:all-access";

    /** Ver auditoría (permiso sensible: su uso se audita). */
    public static final String SECURITY_AUDIT_READ = "security:audit:read";

    /** Verificar la cadena de hash (permiso sensible: su uso se audita). */
    public static final String SECURITY_AUDIT_VERIFY = "security:audit:verify";

    // ------------------------------------------------------------------
    // Módulo Configuración
    // ------------------------------------------------------------------

    /** Editar la empresa (permiso sensible: su uso se audita). */
    public static final String CONFIG_COMPANY_UPDATE = "config:company:update";

    /** Crear y editar locales (permiso sensible: su uso se audita). */
    public static final String CONFIG_STORE_MANAGE = "config:store:manage";

    /** Gestionar terminales. */
    public static final String CONFIG_TERMINAL_MANAGE = "config:terminal:manage";

    /** Gestionar impuestos (permiso sensible: su uso se audita). */
    public static final String CONFIG_TAX_MANAGE = "config:tax:manage";

    /** Ver parámetros. */
    public static final String CONFIG_SETTINGS_READ = "config:settings:read";

    /** Editar parámetros globales y del local (permiso sensible: su uso se audita). */
    public static final String CONFIG_SETTINGS_UPDATE = "config:settings:update";

    /** Editar límites por rol (permiso sensible: su uso se audita). */
    public static final String CONFIG_ROLE_LIMIT_MANAGE = "config:role-limit:manage";

    /** Configurar códigos de balanza. */
    public static final String CONFIG_SCALE_BARCODE_MANAGE = "config:scale-barcode:manage";

    // ------------------------------------------------------------------
    // Módulo Catálogo
    // ------------------------------------------------------------------

    /** Ver productos (sin costos). */
    public static final String CATALOG_PRODUCT_READ = "catalog:product:read";

    /** Crear productos. */
    public static final String CATALOG_PRODUCT_CREATE = "catalog:product:create";

    /** Editar datos no económicos. */
    public static final String CATALOG_PRODUCT_UPDATE = "catalog:product:update";

    /** Activar o desactivar. */
    public static final String CATALOG_PRODUCT_DEACTIVATE = "catalog:product:deactivate";

    /** Cambiar el precio de venta (permiso sensible: su uso se audita). */
    public static final String CATALOG_PRODUCT_UPDATE_PRICE = "catalog:product:update-price";

    /** Ver costos y márgenes (permiso sensible: su uso se audita). */
    public static final String CATALOG_COST_READ = "catalog:cost:read";

    /** Costo manual o inicial (permiso sensible: su uso se audita). */
    public static final String CATALOG_COST_UPDATE = "catalog:cost:update";

    /** Categorías. */
    public static final String CATALOG_CATEGORY_MANAGE = "catalog:category:manage";

    /** Marcas. */
    public static final String CATALOG_BRAND_MANAGE = "catalog:brand:manage";

    /** Importación masiva (permiso sensible: su uso se audita). */
    public static final String CATALOG_PRODUCT_IMPORT = "catalog:product:import";

    /** Exportar el catálogo (permiso sensible: su uso se audita). */
    public static final String CATALOG_PRODUCT_EXPORT = "catalog:product:export";

    // ------------------------------------------------------------------
    // Módulo Promociones
    // ------------------------------------------------------------------

    /** Ver promociones. */
    public static final String PROMOTIONS_PROMOTION_READ = "promotions:promotion:read";

    /** Crear y editar promociones (permiso sensible: su uso se audita). */
    public static final String PROMOTIONS_PROMOTION_MANAGE = "promotions:promotion:manage";

    // ------------------------------------------------------------------
    // Módulo Compras
    // ------------------------------------------------------------------

    /** Ver proveedores. */
    public static final String PURCHASING_SUPPLIER_READ = "purchasing:supplier:read";

    /** Gestionar proveedores. */
    public static final String PURCHASING_SUPPLIER_MANAGE = "purchasing:supplier:manage";

    /** Producto-proveedor y costos (permiso sensible: su uso se audita). */
    public static final String PURCHASING_PRODUCT_SUPPLIER_MANAGE = "purchasing:product-supplier:manage";

    /** Ver órdenes de compra. */
    public static final String PURCHASING_ORDER_READ = "purchasing:order:read";

    /** Crear y editar borradores. */
    public static final String PURCHASING_ORDER_CREATE = "purchasing:order:create";

    /** Aprobar y enviar la OC (permiso sensible: su uso se audita). */
    public static final String PURCHASING_ORDER_SEND = "purchasing:order:send";

    /** Cancelar la OC (permiso sensible: su uso se audita). */
    public static final String PURCHASING_ORDER_CANCEL = "purchasing:order:cancel";

    /** Registrar recepción. */
    public static final String PURCHASING_RECEIPT_CREATE = "purchasing:receipt:create";

    /** Confirmar recepción (mueve stock y costo) (permiso sensible: su uso se audita). */
    public static final String PURCHASING_RECEIPT_CONFIRM = "purchasing:receipt:confirm";

    /** Recepción sin OC (permiso sensible: su uso se audita). */
    public static final String PURCHASING_RECEIPT_WITHOUT_ORDER = "purchasing:receipt:without-order";

    /** Recibir más de lo pedido (permiso sensible: su uso se audita). */
    public static final String PURCHASING_RECEIPT_OVER_RECEIVE = "purchasing:receipt:over-receive";

    // ------------------------------------------------------------------
    // Módulo Reabastecimiento
    // ------------------------------------------------------------------

    /** Ejecutar sugerencia. */
    public static final String REPLENISHMENT_RUN_EXECUTE = "replenishment:run:execute";

    /** Ver sugerencias. */
    public static final String REPLENISHMENT_RUN_READ = "replenishment:run:read";

    /** Parámetros de reabastecimiento. */
    public static final String REPLENISHMENT_PARAMS_MANAGE = "replenishment:params:manage";

    // ------------------------------------------------------------------
    // Módulo Inventario
    // ------------------------------------------------------------------

    /** Ver stock. */
    public static final String INVENTORY_STOCK_READ = "inventory:stock:read";

    /** Ver kardex. */
    public static final String INVENTORY_KARDEX_READ = "inventory:kardex:read";

    /** Mínimos y máximos. */
    public static final String INVENTORY_STOCK_LIMITS_UPDATE = "inventory:stock-limits:update";

    /** Solicitar ajuste. */
    public static final String INVENTORY_ADJUSTMENT_CREATE = "inventory:adjustment:create";

    /** Aprobar ajuste (permiso sensible: su uso se audita). */
    public static final String INVENTORY_ADJUSTMENT_APPROVE = "inventory:adjustment:approve";

    /** Crear o cancelar toma. */
    public static final String INVENTORY_COUNT_MANAGE = "inventory:count:manage";

    /** Registrar conteos. */
    public static final String INVENTORY_COUNT_EXECUTE = "inventory:count:execute";

    /** Aprobar toma (permiso sensible: su uso se audita). */
    public static final String INVENTORY_COUNT_APPROVE = "inventory:count:approve";

    /** Ver transferencias. */
    public static final String INVENTORY_TRANSFER_READ = "inventory:transfer:read";

    /** Crear y enviar transferencia. */
    public static final String INVENTORY_TRANSFER_SEND = "inventory:transfer:send";

    /** Recibir transferencia. */
    public static final String INVENTORY_TRANSFER_RECEIVE = "inventory:transfer:receive";

    /** Cancelar transferencia en DRAFT. */
    public static final String INVENTORY_TRANSFER_CANCEL = "inventory:transfer:cancel";

    /** Corregir datos de lote (permiso sensible: su uso se audita). */
    public static final String INVENTORY_LOT_UPDATE = "inventory:lot:update";

    /** Alertas de vencimiento. */
    public static final String INVENTORY_EXPIRY_READ = "inventory:expiry:read";

    // ------------------------------------------------------------------
    // Módulo Mermas
    // ------------------------------------------------------------------

    /** Ver mermas. */
    public static final String SHRINKAGE_RECORD_READ = "shrinkage:record:read";

    /** Registrar merma. */
    public static final String SHRINKAGE_RECORD_CREATE = "shrinkage:record:create";

    /** Aprobar merma (permiso sensible: su uso se audita). */
    public static final String SHRINKAGE_RECORD_APPROVE = "shrinkage:record:approve";

    // ------------------------------------------------------------------
    // Módulo Ventas y POS
    // ------------------------------------------------------------------

    /** Operar el POS (vender, suspender, retomar). */
    public static final String SALES_POS_OPERATE = "sales:pos:operate";

    /** Ver ventas. */
    public static final String SALES_SALE_READ = "sales:sale:read";

    /** Descuento manual dentro del límite. */
    public static final String SALES_DISCOUNT_APPLY = "sales:discount:apply";

    /** Autorizar descuento sobre el límite (permiso sensible: su uso se audita). */
    public static final String SALES_DISCOUNT_OVERRIDE = "sales:discount:override";

    /** Anular venta / autorizar anulación (permiso sensible: su uso se audita). */
    public static final String SALES_SALE_VOID = "sales:sale:void";

    /** Devolución / autorizarla (permiso sensible: su uso se audita). */
    public static final String SALES_RETURN_CREATE = "sales:return:create";

    /** Borrar suspendidas ajenas. */
    public static final String SALES_SUSPENDED_DELETE_OTHERS = "sales:suspended:delete-others";

    /** Reimprimir comprobante. */
    public static final String SALES_RECEIPT_REPRINT = "sales:receipt:reprint";

    /** Ver documentos tributarios. */
    public static final String SALES_TAX_DOCUMENT_READ = "sales:tax-document:read";

    /** Reintentar emisión. */
    public static final String SALES_TAX_DOCUMENT_RETRY = "sales:tax-document:retry";

    // ------------------------------------------------------------------
    // Módulo Clientes y cuenta corriente
    // ------------------------------------------------------------------

    /** Ver clientes. */
    public static final String CUSTOMERS_CUSTOMER_READ = "customers:customer:read";

    /** Crear y editar clientes. */
    public static final String CUSTOMERS_CUSTOMER_MANAGE = "customers:customer:manage";

    /** Vender a fiado. */
    public static final String CUSTOMERS_CREDIT_SELL = "customers:credit:sell";

    /** Registrar abono. */
    public static final String CUSTOMERS_CREDIT_PAYMENT = "customers:credit:payment";

    /** Habilitar o editar el cupo (permiso sensible: su uso se audita). */
    public static final String CUSTOMERS_CREDIT_MANAGE_LIMIT = "customers:credit:manage-limit";

    /** Autorizar sobre el cupo (permiso sensible: su uso se audita). */
    public static final String CUSTOMERS_CREDIT_OVERRIDE = "customers:credit:override";

    // ------------------------------------------------------------------
    // Módulo Caja
    // ------------------------------------------------------------------

    /** Abrir turno. */
    public static final String CASH_SESSION_OPEN = "cash:session:open";

    /** Cerrar el turno propio. */
    public static final String CASH_SESSION_CLOSE = "cash:session:close";

    /** Cerrar turno ajeno (permiso sensible: su uso se audita). */
    public static final String CASH_SESSION_CLOSE_OTHERS = "cash:session:close-others";

    /** Ver turnos. */
    public static final String CASH_SESSION_READ = "cash:session:read";

    /** Ingreso de efectivo. */
    public static final String CASH_MOVEMENT_CASH_IN = "cash:movement:cash-in";

    /** Retiro de efectivo (permiso sensible: su uso se audita). */
    public static final String CASH_MOVEMENT_CASH_OUT = "cash:movement:cash-out";

    /** Autorizar retiro sobre el límite (permiso sensible: su uso se audita). */
    public static final String CASH_MOVEMENT_OVERRIDE = "cash:movement:override";

    /** Aprobar diferencia de cierre (permiso sensible: su uso se audita). */
    public static final String CASH_DIFFERENCE_APPROVE = "cash:difference:approve";

    // ------------------------------------------------------------------
    // Módulo Reportes
    // ------------------------------------------------------------------

    /** Dashboard. */
    public static final String REPORT_DASHBOARD_READ = "report:dashboard:read";

    /** Reportes de venta, cajero y medio de pago. */
    public static final String REPORT_SALES_READ = "report:sales:read";

    /** Margen, utilidad y ABC por margen (permiso sensible: su uso se audita). */
    public static final String REPORT_MARGIN_READ = "report:margin:read";

    /** Rotación, sin rotación y valorización (permiso sensible: su uso se audita). */
    public static final String REPORT_INVENTORY_READ = "report:inventory:read";

    /** Compras por proveedor. */
    public static final String REPORT_PURCHASING_READ = "report:purchasing:read";

    /** Reportes de merma. */
    public static final String REPORT_SHRINKAGE_READ = "report:shrinkage:read";

    /** Diferencias de caja. */
    public static final String REPORT_CASH_READ = "report:cash:read";

    /** Exportar a Excel o PDF (permiso sensible: su uso se audita). */
    public static final String REPORT_EXPORT_EXECUTE = "report:export:execute";

    /**
     * Todos los códigos de permiso del catálogo, inmutable y en el orden de D.1.
     */
    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            SECURITY_USER_READ,
            SECURITY_USER_CREATE,
            SECURITY_USER_UPDATE,
            SECURITY_USER_DISABLE,
            SECURITY_USER_RESET_CREDENTIALS,
            SECURITY_ROLE_READ,
            SECURITY_ROLE_MANAGE,
            SECURITY_SESSION_REVOKE,
            SECURITY_STORE_ALL_ACCESS,
            SECURITY_AUDIT_READ,
            SECURITY_AUDIT_VERIFY,
            CONFIG_COMPANY_UPDATE,
            CONFIG_STORE_MANAGE,
            CONFIG_TERMINAL_MANAGE,
            CONFIG_TAX_MANAGE,
            CONFIG_SETTINGS_READ,
            CONFIG_SETTINGS_UPDATE,
            CONFIG_ROLE_LIMIT_MANAGE,
            CONFIG_SCALE_BARCODE_MANAGE,
            CATALOG_PRODUCT_READ,
            CATALOG_PRODUCT_CREATE,
            CATALOG_PRODUCT_UPDATE,
            CATALOG_PRODUCT_DEACTIVATE,
            CATALOG_PRODUCT_UPDATE_PRICE,
            CATALOG_COST_READ,
            CATALOG_COST_UPDATE,
            CATALOG_CATEGORY_MANAGE,
            CATALOG_BRAND_MANAGE,
            CATALOG_PRODUCT_IMPORT,
            CATALOG_PRODUCT_EXPORT,
            PROMOTIONS_PROMOTION_READ,
            PROMOTIONS_PROMOTION_MANAGE,
            PURCHASING_SUPPLIER_READ,
            PURCHASING_SUPPLIER_MANAGE,
            PURCHASING_PRODUCT_SUPPLIER_MANAGE,
            PURCHASING_ORDER_READ,
            PURCHASING_ORDER_CREATE,
            PURCHASING_ORDER_SEND,
            PURCHASING_ORDER_CANCEL,
            PURCHASING_RECEIPT_CREATE,
            PURCHASING_RECEIPT_CONFIRM,
            PURCHASING_RECEIPT_WITHOUT_ORDER,
            PURCHASING_RECEIPT_OVER_RECEIVE,
            REPLENISHMENT_RUN_EXECUTE,
            REPLENISHMENT_RUN_READ,
            REPLENISHMENT_PARAMS_MANAGE,
            INVENTORY_STOCK_READ,
            INVENTORY_KARDEX_READ,
            INVENTORY_STOCK_LIMITS_UPDATE,
            INVENTORY_ADJUSTMENT_CREATE,
            INVENTORY_ADJUSTMENT_APPROVE,
            INVENTORY_COUNT_MANAGE,
            INVENTORY_COUNT_EXECUTE,
            INVENTORY_COUNT_APPROVE,
            INVENTORY_TRANSFER_READ,
            INVENTORY_TRANSFER_SEND,
            INVENTORY_TRANSFER_RECEIVE,
            INVENTORY_TRANSFER_CANCEL,
            INVENTORY_LOT_UPDATE,
            INVENTORY_EXPIRY_READ,
            SHRINKAGE_RECORD_READ,
            SHRINKAGE_RECORD_CREATE,
            SHRINKAGE_RECORD_APPROVE,
            SALES_POS_OPERATE,
            SALES_SALE_READ,
            SALES_DISCOUNT_APPLY,
            SALES_DISCOUNT_OVERRIDE,
            SALES_SALE_VOID,
            SALES_RETURN_CREATE,
            SALES_SUSPENDED_DELETE_OTHERS,
            SALES_RECEIPT_REPRINT,
            SALES_TAX_DOCUMENT_READ,
            SALES_TAX_DOCUMENT_RETRY,
            CUSTOMERS_CUSTOMER_READ,
            CUSTOMERS_CUSTOMER_MANAGE,
            CUSTOMERS_CREDIT_SELL,
            CUSTOMERS_CREDIT_PAYMENT,
            CUSTOMERS_CREDIT_MANAGE_LIMIT,
            CUSTOMERS_CREDIT_OVERRIDE,
            CASH_SESSION_OPEN,
            CASH_SESSION_CLOSE,
            CASH_SESSION_CLOSE_OTHERS,
            CASH_SESSION_READ,
            CASH_MOVEMENT_CASH_IN,
            CASH_MOVEMENT_CASH_OUT,
            CASH_MOVEMENT_OVERRIDE,
            CASH_DIFFERENCE_APPROVE,
            REPORT_DASHBOARD_READ,
            REPORT_SALES_READ,
            REPORT_MARGIN_READ,
            REPORT_INVENTORY_READ,
            REPORT_PURCHASING_READ,
            REPORT_SHRINKAGE_READ,
            REPORT_CASH_READ,
            REPORT_EXPORT_EXECUTE)));
}
