package cl.minimarket.pos.inventory.web.count;

import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountLineView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Respuesta pública de una línea de toma de inventario ({@code API.md} F.3).
 */
@Schema(description = "Detalle de una línea de toma física de inventario")
public record CountLineResponse(
        @Schema(description = "ID de la línea", example = "201")
        Long id,

        @Schema(description = "ID del producto", example = "42")
        Long productId,

        @Schema(description = "SKU del producto", example = "7801234500012")
        String sku,

        @Schema(description = "Nombre del producto", example = "Leche entera 1 L")
        String name,

        @Schema(description = "Unidad de medida", example = "UNIT")
        UnitOfMeasure unitOfMeasure,

        @Schema(description = "ID del lote si aplica desglose por lote")
        Long lotId,

        @Schema(description = "Número de lote si aplica")
        String lotNumber,

        @Schema(description = "Existencia teórica capturada (null en toma ciega sin permiso de aprobar)")
        BigDecimal theoreticalQty,

        @Schema(description = "Cantidad física contada", example = "22.000")
        BigDecimal countedQty,

        @Schema(description = "Diferencia countedQty - theoreticalQty (null en toma ciega sin permiso de aprobar)")
        BigDecimal difference,

        @Schema(description = "Costo unitario neto al momento del conteo (null sin catalog:cost:read)")
        BigDecimal unitCost,

        @Schema(description = "Fecha/hora UTC del conteo")
        Instant countedAt,

        @Schema(description = "ID del movimiento de kardex asociado tras la aprobación")
        Long stockMovementId) {

    /** Convierte una vista interna en DTO REST. */
    public static CountLineResponse from(CountLineView view) {
        return new CountLineResponse(
                view.id(),
                view.productId(),
                view.sku(),
                view.name(),
                view.unitOfMeasure(),
                view.lotId(),
                view.lotNumber(),
                view.theoreticalQty(),
                view.countedQty(),
                view.difference(),
                view.unitCost(),
                view.countedAt(),
                view.stockMovementId());
    }
}
