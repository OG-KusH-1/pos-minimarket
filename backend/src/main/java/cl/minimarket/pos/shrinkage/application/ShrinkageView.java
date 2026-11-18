package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Proyección de lectura de una merma para el listado y detalle ({@code API.md} F.3).
 *
 * <p>Los campos {@code unitCost} y {@code totalCost} se entregan en {@code null} cuando el usuario
 * consultante no tiene permiso para ver costos.
 */
public record ShrinkageView(
        Long id,
        String number,
        ProductSummaryView product,
        LotSummaryView lot,
        ShrinkageType shrinkageType,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String reason,
        Long evidenceFileId,
        UserSummaryView responsible,
        UserSummaryView reportedBy,
        ShrinkageStatus status,
        boolean autoApproved,
        boolean affectsStock,
        UserSummaryView approvedBy,
        OffsetDateTime approvedAt,
        String rejectionReason,
        OffsetDateTime occurredAt,
        Long transferLineId,
        Long saleReturnLineId) {

    /** Resumen del producto afectado. */
    public record ProductSummaryView(Long id, String sku, String name) {
    }

    /** Resumen del lote afectado, cuando aplica. */
    public record LotSummaryView(Long id, String lotNumber, LocalDate expiryDate) {
    }

    /** Resumen de un usuario participante. */
    public record UserSummaryView(Long id, String username, String fullName) {
    }
}
