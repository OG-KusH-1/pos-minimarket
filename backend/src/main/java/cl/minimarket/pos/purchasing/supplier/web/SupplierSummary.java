package cl.minimarket.pos.purchasing.supplier.web;

import cl.minimarket.pos.purchasing.supplier.application.SupplierService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resumen de proveedor para listados paginados ({@code API.md} F.3 {@code SupplierSummary}).
 */
@Schema(name = "SupplierSummary", description = "Resumen de proveedor para listados paginados.")
public record SupplierSummary(
        @Schema(description = "Identificador único del proveedor.") Long id,
        @Schema(description = "RUT normalizado.") String rut,
        @Schema(description = "Razón social.") String legalName,
        @Schema(description = "Nombre de fantasía.") String tradeName,
        @Schema(description = "Tiempo de reposición en días.") int leadTimeDays,
        @Schema(description = "Indica si el proveedor está activo.") boolean active
) {

    /** Proyecta desde la vista resumida de {@link SupplierService}. */
    public static SupplierSummary from(SupplierService.SupplierSummaryView view) {
        return new SupplierSummary(
                view.id(),
                view.rut(),
                view.legalName(),
                view.tradeName(),
                view.leadTimeDays(),
                view.active()
        );
    }
}
