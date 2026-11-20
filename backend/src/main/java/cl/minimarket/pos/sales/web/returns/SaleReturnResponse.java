package cl.minimarket.pos.sales.web.returns;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnLineDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnRefundDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnReasonCode;
import cl.minimarket.pos.sales.application.returns.SaleReturnType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta de una anulación o devolución de venta ({@code API.md} F.3).
 */
@Schema(name = "SaleReturnResponse")
public record SaleReturnResponse(
        Long id,
        String number,
        Long saleId,
        SaleReturnType returnType,
        SaleReturnReasonCode reasonCode,
        String reasonText,
        BigDecimal refundTotal,
        List<LineResponse> lines,
        List<RefundResponse> refunds,
        Instant createdAt,
        String createdBy
) {

    /** Línea incluida en la anulación o devolución. */
    @Schema(name = "SaleReturnLineResponse")
    public record LineResponse(
            Long saleLineId,
            String name,
            BigDecimal quantity,
            boolean restock,
            BigDecimal refundAmount
    ) {
        static LineResponse from(SaleReturnLineDetail d) {
            return new LineResponse(d.saleLineId(), d.name(), d.quantity(), d.restock(), d.refundAmount());
        }
    }

    /** Reembolso emitido en la anulación o devolución. */
    @Schema(name = "SaleReturnRefundResponse")
    public record RefundResponse(
            PaymentMethod method,
            BigDecimal amount,
            String gatewayReference
    ) {
        static RefundResponse from(SaleReturnRefundDetail d) {
            return new RefundResponse(d.method(), d.amount(), d.gatewayReference());
        }
    }

    /** Convierte desde el modelo de aplicación {@link SaleReturnDetail}. */
    public static SaleReturnResponse from(SaleReturnDetail d) {
        return new SaleReturnResponse(
                d.id(),
                d.number(),
                d.saleId(),
                d.returnType(),
                d.reasonCode(),
                d.reasonText(),
                d.refundTotal(),
                d.lines().stream().map(LineResponse::from).toList(),
                d.refunds().stream().map(RefundResponse::from).toList(),
                d.createdAt(),
                d.createdByName()
        );
    }
}
