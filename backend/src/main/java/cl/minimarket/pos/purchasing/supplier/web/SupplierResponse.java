package cl.minimarket.pos.purchasing.supplier.web;

import cl.minimarket.pos.purchasing.supplier.domain.PaymentTermsType;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Ficha completa de un proveedor ({@code API.md} F.3 {@code SupplierResponse}).
 */
@Schema(name = "SupplierResponse", description = "Detalle completo de un proveedor.")
public record SupplierResponse(
        @Schema(description = "Identificador único del proveedor.") Long id,
        @Schema(description = "RUT normalizado del proveedor.") String rut,
        @Schema(description = "Razón social.") String legalName,
        @Schema(description = "Nombre de fantasía.") String tradeName,
        @Schema(description = "Giro comercial.") String businessActivity,
        @Schema(description = "Dirección comercial.") String address,
        @Schema(description = "Comuna.") String commune,
        @Schema(description = "Ciudad.") String city,
        @Schema(description = "Teléfono principal.") String phone,
        @Schema(description = "Correo electrónico principal.") String email,
        @Schema(description = "Nombre de contacto.") String contactName,
        @Schema(description = "Teléfono de contacto.") String contactPhone,
        @Schema(description = "Correo de contacto.") String contactEmail,
        @Schema(description = "Condición de pago.") PaymentTermsType paymentTermsType,
        @Schema(description = "Plazo de pago en días.") int paymentTermsDays,
        @Schema(description = "Tiempo de reposición en días.") int leadTimeDays,
        @Schema(description = "Días ISO de despacho (1..7).") List<Integer> deliveryWeekdays,
        @Schema(description = "Notas internas.") String notes,
        @Schema(description = "Indica si el proveedor está activo.") boolean active,
        @Schema(description = "Versión de concurrencia optimista.") Long version
) {

    /** Proyecta desde la entidad {@link Supplier}. */
    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getRut(),
                supplier.getLegalName(),
                supplier.getTradeName(),
                supplier.getBusinessActivity(),
                supplier.getAddress(),
                supplier.getCommune(),
                supplier.getCity(),
                supplier.getPhone(),
                supplier.getEmail(),
                supplier.getContactName(),
                supplier.getContactPhone(),
                supplier.getContactEmail(),
                supplier.getPaymentTermsType(),
                supplier.getPaymentTermsDays(),
                supplier.getLeadTimeDays(),
                supplier.getDeliveryWeekdaysList(),
                supplier.getNotes(),
                supplier.isActive(),
                supplier.getVersion()
        );
    }
}
