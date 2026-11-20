package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.promotions.PricingContext;
import cl.minimarket.pos.promotions.PromotionEngine;
import cl.minimarket.pos.promotions.PromotionResult;
import cl.minimarket.pos.promotions.application.PromotionEvaluator.PromotionRule;
import cl.minimarket.pos.promotions.application.PromotionEvaluator.RuleItem;
import cl.minimarket.pos.promotions.domain.Promotion;
import cl.minimarket.pos.promotions.domain.PromotionItem;
import cl.minimarket.pos.promotions.domain.PromotionRepository;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Implementación real de {@link PromotionEngine} respaldada por las reglas persistidas en
 * {@code promotions} y la zona horaria de {@link BusinessClock} ({@code PLAN.md} H.3 T68).
 */
@Service
public class RealPromotionEngine implements PromotionEngine {

    private final PromotionRepository promotionRepository;
    private final BusinessClock businessClock;

    public RealPromotionEngine(PromotionRepository promotionRepository, BusinessClock businessClock) {
        this.promotionRepository = Objects.requireNonNull(promotionRepository, "promotionRepository");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionResult evaluate(PricingContext context) {
        Objects.requireNonNull(context, "context");
        if (context.lines().isEmpty()) {
            return PromotionResult.empty();
        }

        List<Promotion> candidates = promotionRepository.findCandidatesForStoreAt(
                context.storeId(),
                context.evaluatedAt()
        );
        if (candidates.isEmpty()) {
            return PromotionResult.empty();
        }

        List<PromotionRule> rules = candidates.stream()
                .map(p -> toRule(p, context.storeId()))
                .toList();

        return PromotionEvaluator.evaluate(context, rules, businessClock.zone());
    }

    private static PromotionRule toRule(Promotion p, Long storeId) {
        List<RuleItem> items = p.getItems().stream()
                .map(RealPromotionEngine::toRuleItem)
                .toList();
        // La consulta findCandidatesForStoreAt ya filtró por storeId cuando allStores = false
        Set<Long> storeIds = p.isAllStores() ? Set.of() : Set.of(storeId);
        return new PromotionRule(
                p.getId(),
                p.getName(),
                p.getPromotionType(),
                p.getPriority(),
                p.isStackable(),
                p.getValidFrom(),
                p.getValidTo(),
                p.getDaysOfWeek(),
                p.getTimeFrom(),
                p.getTimeTo(),
                p.isAllStores(),
                storeIds,
                p.getPercent(),
                p.getAmount(),
                p.getBuyQty(),
                p.getPayQty(),
                p.getMinQty(),
                p.getComboPrice(),
                p.getMaxApplicationsPerSale(),
                p.isActive(),
                items
        );
    }

    private static RuleItem toRuleItem(PromotionItem item) {
        return new RuleItem(item.getTargetType(), item.targetId(), item.getRequiredQty());
    }
}
