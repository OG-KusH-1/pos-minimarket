package cl.minimarket.pos.shrinkage.web;

import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.ShrinkageView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.LotSummaryView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.ProductSummaryView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.UserSummaryView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Representación HTTP de una merma ({@code API.md} F.3).
 *
 * <p>Los campos {@code unitCost} y {@code totalCost} son visibles únicamente para usuarios con
 * {@code shrinkage:record:approve} o {@code catalog:cost:read} ({@code ROLES_PERMISOS.md} D.2).
 */
@Schema(name = "ShrinkageResponse", description = "Detalle de una merma manual o sistémica.")
public record ShrinkageResponse(
        @Schema(description = "Identificador único de la merma.")
        Long id,

        @Schema(description = "Folio correlativo de la merma (p. ej. L01-MR-000001).")
        String number,

        @Schema(description = "Producto afectado.")
        ProductRef product,

        @Schema(description = "Lote afectado, si aplica.")
        LotRef lot,

        @Schema(description = "Tipo de merma.")
        ShrinkageType shrinkageType,

        @Schema(description = "Cantidad mermada.")
        BigDecimal quantity,

        @Schema(description = "Costo unitario (null sin permiso de lectura de costos).")
        BigDecimal unitCost,

        @Schema(description = "Costo total (null sin permiso de lectura de costos).")
        BigDecimal totalCost,

        @Schema(description = "Motivo de la merma.")
        String reason,

        @Schema(description = "URL de descarga de la evidencia adjunta, si existe.")
        String evidenceUrl,

        @Schema(description = "Usuario responsable.")
        UserRef responsible,

        @Schema(description = "Usuario que reportó la merma.")
        UserRef reportedBy,

        @Schema(description = "Estado de aprobación.")
        ShrinkageStatus status,

        @Schema(description = "Indica si se autoaprobó por monto dentro del límite del reportante.")
        boolean autoApproved,

        @Schema(description = "Indica si la merma descuenta stock al aprobarse (false en mermas sistémicas).")
        boolean affectsStock,

        @Schema(description = "Usuario que aprobó o rechazó la merma.")
        UserRef approvedBy,

        @Schema(description = "Instante UTC de aprobación o rechazo.")
        OffsetDateTime approvedAt,

        @Schema(description = "Motivo de rechazo cuando status = REJECTED.")
        String rejectionReason,

        @Schema(description = "Instante UTC en que ocurrió la merma.")
        OffsetDateTime occurredAt,

        @Schema(description = "Identificador de la línea de transferencia origen, si es sistémica.")
        Long transferLineId,

        @Schema(description = "Identificador de la línea de devolución origen, si es sistémica.")
        Long saleReturnLineId) {

    /** Resumen del producto afectado. */
    @Schema(name = "ShrinkageProductRef", description = "Resumen del producto afectado.")
    public record ProductRef(
            @Schema(description = "Identificador del producto.") Long id,
            @Schema(description = "SKU del producto.") String sku,
            @Schema(description = "Nombre del producto.") String name) {
    }

    /** Resumen del lote afectado. */
    @Schema(name = "ShrinkageLotRef", description = "Resumen del lote afectado.")
    public record LotRef(
            @Schema(description = "Identificador del lote.") Long id,
            @Schema(description = "Número de lote.") String lotNumber,
            @Schema(description = "Fecha de vencimiento del lote.") LocalDate expiryDate) {
    }

    /** Resumen de un usuario participante. */
    @Schema(name = "ShrinkageUserRef", description = "Identificación resumida de usuario.")
    public record UserRef(
            @Schema(description = "Identificador del usuario.") Long id,
            @Schema(description = "Nombre de usuario.") String username,
            @Schema(description = "Nombre completo.") String fullName) {
    }

    /** Convierte la proyección de aplicación al DTO HTTP de respuesta. */
    public static ShrinkageResponse from(ShrinkageView view) {
        String evidenceUrl = view.evidenceFileId() != null
                ? "/api/v1/files/" + view.evidenceFileId() + "/content"
                : null;
        return new ShrinkageResponse(
                view.id(),
                view.number(),
                toProductRef(view.product()),
                toLotRef(view.lot()),
                view.shrinkageType(),
                view.quantity(),
                view.unitCost(),
                view.totalCost(),
                view.reason(),
                evidenceUrl,
                toUserRef(view.responsible()),
                toUserRef(view.reportedBy()),
                view.status(),
                view.autoApproved(),
                view.affectsStock(),
                toUserRef(view.approvedBy()),
                view.approvedAt(),
                view.rejectionReason(),
                view.occurredAt(),
                view.transferLineId(),
                view.saleReturnLineId());
    }

    private static ProductRef toProductRef(ProductSummaryView p) {
        return p == null ? null : new ProductRef(p.id(), p.sku(), p.name());
    }

    private static LotRef toLotRef(LotSummaryView l) {
        return l == null ? null : new LotRef(l.id(), l.lotNumber(), l.expiryDate());
    }

    private static UserRef toUserRef(UserSummaryView u) {
        return u == null ? null : new UserRef(u.id(), u.username(), u.fullName());
    }
}
