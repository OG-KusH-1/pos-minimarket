package cl.minimarket.pos.inventory.application.adjustment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Proyección de lectura de un ajuste manual de stock y sus líneas ({@code API.md} F.3).
 *
 * <p>Los campos de costo ({@code totalCostImpact} y {@code lines[].unitCost}) llegan en
 * {@code null} cuando el usuario consultante no posee {@code catalog:cost:read} ni
 * {@code inventory:adjustment:approve} ({@code ROLES_PERMISOS.md} D.2).
 */
public record StockAdjustmentView(
        Long id,
        String number,
        AdjustmentStatus status,
        AdjustmentReason reasonCode,
        String notes,
        BigDecimal totalCostImpact,
        UserSummaryView requestedBy,
        UserSummaryView approvedBy,
        OffsetDateTime approvedAt,
        String rejectionReason,
        OffsetDateTime createdAt,
        List<LineView> lines
) {

    /** Referencia resumida a un usuario participante en el flujo del ajuste. */
    public record UserSummaryView(
            Long id,
            String username,
            String fullName
    ) {
    }

    /** Proyección de una línea del ajuste con datos de producto y lote. */
    public record LineView(
            Long id,
            Long productId,
            String sku,
            String name,
            Long lotId,
            String lotNumber,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            Long stockMovementId
    ) {
    }
}
