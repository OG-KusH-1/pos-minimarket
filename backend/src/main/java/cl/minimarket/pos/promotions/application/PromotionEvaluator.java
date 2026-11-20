package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionResult;
import cl.minimarket.pos.promotions.PromotionTargetType;
import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.shared.money.DiscountProrater;
import cl.minimarket.pos.shared.money.Money;
import cl.minimarket.pos.shared.money.Rounding;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Motor puro de evaluación de promociones sobre un carrito ({@code MODELO_DATOS.md} B.10).
 *
 * <p>Implementa los cinco tipos ({@code PERCENT}, {@code FIXED_AMOUNT}, {@code BUY_X_PAY_Y},
 * {@code QUANTITY_PRICE}, {@code COMBO}), vigencia por local, fecha, día de la semana y horario,
 * límite {@code maxApplicationsPerSale} y selección de mayor beneficio para promociones no
 * acumulables.
 */
final class PromotionEvaluator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PromotionEvaluator() {
    }

    /**
     * Evalúa las reglas candidatas sobre el contexto de carrito dado.
     *
     * @param context contexto del carrito
     * @param rules   reglas de promoción candidatas
     * @param zone    zona horaria del negocio
     * @return resultado con a lo más una entrada por línea de carrito
     */
    static PromotionResult evaluate(PricingContext context, List<PromotionRule> rules, ZoneId zone) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(zone, "zone");

        if (context.lines().isEmpty() || rules.isEmpty()) {
            return PromotionResult.empty();
        }

        List<PromotionRule> vigentRules = rules.stream()
                .filter(r -> r.isVigentFor(context.storeId(), context.evaluatedAt(), zone))
                .sorted(Comparator.comparingInt(PromotionRule::priority).thenComparingLong(PromotionRule::id))
                .toList();

        if (vigentRules.isEmpty()) {
            return PromotionResult.empty();
        }

        Map<Integer, List<CandidateApplication>> candidatesByLine = new LinkedHashMap<>();
        for (PricingContext.Line line : context.lines()) {
            candidatesByLine.put(line.lineNumber(), new ArrayList<>());
        }

        for (PromotionRule rule : vigentRules) {
            Map<Integer, BigDecimal> discounts = evaluateSingleRule(context, rule);
            for (Map.Entry<Integer, BigDecimal> entry : discounts.entrySet()) {
                BigDecimal amount = Rounding.clp(entry.getValue());
                if (amount.signum() > 0) {
                    List<CandidateApplication> list = candidatesByLine.get(entry.getKey());
                    if (list != null) {
                        list.add(new CandidateApplication(
                                rule.id(),
                                rule.name(),
                                rule.stackable(),
                                rule.priority(),
                                amount
                        ));
                    }
                }
            }
        }

        List<PromotionResult.LinePromotion> resultLines = new ArrayList<>();
        for (PricingContext.Line line : context.lines()) {
            List<CandidateApplication> candidates = candidatesByLine.get(line.lineNumber());
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            PromotionResult.LinePromotion selected = selectBestForLine(line, candidates);
            if (selected != null && selected.discountAmount().signum() > 0) {
                resultLines.add(selected);
            }
        }

        return resultLines.isEmpty() ? PromotionResult.empty() : new PromotionResult(resultLines);
    }

    private static PromotionResult.LinePromotion selectBestForLine(
            PricingContext.Line line,
            List<CandidateApplication> candidates
    ) {
        CandidateApplication bestNonStackable = candidates.stream()
                .filter(c -> !c.stackable())
                .max(Comparator
                        .comparing(CandidateApplication::discountAmount)
                        .thenComparing(Comparator.comparingInt(CandidateApplication::priority).reversed())
                        .thenComparing(Comparator.comparingLong(CandidateApplication::promotionId).reversed()))
                .orElse(null);

        List<CandidateApplication> stackables = candidates.stream()
                .filter(CandidateApplication::stackable)
                .toList();

        BigDecimal stackableTotal = BigDecimal.ZERO;
        for (CandidateApplication s : stackables) {
            stackableTotal = stackableTotal.add(s.discountAmount());
        }
        stackableTotal = stackableTotal.min(line.lineSubtotalGross());

        BigDecimal nonStackableAmount = bestNonStackable == null
                ? BigDecimal.ZERO
                : bestNonStackable.discountAmount().min(line.lineSubtotalGross());

        if (bestNonStackable != null && nonStackableAmount.compareTo(stackableTotal) >= 0) {
            return new PromotionResult.LinePromotion(
                    line.lineNumber(),
                    bestNonStackable.promotionId(),
                    bestNonStackable.promotionName(),
                    Rounding.clp(nonStackableAmount)
            );
        }

        if (!stackables.isEmpty() && stackableTotal.signum() > 0) {
            CandidateApplication primary = stackables.stream()
                    .max(Comparator
                            .comparing(CandidateApplication::discountAmount)
                            .thenComparing(Comparator.comparingInt(CandidateApplication::priority).reversed())
                            .thenComparing(Comparator.comparingLong(CandidateApplication::promotionId).reversed()))
                    .orElse(stackables.get(0));
            return new PromotionResult.LinePromotion(
                    line.lineNumber(),
                    primary.promotionId(),
                    primary.promotionName(),
                    Rounding.clp(stackableTotal)
            );
        }

        return null;
    }

    private static Map<Integer, BigDecimal> evaluateSingleRule(PricingContext context, PromotionRule rule) {
        return switch (rule.promotionType()) {
            case PERCENT -> evaluatePercent(context, rule);
            case FIXED_AMOUNT -> evaluateFixedAmount(context, rule);
            case BUY_X_PAY_Y -> evaluateBuyXPayY(context, rule);
            case QUANTITY_PRICE -> evaluateQuantityPrice(context, rule);
            case COMBO -> evaluateCombo(context, rule);
        };
    }

    private static Map<Integer, BigDecimal> evaluatePercent(PricingContext context, PromotionRule rule) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        if (rule.percent() == null || rule.percent().signum() <= 0) {
            return result;
        }
        long remainingApps = rule.maxApplicationsPerSale() == null
                ? Long.MAX_VALUE
                : rule.maxApplicationsPerSale();
        BigDecimal factor = rule.percent().divide(HUNDRED, MathContext.DECIMAL64);

        for (PricingContext.Line line : context.lines()) {
            if (remainingApps <= 0) {
                break;
            }
            if (!matchesAnyItem(line, rule.items())) {
                continue;
            }
            BigDecimal eligibleSubtotal;
            if (rule.maxApplicationsPerSale() == null) {
                eligibleSubtotal = line.lineSubtotalGross();
            } else if (line.quantity().compareTo(BigDecimal.ONE) < 0) {
                eligibleSubtotal = line.lineSubtotalGross();
                remainingApps -= 1;
            } else {
                long wholeUnits = line.quantity().setScale(0, RoundingMode.FLOOR).longValue();
                long eligibleUnits = Math.min(wholeUnits, remainingApps);
                if (eligibleUnits <= 0) {
                    continue;
                }
                if (eligibleUnits == wholeUnits
                        && line.quantity().compareTo(BigDecimal.valueOf(wholeUnits)) == 0) {
                    eligibleSubtotal = line.lineSubtotalGross();
                } else {
                    eligibleSubtotal = Rounding.clp(
                            line.unitPriceGross().multiply(BigDecimal.valueOf(eligibleUnits)))
                            .min(line.lineSubtotalGross());
                }
                remainingApps -= eligibleUnits;
            }

            BigDecimal discount = Rounding.clp(eligibleSubtotal.multiply(factor))
                    .min(line.lineSubtotalGross());
            if (discount.signum() > 0) {
                result.put(line.lineNumber(), discount);
            }
        }
        return result;
    }

    private static Map<Integer, BigDecimal> evaluateFixedAmount(PricingContext context, PromotionRule rule) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        if (rule.amount() == null || rule.amount().signum() <= 0) {
            return result;
        }
        long remainingApps = rule.maxApplicationsPerSale() == null
                ? Long.MAX_VALUE
                : rule.maxApplicationsPerSale();

        for (PricingContext.Line line : context.lines()) {
            if (remainingApps <= 0) {
                break;
            }
            RuleItem matchedItem = firstMatchingItem(line, rule.items());
            if (matchedItem == null) {
                continue;
            }
            BigDecimal reqQty = matchedItem.requiredQty() != null && matchedItem.requiredQty().signum() > 0
                    ? matchedItem.requiredQty()
                    : BigDecimal.ONE;
            long apps = line.quantity().divideToIntegralValue(reqQty).longValue();
            apps = Math.min(apps, remainingApps);
            if (apps <= 0) {
                continue;
            }
            remainingApps -= apps;
            BigDecimal discount = Rounding.clp(rule.amount().multiply(BigDecimal.valueOf(apps)))
                    .min(line.lineSubtotalGross());
            if (discount.signum() > 0) {
                result.put(line.lineNumber(), discount);
            }
        }
        return result;
    }

    private static Map<Integer, BigDecimal> evaluateBuyXPayY(PricingContext context, PromotionRule rule) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        if (rule.buyQty() == null || rule.payQty() == null
                || rule.payQty() < 1 || rule.buyQty() <= rule.payQty()) {
            return result;
        }
        List<PricingContext.Line> matchingLines = context.lines().stream()
                .filter(line -> matchesAnyItem(line, rule.items()))
                .toList();
        if (matchingLines.isEmpty()) {
            return result;
        }

        long totalWholeUnits = 0;
        for (PricingContext.Line line : matchingLines) {
            totalWholeUnits += line.quantity().setScale(0, RoundingMode.FLOOR).longValue();
        }
        long groups = totalWholeUnits / rule.buyQty();
        if (rule.maxApplicationsPerSale() != null) {
            groups = Math.min(groups, rule.maxApplicationsPerSale());
        }
        if (groups <= 0) {
            return result;
        }

        long freeUnitsRemaining = groups * (rule.buyQty() - rule.payQty());
        List<PricingContext.Line> cheapestFirst = matchingLines.stream()
                .sorted(Comparator
                        .comparing(PricingContext.Line::unitPriceGross)
                        .thenComparingInt(PricingContext.Line::lineNumber))
                .toList();

        for (PricingContext.Line line : cheapestFirst) {
            if (freeUnitsRemaining <= 0) {
                break;
            }
            long wholeUnits = line.quantity().setScale(0, RoundingMode.FLOOR).longValue();
            long freeOnLine = Math.min(wholeUnits, freeUnitsRemaining);
            if (freeOnLine <= 0) {
                continue;
            }
            freeUnitsRemaining -= freeOnLine;
            BigDecimal discount = Rounding.clp(line.unitPriceGross().multiply(BigDecimal.valueOf(freeOnLine)))
                    .min(line.lineSubtotalGross());
            if (discount.signum() > 0) {
                result.put(line.lineNumber(), discount);
            }
        }
        return result;
    }

    private static Map<Integer, BigDecimal> evaluateQuantityPrice(PricingContext context, PromotionRule rule) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        if (rule.minQty() == null || rule.minQty().signum() <= 0
                || rule.amount() == null || rule.amount().signum() <= 0) {
            return result;
        }
        long remainingApps = rule.maxApplicationsPerSale() == null
                ? Long.MAX_VALUE
                : rule.maxApplicationsPerSale();

        for (PricingContext.Line line : context.lines()) {
            if (remainingApps <= 0) {
                break;
            }
            if (!matchesAnyItem(line, rule.items())) {
                continue;
            }
            if (line.quantity().compareTo(rule.minQty()) < 0) {
                continue;
            }
            if (line.unitPriceGross().compareTo(rule.amount()) <= 0) {
                continue;
            }
            BigDecimal promoSubtotal = Rounding.clp(line.quantity().multiply(rule.amount()));
            BigDecimal discount = line.lineSubtotalGross().subtract(promoSubtotal);
            if (discount.signum() > 0) {
                remainingApps -= 1;
                result.put(line.lineNumber(), discount.min(line.lineSubtotalGross()));
            }
        }
        return result;
    }

    private static Map<Integer, BigDecimal> evaluateCombo(PricingContext context, PromotionRule rule) {
        Map<Integer, BigDecimal> result = new LinkedHashMap<>();
        if (rule.comboPrice() == null || rule.comboPrice().signum() <= 0 || rule.items().size() < 2) {
            return result;
        }
        for (RuleItem item : rule.items()) {
            if (item.targetType() != PromotionTargetType.PRODUCT
                    || item.requiredQty() == null
                    || item.requiredQty().signum() <= 0) {
                return result;
            }
        }

        // Calcular cuántos combos completos se pueden armar con el carrito
        long maxSets = Long.MAX_VALUE;
        for (RuleItem item : rule.items()) {
            BigDecimal available = BigDecimal.ZERO;
            for (PricingContext.Line line : context.lines()) {
                if (Objects.equals(line.productId(), item.targetId())) {
                    available = available.add(line.quantity());
                }
            }
            long setsForItem = available.divideToIntegralValue(item.requiredQty()).longValue();
            maxSets = Math.min(maxSets, setsForItem);
            if (maxSets <= 0) {
                return result;
            }
        }

        if (rule.maxApplicationsPerSale() != null) {
            maxSets = Math.min(maxSets, rule.maxApplicationsPerSale());
        }
        if (maxSets <= 0) {
            return result;
        }

        // Determinar qué líneas participan y cuánto subtotal normal aportan al combo
        Map<Integer, BigDecimal> consumedSubtotalByLine = new LinkedHashMap<>();
        Map<Integer, BigDecimal> remainingQtyByLine = new LinkedHashMap<>();
        for (PricingContext.Line line : context.lines()) {
            remainingQtyByLine.put(line.lineNumber(), line.quantity());
        }

        BigDecimal totalNormalComboSubtotal = BigDecimal.ZERO;
        for (RuleItem item : rule.items()) {
            BigDecimal needed = item.requiredQty().multiply(BigDecimal.valueOf(maxSets));
            for (PricingContext.Line line : context.lines()) {
                if (needed.signum() <= 0) {
                    break;
                }
                if (!Objects.equals(line.productId(), item.targetId())) {
                    continue;
                }
                BigDecimal avail = remainingQtyByLine.get(line.lineNumber());
                if (avail.signum() <= 0) {
                    continue;
                }
                BigDecimal take = avail.min(needed);
                remainingQtyByLine.put(line.lineNumber(), avail.subtract(take));
                needed = needed.subtract(take);

                BigDecimal portionSubtotal = take.compareTo(line.quantity()) == 0
                        ? line.lineSubtotalGross()
                        : Rounding.clp(take.multiply(line.unitPriceGross())).min(line.lineSubtotalGross());

                consumedSubtotalByLine.merge(line.lineNumber(), portionSubtotal, BigDecimal::add);
                totalNormalComboSubtotal = totalNormalComboSubtotal.add(portionSubtotal);
            }
        }

        BigDecimal targetComboTotal = Rounding.clp(rule.comboPrice().multiply(BigDecimal.valueOf(maxSets)));
        BigDecimal totalDiscountRaw = totalNormalComboSubtotal.subtract(targetComboTotal);
        if (totalDiscountRaw.signum() <= 0) {
            return result;
        }

        List<Integer> participatingLineNumbers = new ArrayList<>(consumedSubtotalByLine.keySet());
        List<Money> bases = participatingLineNumbers.stream()
                .map(ln -> Money.of(consumedSubtotalByLine.get(ln)))
                .toList();
        Money totalDiscount = Money.of(totalDiscountRaw);
        Money totalBase = Money.sum(bases);
        if (totalDiscount.compareTo(totalBase) > 0) {
            totalDiscount = totalBase;
        }

        List<Money> prorated = DiscountProrater.prorate(totalDiscount, bases);
        for (int i = 0; i < participatingLineNumbers.size(); i++) {
            Money share = prorated.get(i);
            if (share.signum() > 0) {
                result.put(participatingLineNumbers.get(i), share.amount());
            }
        }
        return result;
    }

    private static boolean matchesAnyItem(PricingContext.Line line, List<RuleItem> items) {
        return firstMatchingItem(line, items) != null;
    }

    private static RuleItem firstMatchingItem(PricingContext.Line line, List<RuleItem> items) {
        for (RuleItem item : items) {
            if (matchesItem(line, item)) {
                return item;
            }
        }
        return null;
    }

    private static boolean matchesItem(PricingContext.Line line, RuleItem item) {
        return switch (item.targetType()) {
            case PRODUCT -> Objects.equals(item.targetId(), line.productId());
            case CATEGORY -> Objects.equals(item.targetId(), line.categoryId());
            case BRAND -> line.brandId() != null && Objects.equals(item.targetId(), line.brandId());
        };
    }

    private record CandidateApplication(
            Long promotionId,
            String promotionName,
            boolean stackable,
            int priority,
            BigDecimal discountAmount
    ) {
    }

    /**
     * Proyección inmutable de una regla de promoción para evaluación en memoria.
     */
    record PromotionRule(
            Long id,
            String name,
            PromotionType promotionType,
            int priority,
            boolean stackable,
            Instant validFrom,
            Instant validTo,
            short[] daysOfWeek,
            LocalTime timeFrom,
            LocalTime timeTo,
            boolean allStores,
            Set<Long> storeIds,
            BigDecimal percent,
            BigDecimal amount,
            Integer buyQty,
            Integer payQty,
            BigDecimal minQty,
            BigDecimal comboPrice,
            Integer maxApplicationsPerSale,
            boolean active,
            List<RuleItem> items
    ) {
        boolean isVigentFor(Long storeId, Instant at, ZoneId zone) {
            if (!allStores && (storeIds == null || !storeIds.contains(storeId))) {
                return false;
            }
            return PromotionVigency.isVigentAt(
                    active, validFrom, validTo, daysOfWeek, timeFrom, timeTo, at, zone);
        }
    }

    /**
     * Ítem objetivo de una regla de promoción.
     */
    record RuleItem(
            PromotionTargetType targetType,
            Long targetId,
            BigDecimal requiredQty
    ) {
    }
}
