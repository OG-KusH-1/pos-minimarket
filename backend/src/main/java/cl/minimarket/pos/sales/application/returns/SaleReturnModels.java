package cl.minimarket.pos.sales.application.returns;

import cl.minimarket.pos.sales.application.pricing.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Modelos de comando y lectura de la capa de aplicación para anulaciones y devoluciones de ventas.
 */
public final class SaleReturnModels {

    private SaleReturnModels() {
    }

    /** Comando para anular una venta completa ({@code POST /sales/{id}/void}). */
    public record VoidSaleCommand(
            Long saleId,
            SaleReturnReasonCode reasonCode,
            String reasonText,
            UUID idempotencyKey,
            String overrideToken
    ) {
    }

    /** Línea solicitada para devolución parcial o total. */
    public record ReturnLineCommand(
            Long saleLineId,
            BigDecimal quantity,
            boolean restock
    ) {
    }

    /** Medio de reembolso explícito en una devolución. */
    public record RefundCommand(
            PaymentMethod method,
            BigDecimal amount
    ) {
    }

    /** Comando para registrar una devolución de venta ({@code POST /sales/{id}/returns}). */
    public record ReturnSaleCommand(
            Long saleId,
            SaleReturnReasonCode reasonCode,
            String reasonText,
            List<ReturnLineCommand> lines,
            List<RefundCommand> refunds,
            UUID idempotencyKey,
            String overrideToken
    ) {
    }

    /** Detalle de una línea devuelta o anulada. */
    public record SaleReturnLineDetail(
            Long saleLineId,
            String name,
            BigDecimal quantity,
            boolean restock,
            BigDecimal refundAmount
    ) {
    }

    /** Detalle de un reembolso emitido en la devolución o anulación. */
    public record SaleReturnRefundDetail(
            PaymentMethod method,
            BigDecimal amount,
            String gatewayReference
    ) {
    }

    /** Vista completa de una anulación o devolución. */
    public record SaleReturnDetail(
            Long id,
            String number,
            Long saleId,
            SaleReturnType returnType,
            SaleReturnReasonCode reasonCode,
            String reasonText,
            BigDecimal refundTotal,
            List<SaleReturnLineDetail> lines,
            List<SaleReturnRefundDetail> refunds,
            Instant createdAt,
            Long createdById,
            String createdByName
    ) {
    }

    /** Línea consultable para devolución en {@code GET /sales/{id}/returnable}. */
    public record ReturnableLineDetail(
            Long saleLineId,
            String name,
            BigDecimal quantity,
            BigDecimal returnedQuantity,
            BigDecimal returnableQuantity,
            BigDecimal unitRefundAmount
    ) {
    }

    /** Estado de anulabilidad y líneas devolvibles de una venta. */
    public record ReturnableLinesDetail(
            Long saleId,
            boolean voidAllowed,
            LocalDate returnDeadline,
            List<ReturnableLineDetail> lines
    ) {
    }
}
