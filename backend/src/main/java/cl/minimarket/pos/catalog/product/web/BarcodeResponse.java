package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.BarcodeType;
import cl.minimarket.pos.catalog.product.domain.ProductBarcode;

/**
 * Respuesta de código de barras de un producto ({@code API.md} F.3).
 */
public record BarcodeResponse(
        Long id,
        String code,
        BarcodeType barcodeType,
        boolean isPrimary
) {
    /** Proyecta desde la entidad {@link ProductBarcode}. */
    public static BarcodeResponse from(ProductBarcode barcode) {
        return new BarcodeResponse(
                barcode.getId(),
                barcode.getCode(),
                barcode.getBarcodeType(),
                barcode.isPrimary()
        );
    }
}
