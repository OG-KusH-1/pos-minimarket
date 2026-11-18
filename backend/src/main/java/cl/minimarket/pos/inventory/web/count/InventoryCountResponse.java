package cl.minimarket.pos.inventory.web.count;

import cl.minimarket.pos.inventory.application.count.CountStatus;
import cl.minimarket.pos.inventory.application.count.CountType;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryRefView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountDetailView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountProgressView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.DifferenceSummaryView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.UserRefView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Respuesta pública de detalle o cabecera de una toma física de inventario ({@code API.md} F.3).
 */
@Schema(description = "Cabecera y progreso de una toma de inventario")
public record InventoryCountResponse(
        @Schema(description = "ID interno de la toma", example = "12")
        Long id,

        @Schema(description = "Número correlativo único de la toma", example = "TI-001-000012")
        String number,

        @Schema(description = "ID del local", example = "1")
        Long storeId,

        @Schema(description = "Tipo de toma", example = "CYCLIC")
        CountType countType,

        @Schema(description = "Categoría raíz del alcance cíclico (null si FULL)")
        CategoryRef category,

        @Schema(description = "Estado actual de la toma", example = "SUBMITTED")
        CountStatus status,

        @Schema(description = "Si es true, oculta existencias teóricas a quien no puede aprobar", example = "true")
        boolean blind,

        @Schema(description = "Usuario que inició la toma")
        UserRef startedBy,

        @Schema(description = "Fecha/hora UTC de inicio")
        Instant startedAt,

        @Schema(description = "Fecha/hora UTC de envío a revisión")
        Instant submittedAt,

        @Schema(description = "Supervisor que aprobó la toma")
        UserRef approvedBy,

        @Schema(description = "Fecha/hora UTC de aprobación")
        Instant approvedAt,

        @Schema(description = "Motivo de rechazo o cancelación")
        String rejectionReason,

        @Schema(description = "Notas u observaciones")
        String notes,

        @Schema(description = "Avance del conteo")
        CountProgress progress,

        @Schema(description = "Resumen valorizado de diferencias (null si falta catalog:cost:read o modo ciego restringido)")
        DifferenceSummary differenceSummary,

        @Schema(description = "Versión de concurrencia optimista", example = "2")
        Long version) {

    /** Referencia resumida a la categoría del alcance. */
    @Schema(name = "InventoryCountCategoryRef", description = "Categoría asociada a una toma cíclica")
    public record CategoryRef(Long id, String name) {
        static CategoryRef from(CategoryRefView view) {
            return view != null ? new CategoryRef(view.id(), view.name()) : null;
        }
    }

    /** Referencia resumida a un usuario participante. */
    @Schema(name = "InventoryCountUserRef", description = "Usuario responsable")
    public record UserRef(Long id, String fullName) {
        static UserRef from(UserRefView view) {
            return view != null ? new UserRef(view.id(), view.fullName()) : null;
        }
    }

    /** Progreso de líneas contadas sobre el total de líneas. */
    @Schema(name = "InventoryCountProgress", description = "Avance de conteo de líneas")
    public record CountProgress(long totalLines, long countedLines) {
        static CountProgress from(CountProgressView view) {
            return view != null ? new CountProgress(view.totalLines(), view.countedLines()) : new CountProgress(0L, 0L);
        }
    }

    /** Resumen valorizado de diferencias positivas y negativas. */
    @Schema(name = "InventoryCountDifferenceSummary", description = "Resumen valorizado de diferencias")
    public record DifferenceSummary(long lines, BigDecimal valueIn, BigDecimal valueOut) {
        static DifferenceSummary from(DifferenceSummaryView view) {
            return view != null ? new DifferenceSummary(view.lines(), view.valueIn(), view.valueOut()) : null;
        }
    }

    /** Convierte la vista interna en DTO REST. */
    public static InventoryCountResponse from(CountDetailView view) {
        return new InventoryCountResponse(
                view.id(),
                view.number(),
                view.storeId(),
                view.countType(),
                CategoryRef.from(view.category()),
                view.status(),
                view.blind(),
                UserRef.from(view.startedBy()),
                view.startedAt(),
                view.submittedAt(),
                UserRef.from(view.approvedBy()),
                view.approvedAt(),
                view.rejectionReason(),
                view.notes(),
                CountProgress.from(view.progress()),
                DifferenceSummary.from(view.differenceSummary()),
                view.version());
    }
}
