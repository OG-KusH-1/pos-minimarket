package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de {@link NoopPromotionEngine} (T32).
 */
class NoopPromotionEngineTest {

    private final NoopPromotionEngine engine = new NoopPromotionEngine();

    @Test
    void evaluateAlwaysReturnsEmptyResult() {
        PricingContext context = new PricingContext(
                1L,
                Instant.parse("2026-11-13T15:00:00Z"),
                null,
                List.of(new PricingContext.Line(
                        1,
                        10L,
                        2L,
                        3L,
                        new BigDecimal("2.000"),
                        new BigDecimal("1500"),
                        new BigDecimal("3000")
                ))
        );

        PromotionResult result = engine.evaluate(context);

        assertThat(result.linePromotions()).isEmpty();
        assertThat(result.forLine(1)).isEmpty();
    }
}
