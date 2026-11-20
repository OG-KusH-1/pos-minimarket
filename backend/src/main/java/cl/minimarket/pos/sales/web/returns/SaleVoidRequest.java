package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.sales.application.returns.SaleReturnReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de solicitud para anular una venta ({@code POST /api/v1/sales/{id}/void}).
 */
@Schema(name = "SaleVoidRequest")
public record SaleVoidRequest(
        @NotNull SaleReturnReasonCode reasonCode,
        @Size(max = 500) String reasonText
) {
}
