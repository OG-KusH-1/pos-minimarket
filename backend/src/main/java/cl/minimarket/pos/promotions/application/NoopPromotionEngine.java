package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionEngine;
import cl.minimarket.pos.promotions.PromotionResult;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Implementación no-op de {@link PromotionEngine} para la Fase 1 (MVP) mientras no exista el motor
 * completo de promociones de la Fase 5 ({@code PLAN.md} A.4, H.3 T32).
 */
@Service
public class NoopPromotionEngine implements PromotionEngine {

    @Override
    public PromotionResult evaluate(PricingContext context) {
        Objects.requireNonNull(context, "context");
        return PromotionResult.empty();
    }
}
