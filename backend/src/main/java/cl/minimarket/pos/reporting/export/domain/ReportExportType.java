package cl.minimarket.pos.reporting.export.domain;

/**
 * Catálogo de reportes exportables en {@code reporting/export} ({@code API.md} F.2).
 *
 * <p>Define el identificador persistido en {@code audit_log.entity_id}, el código de tipo en el
 * payload de auditoría {@code DATA_EXPORTED}, el nombre base del archivo descargado, el nombre de
 * la hoja XLSX y el título del encabezado PDF.
 */
public enum ReportExportType {

    DASHBOARD("dashboard", "DASHBOARD", "reporte-dashboard", "Dashboard", "Dashboard del Día"),
    SALES("sales", "SALES", "reporte-ventas", "Ventas", "Reporte de Ventas"),
    SALES_BY_CASHIER("sales_by_cashier", "SALES_BY_CASHIER", "reporte-ventas-cajero",
            "Ventas por cajero", "Ventas por Cajero"),
    SALES_BY_PAYMENT_METHOD("sales_by_payment_method", "SALES_BY_PAYMENT_METHOD",
            "reporte-ventas-medio-pago", "Medios de pago", "Ventas por Medio de Pago"),
    PRODUCT_RANKING("product_ranking", "PRODUCT_RANKING", "reporte-ranking-productos",
            "Ranking", "Ranking de Productos"),
    NO_ROTATION("no_rotation", "NO_ROTATION", "reporte-sin-rotacion",
            "Sin rotación", "Productos sin Rotación"),
    ABC("abc", "ABC", "reporte-abc", "Análisis ABC", "Análisis Pareto ABC"),
    MARGIN("margin", "MARGIN", "reporte-margen", "Margen", "Reporte de Margen y Utilidad"),
    INVENTORY_VALUATION("inventory_valuation", "INVENTORY_VALUATION",
            "reporte-valorizacion-inventario", "Valorización", "Valorización de Inventario"),
    PURCHASES_BY_SUPPLIER("purchases_by_supplier", "PURCHASES_BY_SUPPLIER",
            "reporte-compras-proveedor", "Compras", "Compras por Proveedor"),
    SHRINKAGE("shrinkage", "SHRINKAGE", "reporte-mermas", "Mermas", "Reporte de Mermas"),
    SHRINKAGE_TOP_PRODUCTS("shrinkage_top_products", "SHRINKAGE_TOP_PRODUCTS",
            "reporte-mermas-top-productos", "Top Mermas", "Ranking de Productos con Mayor Merma"),
    CASH_DIFFERENCES("cash_differences", "CASH_DIFFERENCES",
            "reporte-diferencias-caja", "Diferencias caja", "Reporte de Diferencias de Caja");

    private final String entityId;
    private final String auditType;
    private final String fileBaseName;
    private final String sheetName;
    private final String defaultTitle;

    ReportExportType(
            String entityId,
            String auditType,
            String fileBaseName,
            String sheetName,
            String defaultTitle) {
        this.entityId = entityId;
        this.auditType = auditType;
        this.fileBaseName = fileBaseName;
        this.sheetName = sheetName;
        this.defaultTitle = defaultTitle;
    }

    /** Identificador registrado en {@code audit_log.entity_id} (máximo 40 caracteres). */
    public String entityId() {
        return entityId;
    }

    /** Código de tipo registrado en el payload {@code new_value.type} de {@code DATA_EXPORTED}. */
    public String auditType() {
        return auditType;
    }

    /** Prefijo del nombre de archivo descargado en {@code Content-Disposition}. */
    public String fileBaseName() {
        return fileBaseName;
    }

    /** Nombre de la hoja principal en el libro XLSX. */
    public String sheetName() {
        return sheetName;
    }

    /** Título por defecto del documento PDF. */
    public String defaultTitle() {
        return defaultTitle;
    }
}
