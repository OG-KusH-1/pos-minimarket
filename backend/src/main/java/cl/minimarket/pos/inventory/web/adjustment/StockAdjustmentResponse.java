package cl.minimarket.pos.inventory.web.adjustment;

import cl.minimarket.pos.inventory.application.adjustment.AdjustmentReason;
import cl.minimarket.pos.inventory.application.adjustment.AdjustmentStatus;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentView;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentView.LineView;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentView.UserSummaryView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Representación pública de un ajuste manual de stock ({@code API.md} F.3).
 *
 * <p>Los campos {@code totalCostImpact} y {@code lines[].unitCost} son visibles únicamente para
 * usuarios con {@code catalog:cost:read} o {@code inventory:adjustment:approve}; para el resto se
 * devuelven en {@code null} ({@code ROLES_PERMISOS.md} D.2).
 */
@Schema(description = "Detalle de un ajuste manual de stock y sus líneas.")
public record StockAdjustmentResponse(
        @Schema(description = "Identificador único del ajuste.")
        Long id,

        @Schema(description = "Folio interno único del ajuste (p. ej. L01-AJ-000001).")
        String number,

        @Schema(description = "Estado del ajuste en su ciclo de aprobación.")
        AdjustmentStatus status,

        @Schema(description = "Motivo tipificado del ajuste.")
        AdjustmentReason reasonCode,

        @Schema(description = "Observaciones de respaldo.")
        String notes,

        @Schema(description = "Impacto económico neto en costo (null si el usuario no tiene permiso para ver costos).")
        BigDecimal totalCostImpact,

        @Schema(description = "Usuario que solicitó el ajuste.")
        UserRef requestedBy,

        @Schema(description = "Usuario que aprobó o rechazó el ajuste, si ya fue resuelto.")
        UserRef approvedBy,

        @Schema(description = "Instante UTC de aprobación o rechazo.")
        OffsetDateTime approvedAt,

        @Schema(description = "Motivo del rechazo cuando status = REJECTED.")
        String rejectionReason,

        @Schema(description = "Instante UTC de creación de la solicitud.")
        OffsetDateTime createdAt,

        @Schema(description = "Líneas del ajuste.")
        List<LineResponse> lines
) {

    /** Referencia compacta a un usuario participante. */
    @Schema(name = "StockAdjustmentUserRef", description = "Identificación resumida de usuario.")
    public record UserRef(
            @Schema(description = "Identificador del usuario.")
            Long id,
            @Schema(description = "Nombre de usuario.")
            String username,
            @Schema(description = "Nombre completo.")
            String fullName
    ) {
    }

    /** Línea individual del ajuste de stock. */
    @Schema(name = "StockAdjustmentLineResponse", description = "Línea individual de un ajuste de stock.")
    public record LineResponse(
            @Schema(description = "Identificador de la línea.")
            Long id,
            @Schema(description = "Identificador del producto.")
            Long productId,
            @Schema(description = "SKU del producto.")
            String sku,
            @Schema(description = "Nombre del producto.")
            String name,
            @Schema(description = "Identificador del lote, si aplica.")
            Long lotId,
            @Schema(description = "Número del lote, si aplica.")
            String lotNumber,
            @Schema(description = "Variación de cantidad solicitada.")
            BigDecimal quantityDelta,
            @Schema(description = "Costo unitario aplicado al ajuste (null si el usuario no puede ver costos).")
            BigDecimal unitCost,
            @Schema(description = "Identificador del movimiento generado en kardex al aprobar.")
            Long stockMovementId
    ) {
    }

    /** Convierte la proyección de aplicación al DTO HTTP de respuesta. */
    public static StockAdjustmentResponse from(StockAdjustmentView view) {
        return new StockAdjustmentResponse(
                view.id(),
                view.number(),
                view.status(),
                view.reasonCode(),
                view.notes(),
                view.totalCostImpact(),
                toUserRef(view.requestedBy()),
                toUserRef(view.approvedBy()),
                view.approvedAt(),
                view.rejectionReason(),
                view.createdAt(),
                view.lines().stream().map(StockAdjustmentResponse::toLineResponse).toList());
    }

    private static UserRef toUserRef(UserSummaryView user) {
        return user == null ? null : new UserRef(user.id(), user.username(), user.fullName());
    }

    private static LineResponse toLineResponse(LineView line) {
        return new LineResponse(
                line.id(),
                line.productId(),
                line.sku(),
                line.name(),
                line.lotId(),
                line.lotNumber(),
                line.quantityDelta(),
                line.unitCost(),
                line.stockMovementId());
    }
}
