package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.product.domain.PriceHistory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Entrada del historial de precios y costos de un producto ({@code API.md} F.3).
 *
 * <p>{@code oldCost} y {@code newCost} se ocultan ({@code null}) sin el permiso
 * {@code catalog:cost:read}.
 */
public record PriceHistoryResponse(
        Long id,
        PriceChangeType changeType,
        BigDecimal oldPriceGross,
        BigDecimal newPriceGross,
        BigDecimal oldCost,
        BigDecimal newCost,
        String reason,
        String sourceType,
        Long sourceId,
        Long changedBy,
        OffsetDateTime changedAt
) {
    /** Proyecta desde la entidad {@link PriceHistory} respetando la visibilidad de costos. */
    public static PriceHistoryResponse from(PriceHistory entry, boolean includeCosts) {
        return new PriceHistoryResponse(
                entry.getId(),
                entry.getChangeType(),
                entry.getOldPriceGross(),
                entry.getNewPriceGross(),
                includeCosts ? entry.getOldCost() : null,
                includeCosts ? entry.getNewCost() : null,
                entry.getReason(),
                entry.getSourceType(),
                entry.getSourceId(),
                entry.getChangedBy(),
                entry.getChangedAt()
        );
    }
}
