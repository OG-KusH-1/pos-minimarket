package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.application.pricing.DiscountKind;
import cl.minimarket.pos.sales.application.sale.Sale;
import cl.minimarket.pos.sales.application.sale.SaleDetail;
import cl.minimarket.pos.sales.application.sale.SaleDiscount;
import cl.minimarket.pos.sales.application.sale.SaleDiscountSource;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CustomerInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.TerminalInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.UserInfo;
import cl.minimarket.pos.sales.application.sale.SaleLine;
import cl.minimarket.pos.sales.application.sale.SaleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Representación pública completa de una venta emitida ({@code API.md} F.3 {@code SaleResponse}).
 */
public record SaleResponse(
        Long id,
        String number,
        SaleStatus status,
        Instant soldAt,
        LocalDate businessDate,
        StoreRef store,
        TerminalRef terminal,
        UserRef cashier,
        CustomerRef customer,
        List<LineResponse> lines,
        List<DiscountResponse> discounts,
        List<SalePaymentResponse> payments,
        BigDecimal subtotalGross,
        BigDecimal discountTotal,
        BigDecimal totalGross,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal exemptTotal,
        List<TaxBreakdown> taxBreakdown,
        BigDecimal roundingAdjustment,
        BigDecimal totalPayable,
        BigDecimal paidTotal,
        BigDecimal changeTotal,
        boolean ageConfirmed,
        String emailTo,
        List<ReturnSummary> returns
) {

    /** Referencia compacta de local. */
    public record StoreRef(Long id, String code, String name) {
    }

    /** Referencia compacta de terminal. */
    public record TerminalRef(Long id, String code, String name) {
    }

    /** Referencia compacta de cajero. */
    public record UserRef(Long id, String username, String fullName) {
    }

    /** Referencia compacta de cliente. */
    public record CustomerRef(Long id, String rut, String name) {
    }

    /** Promoción aplicada a una línea. */
    public record AppliedPromotion(Long id, String name) {
    }

    /** Línea de venta con su desglose comercial, tributario y cantidad devuelta. */
    public record LineResponse(
            Long id,
            int lineNumber,
            Long productId,
            String sku,
            String name,
            SaleType saleType,
            BigDecimal quantity,
            BigDecimal unitPriceGross,
            BigDecimal lineSubtotalGross,
            BigDecimal promotionDiscount,
            BigDecimal manualDiscount,
            BigDecimal proratedDiscount,
            BigDecimal discountAmount,
            BigDecimal lineTotalGross,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal lineExempt,
            boolean restricted,
            AppliedPromotion appliedPromotion,
            BigDecimal returnedQuantity
    ) {
    }

    /** Descuento aplicado a una línea o al total de la venta. */
    public record DiscountResponse(
            Long id,
            Long saleLineId,
            SaleDiscountSource source,
            Long promotionId,
            String promotionName,
            DiscountKind discountKind,
            BigDecimal value,
            BigDecimal amount,
            String reason,
            Long appliedBy,
            Long authorizationId
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

    /** Resumen de anulación o devolución asociada a la venta. */
    public record ReturnSummary(
            Long id,
            String number,
            String returnType,
            BigDecimal refundTotal
    ) {
    }

    /** Convierte un {@link SaleDetail} de aplicación al DTO REST. */
    public static SaleResponse from(SaleDetail detail) {
        Sale sale = detail.sale();
        StoreInfo st = detail.store();
        TerminalInfo term = detail.terminal();
        UserInfo cashier = detail.cashier();
        CustomerInfo customer = detail.customer();
        Map<Long, String> promoNames = detail.promotionNamesById();

        List<LineResponse> lines = detail.lines().stream()
                .map(line -> toLineResponse(line, detail.discounts(), promoNames))
                .toList();

        List<DiscountResponse> discounts = detail.discounts().stream()
                .map(d -> new DiscountResponse(
                        d.getId(),
                        d.getSaleLineId(),
                        d.getSource(),
                        d.getPromotionId(),
                        d.getPromotionId() != null ? promoNames.get(d.getPromotionId()) : null,
                        d.getDiscountKind(),
                        d.getValue(),
                        d.getAmount(),
                        d.getReason(),
                        d.getAppliedBy(),
                        d.getAuthorizationId()
                ))
                .toList();

        List<SalePaymentResponse> payments = detail.payments().stream()
                .map(SalePaymentResponse::from)
                .toList();

        List<TaxBreakdown> taxes = detail.taxBreakdown().stream()
                .map(t -> new TaxBreakdown(t.taxCode(), t.rate(), t.baseAmount(), t.taxAmount()))
                .toList();

        List<ReturnSummary> returns = detail.returns().stream()
                .map(r -> new ReturnSummary(r.id(), r.number(), r.returnType(), r.refundTotal()))
                .toList();

        return new SaleResponse(
                sale.getId(),
                sale.getNumber(),
                sale.getStatus(),
                sale.getSoldAt(),
                sale.getBusinessDate(),
                st != null ? new StoreRef(st.id(), st.code(), st.name()) : new StoreRef(sale.getStoreId(), null, null),
                term != null ? new TerminalRef(term.id(), term.code(), term.name()) : new TerminalRef(sale.getTerminalId(), null, null),
                cashier != null ? new UserRef(cashier.id(), cashier.username(), cashier.fullName()) : new UserRef(sale.getCashierUserId(), null, null),
                customer != null ? new CustomerRef(customer.id(), customer.rut(), customer.name()) : null,
                lines,
                discounts,
                payments,
                sale.getSubtotalGross(),
                sale.getDiscountTotal(),
                sale.getTotalGross(),
                sale.getNetTotal(),
                sale.getTaxTotal(),
                sale.getExemptTotal(),
                taxes,
                sale.getRoundingAdjustment(),
                sale.getTotalPayable(),
                sale.getPaidTotal(),
                sale.getChangeTotal(),
                sale.isAgeConfirmed(),
                sale.getEmailTo(),
                returns
        );
    }

    private static LineResponse toLineResponse(
            SaleLine line,
            List<SaleDiscount> discounts,
            Map<Long, String> promoNames
    ) {
        BigDecimal promoDiscount = BigDecimal.ZERO;
        BigDecimal manualDiscount = BigDecimal.ZERO;
        AppliedPromotion appliedPromotion = null;

        for (SaleDiscount d : discounts) {
            if (!line.getId().equals(d.getSaleLineId())) {
                continue;
            }
            if (d.getSource() == SaleDiscountSource.PROMOTION) {
                promoDiscount = promoDiscount.add(d.getAmount());
                if (appliedPromotion == null && d.getPromotionId() != null) {
                    appliedPromotion = new AppliedPromotion(
                            d.getPromotionId(),
                            promoNames.get(d.getPromotionId())
                    );
                }
            } else if (d.getSource() == SaleDiscountSource.MANUAL) {
                manualDiscount = manualDiscount.add(d.getAmount());
            }
        }

        BigDecimal proratedDiscount = line.getDiscountAmount()
                .subtract(promoDiscount)
                .subtract(manualDiscount);
        if (proratedDiscount.signum() < 0) {
            proratedDiscount = BigDecimal.ZERO;
        }

        return new LineResponse(
                line.getId(),
                line.getLineNumber(),
                line.getProductId(),
                line.getSku(),
                line.getProductName(),
                line.getSaleType(),
                line.getQuantity(),
                line.getUnitPriceGross(),
                line.getLineSubtotalGross(),
                promoDiscount,
                manualDiscount,
                proratedDiscount,
                line.getDiscountAmount(),
                line.getLineTotalGross(),
                line.getLineNet(),
                line.getLineTax(),
                line.getLineExempt(),
                line.isRestricted(),
                appliedPromotion,
                line.getReturnedQuantity()
        );
    }
}
