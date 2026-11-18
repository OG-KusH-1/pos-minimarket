package cl.minimarket.pos.sales.application.pos;

import cl.minimarket.pos.catalog.ProductCatalogApi;

import java.math.BigDecimal;

/**
 * Resultado de resolver un código escaneado o digitado en el POS
 * ({@code API.md} F.3 {@code ScanResultResponse}).
 *
 * @param product       producto resuelto con su existencia en el local activo
 * @param quantity      cantidad decodificada desde balanza (o {@code null} si no aplica)
 * @param embeddedPrice precio bruto embebido en etiqueta de balanza (o {@code null} si no aplica)
 * @param source        origen de la resolución ({@code BARCODE}, {@code SCALE}, {@code SHORTCUT}, {@code SKU})
 */
public record ScanResultResponse(
        PosProductResult product,
        BigDecimal quantity,
        BigDecimal embeddedPrice,
        ProductCatalogApi.ScanSource source
) {
}
