package cl.minimarket.pos.inventory.web.lot;

import cl.minimarket.pos.inventory.application.lot.LotQueryService.LotView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Representación HTTP de un lote de inventario en el local activo ({@code API.md} F.3).
 */
@Schema(description = "Detalle de un lote de producto en el local activo.")
public record LotResponse(
        @Schema(description = "Identificador único del lote.")
        Long id,

        @Schema(description = "Identificador del producto.")
        Long productId,

        @Schema(description = "SKU del producto.")
        String sku,

        @Schema(description = "Nombre del producto.")
        String name,

        @Schema(description = "Unidad de medida del producto (UNIT, KG, etc.).")
        String unitOfMeasure,

        @Schema(description = "Código o número de lote.")
        String lotNumber,

        @Schema(description = "Fecha de vencimiento del lote (null si no aplica, p. ej. SIN-LOTE).")
        LocalDate expiryDate,

        @Schema(description = "Saldo disponible actual del lote.")
        BigDecimal quantity,

        @Schema(description = "Instante UTC de recepción del lote.")
        OffsetDateTime receivedAt,

        @Schema(description = "Días restantes hasta el vencimiento según la fecha de negocio (negativo si vencido; null sin fecha).")
        Long daysToExpiry,

        @Schema(description = "Indica si el lote ya está vencido respecto a la fecha de negocio.")
        boolean expired,

        @Schema(description = "Estado del lote (ACTIVE o DEPLETED).")
        String status,

        @Schema(description = "Versión de concurrencia optimista requerida en PUT /lots/{id}.")
        long version
) {

    /**
     * Convierte una proyección de consulta de lote en DTO HTTP de respuesta.
     *
     * @param view proyección de lectura obtenida desde {@code LotQueryService}
     * @return DTO de respuesta del lote
     */
    public static LotResponse from(LotView view) {
        return new LotResponse(
                view.id(),
                view.productId(),
                view.sku(),
                view.productName(),
                view.unitOfMeasure(),
                view.lotNumber(),
                view.expiryDate(),
                view.quantity(),
                view.receivedAt(),
                view.daysToExpiry(),
                view.expired(),
                view.status(),
                view.version());
    }
}
