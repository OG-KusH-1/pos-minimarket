package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionResult;
import cl.minimarket.pos.promotions.PromotionTargetType;
import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.application.PromotionEvaluator.PromotionRule;
import cl.minimarket.pos.promotions.application.PromotionEvaluator.RuleItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias del motor de promociones ({@link PromotionEvaluator}) para los 5 tipos,
 * vigencia por fecha/día/hora, regla de mayor beneficio en no acumulables y
 * {@code maxApplicationsPerSale} ({@code PLAN.md} H.3 T68).
 */
class PromotionEngineTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z"); // Miércoles 12:00 en Santiago (-03)
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void percentAppliesAndRespectsMaxApplicationsPerSale() {
        PromotionRule unlimited = percentRule(1L, "20% Bebidas", 100, false,
                new BigDecimal("20"), null,
                List.of(new RuleItem(PromotionTargetType.CATEGORY, 10L, BigDecimal.ONE)));

        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 100L, 10L, 1L, "3.000", "1000", "3000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(unlimited), SANTIAGO);
        assertThat(res.forLine(1)).isPresent();
        assertThat(res.forLine(1).get().discountAmount()).isEqualByComparingTo("600");

        // Con tope de 2 aplicaciones por venta: solo 2 unidades reciben 20% = 400
        PromotionRule capped = percentRule(2L, "20% Tope 2", 100, false,
                new BigDecimal("20"), 2,
                List.of(new RuleItem(PromotionTargetType.CATEGORY, 10L, BigDecimal.ONE)));

        PromotionResult cappedRes = PromotionEvaluator.evaluate(ctx, List.of(capped), SANTIAGO);
        assertThat(cappedRes.forLine(1)).isPresent();
        assertThat(cappedRes.forLine(1).get().discountAmount()).isEqualByComparingTo("400");
    }

    @Test
    void fixedAmountAppliesPerUnitAndCapsAtLineSubtotalAndMaxApplications() {
        PromotionRule rule = new PromotionRule(
                3L, "$300 DCTO", PromotionType.FIXED_AMOUNT, 100, false,
                FROM, TO, null, null, null, true, Set.of(),
                null, new BigDecimal("300"), null, null, null, null, 2, true,
                List.of(new RuleItem(PromotionTargetType.BRAND, 5L, BigDecimal.ONE))
        );

        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 100L, 10L, 5L, "3.000", "1000", "3000"),
                line(2, 101L, 10L, 5L, "1.000", "1000", "1000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(rule), SANTIAGO);
        // Tope 2 aplicaciones consumidas en línea 1 -> $600 en línea 1, $0 en línea 2
        assertThat(res.forLine(1)).isPresent();
        assertThat(res.forLine(1).get().discountAmount()).isEqualByComparingTo("600");
        assertThat(res.forLine(2)).isEmpty();
    }

    @Test
    void buyXPayYApplies3x2SingleAndMultiLine() {
        PromotionRule threeForTwo = new PromotionRule(
                4L, "3x2 Galletas", PromotionType.BUY_X_PAY_Y, 100, false,
                FROM, TO, null, null, null, true, Set.of(),
                null, null, 3, 2, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.CATEGORY, 20L, BigDecimal.ONE))
        );

        // Caso HU-POS-07: llevar 3 a $1.000 -> descuento $1.000
        PricingContext singleLine = new PricingContext(1L, NOW, null, List.of(
                line(1, 200L, 20L, null, "3.000", "1000", "3000")
        ));
        PromotionResult singleRes = PromotionEvaluator.evaluate(singleLine, List.of(threeForTwo), SANTIAGO);
        assertThat(singleRes.forLine(1)).isPresent();
        assertThat(singleRes.forLine(1).get().discountAmount()).isEqualByComparingTo("1000");

        // Caso multi-línea en categoría: 2 a $1.000 + 1 a $800 -> la unidad gratis es la más barata ($800)
        PricingContext multiLine = new PricingContext(1L, NOW, null, List.of(
                line(1, 200L, 20L, null, "2.000", "1000", "2000"),
                line(2, 201L, 20L, null, "1.000", "800", "800")
        ));
        PromotionResult multiRes = PromotionEvaluator.evaluate(multiLine, List.of(threeForTwo), SANTIAGO);
        assertThat(multiRes.forLine(1)).isEmpty();
        assertThat(multiRes.forLine(2)).isPresent();
        assertThat(multiRes.forLine(2).get().discountAmount()).isEqualByComparingTo("800");
    }

    @Test
    void quantityPriceAppliesFromMinQty() {
        PromotionRule qtyPrice = new PromotionRule(
                5L, "Mayorista desde 6", PromotionType.QUANTITY_PRICE, 100, false,
                FROM, TO, null, null, null, true, Set.of(),
                null, new BigDecimal("800"), null, null, new BigDecimal("6.000"), null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 300L, BigDecimal.ONE))
        );

        // Línea 1 tiene 5 (< 6) -> no aplica; Línea 2 tiene 6 (>= 6, precio normal 1000 -> 800, ahorro 1200)
        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 300L, 10L, null, "5.000", "1000", "5000"),
                line(2, 300L, 10L, null, "6.000", "1000", "6000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(qtyPrice), SANTIAGO);
        assertThat(res.forLine(1)).isEmpty();
        assertThat(res.forLine(2)).isPresent();
        assertThat(res.forLine(2).get().discountAmount()).isEqualByComparingTo("1200");
    }

    @Test
    void comboProratesDiscountAcrossParticipatingLinesAndRespectsMaxApplications() {
        PromotionRule combo = new PromotionRule(
                6L, "Combo Desayuno", PromotionType.COMBO, 100, false,
                FROM, TO, null, null, null, true, Set.of(),
                null, null, null, null, null, new BigDecimal("2500"), 1, true,
                List.of(
                        new RuleItem(PromotionTargetType.PRODUCT, 401L, new BigDecimal("1.000")),
                        new RuleItem(PromotionTargetType.PRODUCT, 402L, new BigDecimal("1.000"))
                )
        );

        // 2 unidades de cada uno (normal por set = 2000 + 1000 = 3000; combo = 2500 -> ahorro 500 por set).
        // Con maxApplicationsPerSale = 1 solo aplica 1 set (ahorro total 500, prorrateado 333 + 167).
        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 401L, 10L, null, "2.000", "2000", "4000"),
                line(2, 402L, 10L, null, "2.000", "1000", "2000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(combo), SANTIAGO);
        BigDecimal d1 = res.forLine(1).orElseThrow().discountAmount();
        BigDecimal d2 = res.forLine(2).orElseThrow().discountAmount();
        assertThat(d1.add(d2)).isEqualByComparingTo("500");
        assertThat(d1).isEqualByComparingTo("333");
        assertThat(d2).isEqualByComparingTo("167");

        // Si falta uno de los productos del combo -> no aplica
        PricingContext incomplete = new PricingContext(1L, NOW, null, List.of(
                line(1, 401L, 10L, null, "2.000", "2000", "4000")
        ));
        assertThat(PromotionEvaluator.evaluate(incomplete, List.of(combo), SANTIAGO).linePromotions()).isEmpty();
    }

    @Test
    void vigencyFiltersByStoreDateDayOfWeekAndTimeInBusinessZone() {
        // 2026-11-18T15:00:00Z = miércoles (3), 12:00 America/Santiago
        PromotionRule valid = new PromotionRule(
                10L, "Vigente", PromotionType.PERCENT, 100, false,
                FROM, TO, new short[]{3}, LocalTime.of(10, 0), LocalTime.of(14, 0),
                false, Set.of(1L),
                new BigDecimal("10"), null, null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );
        PromotionRule wrongStore = new PromotionRule(
                11L, "Otro local", PromotionType.PERCENT, 100, false,
                FROM, TO, new short[]{3}, LocalTime.of(10, 0), LocalTime.of(14, 0),
                false, Set.of(2L),
                new BigDecimal("50"), null, null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );
        PromotionRule wrongDay = new PromotionRule(
                12L, "Otro día", PromotionType.PERCENT, 100, false,
                FROM, TO, new short[]{1, 2}, LocalTime.of(10, 0), LocalTime.of(14, 0),
                true, Set.of(),
                new BigDecimal("50"), null, null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );
        PromotionRule wrongHour = new PromotionRule(
                13L, "Otra hora", PromotionType.PERCENT, 100, false,
                FROM, TO, new short[]{3}, LocalTime.of(15, 0), LocalTime.of(18, 0),
                true, Set.of(),
                new BigDecimal("50"), null, null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );
        PromotionRule inactive = new PromotionRule(
                14L, "Inactiva", PromotionType.PERCENT, 100, false,
                FROM, TO, null, null, null,
                true, Set.of(),
                new BigDecimal("50"), null, null, null, null, null, null, false,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );
        PromotionRule expired = new PromotionRule(
                15L, "Vencida", PromotionType.PERCENT, 100, false,
                FROM, NOW, null, null, null, // validTo == NOW es exclusivo
                true, Set.of(),
                new BigDecimal("50"), null, null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );

        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 100L, 10L, null, "1.000", "1000", "1000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(
                ctx, List.of(valid, wrongStore, wrongDay, wrongHour, inactive, expired), SANTIAGO);
        assertThat(res.forLine(1)).isPresent();
        assertThat(res.forLine(1).get().promotionId()).isEqualTo(10L);
        assertThat(res.forLine(1).get().discountAmount()).isEqualByComparingTo("100");
    }

    @Test
    void nonStackablePicksGreatestBenefitEvenWithHigherPriorityNumber() {
        // Prioridad 10 da $100; prioridad 50 da $250 -> debe ganar la de mayor beneficio ($250)
        PromotionRule lowBenefit = percentRule(20L, "10% Prioridad alta", 10, false,
                new BigDecimal("10"), null,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE)));
        PromotionRule highBenefit = percentRule(21L, "25% Mayor beneficio", 50, false,
                new BigDecimal("25"), null,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE)));

        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 100L, 10L, null, "1.000", "1000", "1000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(lowBenefit, highBenefit), SANTIAGO);
        assertThat(res.forLine(1)).isPresent();
        assertThat(res.forLine(1).get().promotionId()).isEqualTo(21L);
        assertThat(res.forLine(1).get().discountAmount()).isEqualByComparingTo("250");
    }

    @Test
    void stackablePromotionsAccumulateUpToLineSubtotal() {
        PromotionRule stack1 = percentRule(30L, "10% Acumulable", 10, true,
                new BigDecimal("10"), null,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE)));
        PromotionRule stack2 = new PromotionRule(
                31L, "$150 Acumulable", PromotionType.FIXED_AMOUNT, 20, true,
                FROM, TO, null, null, null, true, Set.of(),
                null, new BigDecimal("150"), null, null, null, null, null, true,
                List.of(new RuleItem(PromotionTargetType.PRODUCT, 100L, BigDecimal.ONE))
        );

        PricingContext ctx = new PricingContext(1L, NOW, null, List.of(
                line(1, 100L, 10L, null, "1.000", "1000", "1000")
        ));

        PromotionResult res = PromotionEvaluator.evaluate(ctx, List.of(stack1, stack2), SANTIAGO);
        assertThat(res.forLine(1)).isPresent();
        assertThat(res.forLine(1).get().discountAmount()).isEqualByComparingTo("250");
    }

    private static PromotionRule percentRule(
            Long id,
            String name,
            int priority,
            boolean stackable,
            BigDecimal percent,
            Integer maxApps,
            List<RuleItem> items
    ) {
        return new PromotionRule(
                id, name, PromotionType.PERCENT, priority, stackable,
                FROM, TO, null, null, null, true, Set.of(),
                percent, null, null, null, null, null, maxApps, true, items
        );
    }

    private static PricingContext.Line line(
            int lineNumber,
            Long productId,
            Long categoryId,
            Long brandId,
            String qty,
            String unitPrice,
            String subtotal
    ) {
        return new PricingContext.Line(
                lineNumber,
                productId,
                categoryId,
                brandId,
                new BigDecimal(qty),
                new BigDecimal(unitPrice),
                new BigDecimal(subtotal)
        );
    }
}
