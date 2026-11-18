package cl.minimarket.pos.catalog;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * API pública del módulo {@code catalog} para consumo desde {@code sales}, {@code inventory},
 * {@code purchasing}, {@code promotions} y {@code dataexchange} (PLAN A.4).
 */
public interface ProductCatalogApi {

    /**
     * Obtiene la ficha de un producto para operación de venta.
     *
     * @param id identificador del producto
     * @return datos del producto; lanza {@code NOT_FOUND} si no existe
     */
    ProductSnapshot getForSale(Long id);

    /**
     * Resuelve un código escaneado o ingresado en POS (código de barras, balanza, atajo o SKU).
     *
     * @param code código leído
     * @return resultado de la resolución o vacío si no corresponde a ningún producto activo
     */
    Optional<ScanResult> resolveCode(String code);

    /**
     * Indica si el código ingresado (SKU, código de barras, atajo o PLU de balanza) corresponde a un
     * producto existente pero inactivo ({@code active = false}), para distinguir {@code PRODUCT_INACTIVE}
     * de {@code CODE_NOT_FOUND} en POS sin exponer consultas SQL fuera de {@code catalog}.
     *
     * @param code código leído en POS
     * @return {@code true} si existe al menos un producto inactivo que coincide con el código
     */
    boolean isInactiveCodeMatch(String code);

    /**
     * Busca productos activos por nombre (trigram sin tildes), SKU, atajo o prefijo de código.
     *
     * @param q     texto de búsqueda
     * @param limit máximo de resultados a devolver
     * @return lista de productos activos coincidentes
     */
    List<ProductSnapshot> search(String q, int limit);

    /**
     * Obtiene los productos cuyos identificadores están en la colección dada.
     *
     * @param ids identificadores de producto
     * @return lista de productos encontrados (puede ser menor que {@code ids} si alguno no existe)
     */
    List<ProductSnapshot> findByIds(Collection<Long> ids);

    /**
     * Actualiza el costo promedio ponderado ({@code avg_cost}) del producto, registra la entrada
     * en {@code price_history} y audita {@code PRODUCT_COST_CHANGED}.
     *
     * @param productId  identificador del producto
     * @param newAvg     nuevo costo promedio (escala 4)
     * @param changeType tipo de cambio ({@link PriceChangeType#COST_RECEIPT},
     *                   {@link PriceChangeType#COST_INITIAL} o {@link PriceChangeType#COST_MANUAL})
     * @param sourceRef  referencia de origen (p. ej. {@code "GOODS_RECEIPT:42"} o motivo)
     */
    void applyAverageCost(Long productId, BigDecimal newAvg, PriceChangeType changeType, String sourceRef);

    /**
     * Devuelve los impuestos configurados para el producto ordenados por identificador de impuesto.
     *
     * @param productId identificador del producto
     * @return lista de tasas aplicables
     */
    List<TaxRate> taxesFor(Long productId);

    /**
     * Devuelve la definición de pack si el producto tiene {@code saleType == SaleType.PACK}.
     *
     * @param productId identificador del producto pack
     * @return información del pack o vacío si no es un pack
     */
    Optional<PackInfo> packOf(Long productId);

    /**
     * Devuelve los identificadores de todos los productos cuyo {@code saleType != SaleType.PACK}
     * (los únicos que mantienen filas en la tabla {@code stock}).
     *
     * @return lista de identificadores de producto no pack
     */
    List<Long> nonPackProductIds();

    /**
     * Crea un producto desde el módulo {@code dataexchange} (importación masiva).
     *
     * @param command datos normalizados de importación
     * @return identificador del producto creado
     */
    Long createFromImport(ImportProductCommand command);

    /**
     * Consulta productos para exportación masiva desde {@code dataexchange}.
     *
     * @param filter criterios de filtrado (nulos para sin filtro)
     * @return filas listas para exportar
     */
    List<ProductExportRow> findForExport(ProductExportFilter filter);

    /**
     * Proyección pública de un producto para otros módulos.
     */
    record ProductSnapshot(
            Long id,
            String sku,
            String name,
            Long categoryId,
            Long brandId,
            SaleType saleType,
            UnitOfMeasure unitOfMeasure,
            BigDecimal priceGross,
            BigDecimal avgCost,
            boolean vatExempt,
            RestrictedType restrictedType,
            boolean lotControlled,
            boolean active,
            String primaryBarcode,
            String shortcutCode,
            boolean allowManualDiscount,
            Integer packFactor,
            Integer shelfLifeDays
    ) {
        /**
         * Constructor de compatibilidad sin {@code shelfLifeDays} (asume {@code null}).
         */
        public ProductSnapshot(
                Long id,
                String sku,
                String name,
                Long categoryId,
                Long brandId,
                SaleType saleType,
                UnitOfMeasure unitOfMeasure,
                BigDecimal priceGross,
                BigDecimal avgCost,
                boolean vatExempt,
                RestrictedType restrictedType,
                boolean lotControlled,
                boolean active,
                String primaryBarcode,
                String shortcutCode,
                boolean allowManualDiscount,
                Integer packFactor
        ) {
            this(
                    id,
                    sku,
                    name,
                    categoryId,
                    brandId,
                    saleType,
                    unitOfMeasure,
                    priceGross,
                    avgCost,
                    vatExempt,
                    restrictedType,
                    lotControlled,
                    active,
                    primaryBarcode,
                    shortcutCode,
                    allowManualDiscount,
                    packFactor,
                    null);
        }
    }

    /**
     * Origen de resolución de un código en el POS.
     */
    enum ScanSource {
        BARCODE,
        SCALE,
        SHORTCUT,
        SKU
    }

    /**
     * Resultado de resolver un código en {@link #resolveCode(String)}.
     */
    record ScanResult(
            ProductSnapshot product,
            BigDecimal quantity,
            BigDecimal embeddedPrice,
            ScanSource source
    ) {
    }

    /**
     * Tasa de impuesto aplicable a un producto.
     */
    record TaxRate(
            Long taxId,
            String code,
            String taxType,
            BigDecimal rate
    ) {
    }

    /**
     * Definición de un pack para descomposición de inventario y ventas.
     */
    record PackInfo(
            Long productId,
            Long baseProductId,
            int factor
    ) {
    }

    /**
     * Comando de importación de producto desde {@code dataexchange}.
     */
    record ImportProductCommand(
            String sku,
            String name,
            Long categoryId,
            Long brandId,
            SaleType saleType,
            BigDecimal priceGross,
            List<String> barcodes,
            List<String> taxCodes,
            RestrictedType restrictedType,
            boolean lotControlled
    ) {
    }

    /**
     * Filtros de exportación de catálogo para {@code dataexchange}.
     */
    record ProductExportFilter(
            String q,
            Long categoryId,
            Long brandId,
            SaleType saleType,
            Boolean active,
            Boolean restricted,
            Boolean lotControlled
    ) {
    }

    /**
     * Fila de exportación de catálogo para {@code dataexchange}.
     */
    record ProductExportRow(
            Long id,
            String sku,
            String name,
            String categoryName,
            String brandName,
            SaleType saleType,
            UnitOfMeasure unitOfMeasure,
            BigDecimal priceGross,
            BigDecimal avgCost,
            BigDecimal lastCost,
            boolean vatExempt,
            RestrictedType restrictedType,
            boolean lotControlled,
            boolean active,
            String primaryBarcode
    ) {
    }
}
