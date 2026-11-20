package cl.minimarket.pos.replenishment.web;

import cl.minimarket.pos.replenishment.application.run.ReplenishmentLineService.LineUpdateCommand;
import cl.minimarket.pos.replenishment.domain.ReplenishmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Solicitud de edición parcial de una línea de sugerencia ({@code API.md} F.3
 * {@code ReplenishmentLineUpdateRequest}). Al menos un campo debe venir presente (lo valida el
 * servicio); los campos omitidos ({@code null}) no cambian.
 *
 * <p>{@code status} admite solo {@code ACCEPTED} o {@code DISCARDED}; {@code finalPurchaseUnits}
 * admite {@code 0} (desactiva la sugerencia) y, si es mayor que cero, debe respetar el MOQ del
 * proveedor. Si la línea ya está en una orden de compra, el servicio responde 409
 * {@code INVALID_STATE_TRANSITION}.
 */
@Schema(name = "ReplenishmentLineUpdateRequest", description = "Edición parcial de una sugerencia.")
public record ReplenishmentLineUpdateRequest(
        @Schema(description = "Unidades finales de compra (≥ 0; si > 0, ≥ MOQ del proveedor).")
        @DecimalMin("0")
        BigDecimal finalPurchaseUnits,

        @Schema(description = "Nuevo estado: ACCEPTED o DISCARDED.")
        ReplenishmentStatus status,

        @Schema(description = "Asociación producto-proveedor elegida (candidato activo del producto).")
        Long productSupplierId
) {

    /** Convierte a comando de servicio. */
    public LineUpdateCommand toCommand() {
        return new LineUpdateCommand(finalPurchaseUnits, status, productSupplierId);
    }
}
