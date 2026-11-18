package cl.minimarket.pos.sales.application.pricing;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.ProductCatalogApi.TaxRate;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionEngine;
import cl.minimarket.pos.promotions.PromotionResult;
import cl.minimarket.pos.sales.application.pricing.PricingModels.AppliedPromotion;
import cl.minimarket.pos.sales.application.pricing.PricingModels.DiscountSpec;
import cl.minimarket.pos.sales.application.pricing.PricingModels.LineInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.PaymentInput;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteCommand;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuoteResult;
import cl.minimarket.pos.sales.application.pricing.PricingModels.QuotedLine;
import cl.minimarket.pos.sales.application.pricing.PricingModels.RequiredOverride;
import cl.minimarket.pos.sales.application.pricing.PricingModels.TaxBreakdownItem;
import cl.minimarket.pos.security.OverrideAction;
import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import cl.minimarket.pos.security.SupervisorOverrideApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.CashRounding;
import cl.minimarket.pos.shared.money.DiscountProrater;
import cl.minimarket.pos.shared.money.Money;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.TaxBreakdownCalculator;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Servicio central de cálculo de precios, promociones, descuentos, prorrateo, impuestos,
 * redondeo de efectivo y overrides requeridos para el POS ({@code PLAN.md} A.5, H.3 T32).
 *
 * <p>No persiste datos y sirve tanto a {@code POST /pos/cart/quote} como a la creación de ventas
 * ({@code POST /sales}), garantizando una única fuente de verdad para los totales.
 */
@Service
public class PricingService {

    private static final int MAX_LINES = 300;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PCT_SCALE = 4;

    private final ProductCatalogApi productCatalogApi;
    private final PromotionEngine promotionEngine;
    private final RoleLimitApi roleLimitApi;
    private final SupervisorOverrideApi supervisorOverrideApi;
    private final SettingsApi settingsApi;
    private final BusinessClock businessClock;

    public PricingService(
            ProductCatalogApi productCatalogApi,
            PromotionEngine promotionEngine,
            RoleLimitApi roleLimitApi,
            SupervisorOverrideApi supervisorOverrideApi,
            SettingsApi settingsApi,
            BusinessClock businessClock
    ) {
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.promotionEngine = Objects.requireNonNull(promotionEngine, "promotionEngine");
        this.roleLimitApi = Objects.requireNonNull(roleLimitApi, "roleLimitApi");
        this.supervisorOverrideApi = Objects.requireNonNull(supervisorOverrideApi, "supervisorOverrideApi");
        this.settingsApi = Objects.requireNonNull(settingsApi, "settingsApi");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    /**
     * Calcula la cotización completa del carrito sin persistir ningún cambio.
     *
     * @param command entrada con líneas, descuentos, cliente y pagos propuestos
     * @return resultado desglosado con totales, impuestos, redondeo y overrides requeridos
     */
    @Transactional(readOnly = true)
    public QuoteResult quote(QuoteCommand command) {
        Objects.requireNonNull(command, "command");
        validateCommandBasics(command);

        Map<Long, ProductSnapshot> productsById = loadAndValidateProducts(command.lines());

        // 1. Preparar cantidades, precios unitarios y subtotales brutos por línea
        List<PreparedLine> preparedLines = prepareLines(command.lines(), productsById);

        // 2. Evaluar promociones vigentes
        PromotionResult promotionResult = evaluatePromotions(command, preparedLines);

        // 3. Aplicar promociones y descuentos manuales de línea
        List<DiscountedLine> discountedLines = applyLineDiscounts(preparedLines, promotionResult);

        // 4. Calcular y prorratear el descuento al total
        TotalDiscountComputation totalDiscountComputation = computeTotalDiscount(
                command.totalDiscount(),
                discountedLines
        );
        List<Money> lineBases = discountedLines.stream()
                .map(DiscountedLine::baseAfterLineDiscounts)
                .toList();
        List<Money> proratedShares = DiscountProrater.prorate(totalDiscountComputation.amount(), lineBases);

        // 5. Desglosar neto e impuestos por línea y acumular totales
        List<QuotedLine> quotedLines = new ArrayList<>(discountedLines.size());
        Map<Long, TaxAccumulator> taxAccumulators = new LinkedHashMap<>();
        Money subtotalGross = Money.ZERO;
        Money discountTotal = Money.ZERO;
        Money totalGross = Money.ZERO;
        Money netTotal = Money.ZERO;
        Money taxTotal = Money.ZERO;
        Money exemptTotal = Money.ZERO;
        boolean requiresAgeConfirmation = false;

        for (int i = 0; i < discountedLines.size(); i++) {
            DiscountedLine dl = discountedLines.get(i);
            Money prorated = proratedShares.get(i);
            Money lineTotalGross = dl.baseAfterLineDiscounts().minus(prorated);
            Money lineDiscountSum = dl.promoDiscount().plus(dl.manualDiscount()).plus(prorated);

            List<TaxRate> catalogTaxes = productCatalogApi.taxesFor(dl.product().id());
            TaxBreakdownCalculator.Breakdown breakdown = calculateLineTaxes(
                    lineTotalGross,
                    catalogTaxes,
                    dl.product().vatExempt()
            );
            accumulateTaxes(taxAccumulators, catalogTaxes, breakdown);

            subtotalGross = subtotalGross.plus(dl.subtotalGross());
            discountTotal = discountTotal.plus(lineDiscountSum);
            totalGross = totalGross.plus(lineTotalGross);
            netTotal = netTotal.plus(breakdown.net());
            taxTotal = taxTotal.plus(breakdown.taxTotal());
            exemptTotal = exemptTotal.plus(breakdown.exempt());

            if (dl.product().restrictedType() != null) {
                requiresAgeConfirmation = true;
            }

            quotedLines.add(new QuotedLine(
                    dl.lineNumber(),
                    dl.product().id(),
                    dl.product().sku(),
                    dl.product().name(),
                    dl.quantity().value(),
                    dl.unitPriceGross().amount(),
                    dl.subtotalGross().amount(),
                    dl.promoDiscount().amount(),
                    dl.manualDiscount().amount(),
                    prorated.amount(),
                    lineTotalGross.amount(),
                    breakdown.net().amount(),
                    breakdown.taxTotal().amount(),
                    breakdown.exempt().amount(),
                    dl.product().restrictedType() != null,
                    dl.appliedPromotion()
            ));
        }

        // 6. Redondeo del efectivo (Ley 20.956) y vuelto según pagos informados
        CashComputation cashComputation = computeCashRoundingAndChange(
                command.storeId(),
                totalGross,
                command.payments()
        );

        // 7. Evaluar overrides requeridos (fail-closed)
        String cartHash = computeCartHash(preparedLines);
        List<RequiredOverride> requiredOverrides = evaluateRequiredOverrides(
                command.userId(),
                command.canApplyDiscount(),
                discountedLines,
                command.totalDiscount(),
                totalDiscountComputation,
                cartHash
        );

        List<TaxBreakdownItem> taxBreakdown = taxAccumulators.values().stream()
                .sorted((a, b) -> Long.compare(a.taxId, b.taxId))
                .map(acc -> new TaxBreakdownItem(
                        acc.taxId,
                        acc.taxCode,
                        acc.rate,
                        acc.baseAmount.amount(),
                        acc.taxAmount.amount()
                ))
                .toList();

        return new QuoteResult(
                List.copyOf(quotedLines),
                subtotalGross.amount(),
                discountTotal.amount(),
                totalGross.amount(),
                netTotal.amount(),
                taxTotal.amount(),
                exemptTotal.amount(),
                taxBreakdown,
                cashComputation.roundingAdjustment().amount(),
                cashComputation.totalPayable().amount(),
                cashComputation.changeTotal().amount(),
                requiresAgeConfirmation,
                requiredOverrides,
                cartHash,
                totalDiscountComputation.amount().amount(),
                totalDiscountComputation.percent()
        );
    }

    private void validateCommandBasics(QuoteCommand command) {
        if (command.lines().isEmpty() || command.lines().size() > MAX_LINES) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El carrito debe tener entre 1 y " + MAX_LINES + " líneas."
            );
        }
        if (command.totalDiscount() != null) {
            validateDiscountSpec(command.totalDiscount(), "totalDiscount");
        }
    }

    private void validateDiscountSpec(DiscountSpec discount, String label) {
        if (discount.value().signum() <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El valor del descuento (" + label + ") debe ser mayor que 0."
            );
        }
        if (discount.kind() == DiscountKind.PERCENT && discount.value().compareTo(HUNDRED) > 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "El porcentaje de descuento (" + label + ") no puede superar 100%."
            );
        }
    }

    private Map<Long, ProductSnapshot> loadAndValidateProducts(List<LineInput> lines) {
        List<Long> ids = lines.stream().map(LineInput::productId).distinct().toList();
        Map<Long, ProductSnapshot> byId = productCatalogApi.findByIds(ids).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));

        for (Long id : ids) {
            ProductSnapshot product = byId.get(id);
            if (product == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado: " + id);
            }
            if (!product.active()) {
                throw new BusinessException(
                        ErrorCode.PRODUCT_INACTIVE,
                        "El producto está inactivo: " + product.name()
                );
            }
            if (product.priceGross() == null || product.priceGross().signum() <= 0) {
                throw new BusinessException(
                        ErrorCode.PRICE_NOT_SET,
                        "El producto no tiene precio de venta: " + product.name()
                );
            }
        }
        return byId;
    }

    private List<PreparedLine> prepareLines(List<LineInput> inputs, Map<Long, ProductSnapshot> productsById) {
        List<PreparedLine> prepared = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            LineInput input = inputs.get(i);
            ProductSnapshot product = productsById.get(input.productId());
            Quantity qty = Quantity.of(input.quantity());

            if (qty.signum() <= 0 || qty.value().compareTo(MAX_QUANTITY) > 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La cantidad de la línea " + (i + 1) + " debe estar entre 0,001 y 9999."
                );
            }
            if (product.saleType() == SaleType.PACK && !qty.isWholeNumber()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La cantidad debe ser entera para productos tipo pack: " + product.name()
                );
            }
            if (product.saleType() == SaleType.UNIT && input.embeddedPrice() == null && !qty.isWholeNumber()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "La cantidad debe ser entera para productos por unidad: " + product.name()
                );
            }
            if (input.embeddedPrice() != null && input.embeddedPrice().signum() < 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El precio embebido de la línea " + (i + 1) + " no puede ser negativo."
                );
            }
            if (input.manualDiscount() != null) {
                validateDiscountSpec(input.manualDiscount(), "línea " + (i + 1));
                if (!product.allowManualDiscount()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "El producto no permite descuentos manuales: " + product.name()
                    );
                }
            }

            Money unitPriceGross = Money.of(product.priceGross());
            Money subtotalGross = input.embeddedPrice() != null
                    ? Money.of(input.embeddedPrice())
                    : unitPriceGross.times(qty);

            prepared.add(new PreparedLine(
                    i,
                    i + 1,
                    product,
                    qty,
                    unitPriceGross,
                    subtotalGross,
                    input.manualDiscount()
            ));
        }
        return prepared;
    }

    private PromotionResult evaluatePromotions(QuoteCommand command, List<PreparedLine> preparedLines) {
        List<PricingContext.Line> ctxLines = preparedLines.stream()
                .map(pl -> new PricingContext.Line(
                        pl.lineNumber(),
                        pl.product().id(),
                        pl.product().categoryId(),
                        pl.product().brandId(),
                        pl.quantity().value(),
                        pl.unitPriceGross().amount(),
                        pl.subtotalGross().amount()
                ))
                .toList();
        PricingContext ctx = new PricingContext(
                command.storeId(),
                businessClock.now(),
                command.customerId(),
                ctxLines
        );
        return promotionEngine.evaluate(ctx);
    }

    private List<DiscountedLine> applyLineDiscounts(
            List<PreparedLine> preparedLines,
            PromotionResult promotionResult
    ) {
        List<DiscountedLine> result = new ArrayList<>(preparedLines.size());
        for (PreparedLine pl : preparedLines) {
            PromotionResult.LinePromotion promo = promotionResult.forLine(pl.lineNumber()).orElse(null);
            Money promoDiscount = Money.ZERO;
            AppliedPromotion appliedPromotion = null;
            if (promo != null && promo.discountAmount().signum() > 0) {
                Money rawPromo = Money.of(promo.discountAmount());
                promoDiscount = rawPromo.compareTo(pl.subtotalGross()) > 0 ? pl.subtotalGross() : rawPromo;
                appliedPromotion = new AppliedPromotion(promo.promotionId(), promo.promotionName());
            }

            Money afterPromo = pl.subtotalGross().minus(promoDiscount);
            Money manualDiscount = Money.ZERO;
            BigDecimal manualEffectivePercent = BigDecimal.ZERO;

            if (pl.manualDiscount() != null) {
                DiscountSpec spec = pl.manualDiscount();
                if (spec.kind() == DiscountKind.PERCENT) {
                    BigDecimal factor = spec.value().divide(HUNDRED, MathContext.DECIMAL64);
                    manualDiscount = afterPromo.times(factor);
                    manualEffectivePercent = spec.value().setScale(PCT_SCALE, RoundingMode.HALF_UP);
                } else {
                    manualDiscount = Money.of(spec.value());
                    if (manualDiscount.compareTo(afterPromo) > 0) {
                        throw new BusinessException(
                                ErrorCode.DISCOUNT_LIMIT_EXCEEDED,
                                "El descuento manual excede el subtotal de la línea " + pl.lineNumber() + "."
                        );
                    }
                    manualEffectivePercent = afterPromo.signum() > 0
                            ? manualDiscount.amount()
                                    .multiply(HUNDRED)
                                    .divide(afterPromo.amount(), PCT_SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                }
            }

            Money baseAfterLineDiscounts = afterPromo.minus(manualDiscount);
            result.add(new DiscountedLine(
                    pl.lineIndex(),
                    pl.lineNumber(),
                    pl.product(),
                    pl.quantity(),
                    pl.unitPriceGross(),
                    pl.subtotalGross(),
                    promoDiscount,
                    manualDiscount,
                    manualEffectivePercent,
                    baseAfterLineDiscounts,
                    appliedPromotion,
                    pl.manualDiscount() != null
            ));
        }
        return result;
    }

    private TotalDiscountComputation computeTotalDiscount(
            DiscountSpec totalDiscount,
            List<DiscountedLine> discountedLines
    ) {
        if (totalDiscount == null) {
            return new TotalDiscountComputation(Money.ZERO, BigDecimal.ZERO);
        }

        Money totalBase = Money.sum(discountedLines.stream().map(DiscountedLine::baseAfterLineDiscounts).toList());
        if (totalDiscount.kind() == DiscountKind.PERCENT) {
            BigDecimal factor = totalDiscount.value().divide(HUNDRED, MathContext.DECIMAL64);
            Money amount = totalBase.times(factor);
            BigDecimal pct = totalDiscount.value().setScale(PCT_SCALE, RoundingMode.HALF_UP);
            return new TotalDiscountComputation(amount, pct);
        }

        Money amount = Money.of(totalDiscount.value());
        if (amount.compareTo(totalBase) > 0) {
            throw new BusinessException(
                    ErrorCode.DISCOUNT_LIMIT_EXCEEDED,
                    "El descuento al total excede el monto neto de las líneas."
            );
        }
        BigDecimal pct = totalBase.signum() > 0
                ? amount.amount().multiply(HUNDRED).divide(totalBase.amount(), PCT_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new TotalDiscountComputation(amount, pct);
    }

    private TaxBreakdownCalculator.Breakdown calculateLineTaxes(
            Money lineTotalGross,
            List<TaxRate> catalogTaxes,
            boolean vatExempt
    ) {
        List<TaxBreakdownCalculator.TaxRate> rates = catalogTaxes.stream()
                .map(t -> new TaxBreakdownCalculator.TaxRate(t.taxId(), t.rate()))
                .toList();
        return TaxBreakdownCalculator.calculate(lineTotalGross, rates, vatExempt);
    }

    private void accumulateTaxes(
            Map<Long, TaxAccumulator> accumulators,
            List<TaxRate> catalogTaxes,
            TaxBreakdownCalculator.Breakdown breakdown
    ) {
        if (breakdown.taxes().isEmpty()) {
            return;
        }
        Map<Long, TaxRate> rateById = catalogTaxes.stream()
                .collect(Collectors.toMap(TaxRate::taxId, Function.identity()));
        for (TaxBreakdownCalculator.TaxAmount taxAmount : breakdown.taxes()) {
            TaxRate rate = rateById.get(taxAmount.taxId());
            if (rate == null) {
                continue;
            }
            TaxAccumulator acc = accumulators.computeIfAbsent(
                    rate.taxId(),
                    id -> new TaxAccumulator(rate.taxId(), rate.code(), rate.rate())
            );
            acc.baseAmount = acc.baseAmount.plus(breakdown.net());
            acc.taxAmount = acc.taxAmount.plus(taxAmount.amount());
        }
    }

    private CashComputation computeCashRoundingAndChange(
            Long storeId,
            Money totalGross,
            List<PaymentInput> payments
    ) {
        if (payments.isEmpty()) {
            return new CashComputation(Money.ZERO, totalGross, Money.ZERO);
        }

        PaymentInput cashPayment = null;
        Money nonCashTotal = Money.ZERO;

        for (PaymentInput payment : payments) {
            if (payment.method() == PaymentMethod.CASH) {
                if (cashPayment != null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Solo se admite un pago en efectivo por venta."
                    );
                }
                cashPayment = payment;
            } else {
                if (payment.amount() == null || payment.amount().signum() <= 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Los pagos distintos de efectivo deben indicar un monto mayor que 0."
                    );
                }
                nonCashTotal = nonCashTotal.plus(Money.of(payment.amount()));
            }
        }

        if (cashPayment == null) {
            return new CashComputation(Money.ZERO, totalGross, Money.ZERO);
        }

        boolean cashRoundingEnabled = isCashRoundingEnabled(storeId);
        Money cashDue = totalGross.minus(nonCashTotal);
        Money roundingAdjustment = CashRounding.adjustment(cashDue, cashRoundingEnabled);
        Money totalPayable = totalGross.plus(roundingAdjustment);
        Money cashApplied = cashDue.signum() > 0 ? cashDue.plus(roundingAdjustment) : Money.ZERO;

        Money changeTotal = Money.ZERO;
        if (cashPayment.tenderedAmount() != null) {
            Money tendered = Money.of(cashPayment.tenderedAmount());
            if (tendered.isNegative()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "El monto entregado en efectivo no puede ser negativo."
                );
            }
            if (tendered.compareTo(cashApplied) >= 0) {
                changeTotal = tendered.minus(cashApplied);
            }
        }

        return new CashComputation(roundingAdjustment, totalPayable, changeTotal);
    }

    private boolean isCashRoundingEnabled(Long storeId) {
        Boolean configured = settingsApi.get(CashRounding.SETTING_KEY, storeId, Boolean.class);
        return configured == null || configured;
    }

    private String computeCartHash(List<PreparedLine> preparedLines) {
        List<Map<String, Object>> canonicalLines = preparedLines.stream()
                .map(pl -> Map.<String, Object>of(
                        "productId", pl.product().id(),
                        "quantity", pl.quantity().value()
                ))
                .toList();
        return supervisorOverrideApi.canonicalContextHash(canonicalLines);
    }

    private List<RequiredOverride> evaluateRequiredOverrides(
            Long userId,
            boolean canApplyDiscount,
            List<DiscountedLine> discountedLines,
            DiscountSpec totalDiscountSpec,
            TotalDiscountComputation totalDiscountComputation,
            String cartHash
    ) {
        List<RequiredOverride> overrides = new ArrayList<>();

        RoleLimit itemPctLimit = resolveLimit(userId, canApplyDiscount, RoleLimitCode.DISCOUNT_ITEM_MAX_PCT);
        for (DiscountedLine dl : discountedLines) {
            if (!dl.hasManualDiscount()) {
                continue;
            }
            boolean hasPromotionConflict = dl.promoDiscount().signum() > 0;
            boolean withinLimit = itemPctLimit.allows(dl.manualEffectivePercent());
            if (hasPromotionConflict || !withinLimit) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("lineIndex", dl.lineIndex());
                context.put("productId", dl.product().id());
                context.put("percent", dl.manualEffectivePercent());
                context.put("amount", dl.manualDiscount().toLong());
                context.put("cartHash", cartHash);
                overrides.add(new RequiredOverride(OverrideAction.DISCOUNT_ITEM_OVER_LIMIT, context));
            }
        }

        if (totalDiscountSpec != null && totalDiscountComputation.amount().signum() > 0) {
            RoleLimit totalPctLimit = resolveLimit(userId, canApplyDiscount, RoleLimitCode.DISCOUNT_TOTAL_MAX_PCT);
            RoleLimit totalAmtLimit = resolveLimit(userId, canApplyDiscount, RoleLimitCode.DISCOUNT_TOTAL_MAX_AMOUNT);

            boolean pctAllowed = totalPctLimit.allows(totalDiscountComputation.percent());
            boolean amtAllowed = totalAmtLimit.allows(totalDiscountComputation.amount().amount());
            if (!pctAllowed || !amtAllowed) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("percent", totalDiscountComputation.percent());
                context.put("amount", totalDiscountComputation.amount().toLong());
                context.put("cartHash", cartHash);
                overrides.add(new RequiredOverride(OverrideAction.DISCOUNT_TOTAL_OVER_LIMIT, context));
            }
        }

        return List.copyOf(overrides);
    }

    private RoleLimit resolveLimit(Long userId, boolean canApplyDiscount, RoleLimitCode code) {
        if (!canApplyDiscount || userId == null) {
            return RoleLimit.zero();
        }
        return roleLimitApi.limitFor(userId, code);
    }

    private record PreparedLine(
            int lineIndex,
            int lineNumber,
            ProductSnapshot product,
            Quantity quantity,
            Money unitPriceGross,
            Money subtotalGross,
            DiscountSpec manualDiscount
    ) {
    }

    private record DiscountedLine(
            int lineIndex,
            int lineNumber,
            ProductSnapshot product,
            Quantity quantity,
            Money unitPriceGross,
            Money subtotalGross,
            Money promoDiscount,
            Money manualDiscount,
            BigDecimal manualEffectivePercent,
            Money baseAfterLineDiscounts,
            AppliedPromotion appliedPromotion,
            boolean hasManualDiscount
    ) {
    }

    private record TotalDiscountComputation(Money amount, BigDecimal percent) {
    }

    private record CashComputation(Money roundingAdjustment, Money totalPayable, Money changeTotal) {
    }

    private static final class TaxAccumulator {
        final Long taxId;
        final String taxCode;
        final BigDecimal rate;
        Money baseAmount = Money.ZERO;
        Money taxAmount = Money.ZERO;

        TaxAccumulator(Long taxId, String taxCode, BigDecimal rate) {
            this.taxId = taxId;
            this.taxCode = taxCode;
            this.rate = rate;
        }
    }
}
