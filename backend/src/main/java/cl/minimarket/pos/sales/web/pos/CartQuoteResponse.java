package cl.minimarket.pos.sales.web.pos;

import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteResult;
import cl.minimarket.pos.security.OverrideAction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Respuesta de cotización de carrito en POS ({@code API.md} F.3 {@code CartQuoteResponse}).
 */
public record CartQuoteResponse(
        List<Line> lines,
        BigDecimal subtotalGross,
        BigDecimal discountTotal,
        BigDecimal totalGross,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal exemptTotal,
        List<TaxBreakdown> taxBreakdown,
        BigDecimal roundingAdjustment,
        BigDecimal totalPayable,
        BigDecimal changeTotal,
        boolean requiresAgeConfirmation,
        List<RequiredOverride> requiredOverrides
) {

    /** Promoción aplicada a una línea. */
    public record AppliedPromotion(Long id, String name) {
    }

    /** Línea cotizada con su desglose completo. */
    public record Line(
            int lineNumber,
            Long productId,
            String name,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal lineSubtotalGross,
            BigDecimal promotionDiscount,
            BigDecimal manualDiscount,
            BigDecimal proratedDiscount,
            BigDecimal lineTotalGross,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal lineExempt,
            boolean restricted,
            AppliedPromotion appliedPromotion
    ) {
    }

    /** Desglose agregado de un impuesto sobre la venta. */
    public record TaxBreakdown(
            String taxCode,
            BigDecimal rate,
            BigDecimal baseAmount,
            BigDecimal taxAmount
    ) {
    }

    /** Autorización de supervisor requerida para confirmar el carrito. */
    public record RequiredOverride(
            OverrideAction action,
            Map<String, Object> context
    ) {
    }

    /**
     * Convierte un {@link QuoteResult} interno al DTO de respuesta REST.
     */
    public static CartQuoteResponse from(QuoteResult result) {
        List<Line> lines = result.lines().stream()
                .map(l -> new Line(
                        l.lineNumber(),
                        l.productId(),
                        l.name(),
                        l.quantity(),
                        l.unitPriceGross(),
                        l.lineSubtotalGross(),
                        l.promotionDiscount(),
                        l.manualDiscount(),
                        l.proratedDiscount(),
                        l.lineTotalGross(),
                        l.lineNet(),
                        l.lineTax(),
                        l.lineExempt(),
                        l.restricted(),
                        l.appliedPromotion() != null
                                ? new AppliedPromotion(l.appliedPromotion().id(), l.appliedPromotion().name())
                                : null
                ))
                .toList();

        List<TaxBreakdown> taxes = result.taxBreakdown().stream()
                .map(t -> new TaxBreakdown(t.taxCode(), t.rate(), t.baseAmount(), t.taxAmount()))
                .toList();

        List<RequiredOverride> overrides = result.requiredOverrides().stream()
                .map(o -> new RequiredOverride(o.action(), o.context()))
                .toList();

        return new CartQuoteResponse(
                lines,
                result.subtotalGross(),
                result.discountTotal(),
                result.totalGross(),
                result.netTotal(),
                result.taxTotal(),
                result.exemptTotal(),
                taxes,
                result.roundingAdjustment(),
                result.totalPayable(),
                result.changeTotal(),
                result.requiresAgeConfirmation(),
                overrides
        );
    }
}
