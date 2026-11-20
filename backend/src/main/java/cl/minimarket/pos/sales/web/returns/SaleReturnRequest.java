package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.returns.SaleReturnReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpo de solicitud para registrar una devolución parcial o total ({@code POST /api/v1/sales/{id}/returns}).
 */
@Schema(name = "SaleReturnRequest")
public record SaleReturnRequest(
        @NotNull SaleReturnReasonCode reasonCode,
        @Size(max = 500) String reasonText,
        @NotEmpty @Valid List<LineInput> lines,
        @Valid List<RefundInput> refunds
) {

    /** Línea solicitada en la devolución. */
    @Schema(name = "SaleReturnLineRequest")
    public record LineInput(
            @NotNull Long saleLineId,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            Boolean restock
    ) {
        public boolean effectiveRestock() {
            return restock == null || restock;
        }
    }

    /** Medio de reembolso explícito en la devolución. */
    @Schema(name = "SaleReturnRefundRequest")
    public record RefundInput(
            @NotNull PaymentMethod method,
            @NotNull @DecimalMin("1") BigDecimal amount
    ) {
    }
}
