package cl.minimarket.pos.purchasing.supplier.web;

import cl.minimarket.pos.purchasing.supplier.domain.PaymentTermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Solicitud de creación o actualización de proveedor ({@code API.md} F.3 {@code SupplierRequest}).
 */
@Schema(name = "SupplierRequest", description = "Datos de creación o edición de un proveedor.")
public record SupplierRequest(
        @Schema(description = "RUT chileno del proveedor (validado por módulo 11).", example = "76123456-7")
        @NotBlank(message = "El RUT es obligatorio.")
        @Size(max = 20, message = "El RUT no puede superar los 20 caracteres.")
        String rut,

        @Schema(description = "Razón social del proveedor.", example = "Distribuidora Central SpA")
        @NotBlank(message = "La razón social es obligatoria.")
        @Size(min = 2, max = 200, message = "La razón social debe tener entre 2 y 200 caracteres.")
        String legalName,

        @Schema(description = "Nombre de fantasía opcional.")
        @Size(max = 200, message = "El nombre de fantasía no puede superar los 200 caracteres.")
        String tradeName,

        @Schema(description = "Giro comercial.")
        @Size(max = 200, message = "El giro no puede superar los 200 caracteres.")
        String businessActivity,

        @Schema(description = "Dirección comercial.")
        @Size(max = 250, message = "La dirección no puede superar los 250 caracteres.")
        String address,

        @Schema(description = "Comuna.")
        @Size(max = 100, message = "La comuna no puede superar los 100 caracteres.")
        String commune,

        @Schema(description = "Ciudad.")
        @Size(max = 100, message = "La ciudad no puede superar los 100 caracteres.")
        String city,

        @Schema(description = "Teléfono principal.")
        @Size(max = 30, message = "El teléfono no puede superar los 30 caracteres.")
        String phone,

        @Schema(description = "Correo electrónico principal.")
        @Email(message = "El correo electrónico no tiene un formato válido.")
        @Size(max = 254, message = "El correo electrónico no puede superar los 254 caracteres.")
        String email,

        @Schema(description = "Nombre de la persona de contacto.")
        @Size(max = 150, message = "El nombre de contacto no puede superar los 150 caracteres.")
        String contactName,

        @Schema(description = "Teléfono de contacto.")
        @Size(max = 30, message = "El teléfono de contacto no puede superar los 30 caracteres.")
        String contactPhone,

        @Schema(description = "Correo electrónico de contacto.")
        @Email(message = "El correo de contacto no tiene un formato válido.")
        @Size(max = 254, message = "El correo de contacto no puede superar los 254 caracteres.")
        String contactEmail,

        @Schema(description = "Condición de pago (CASH_ON_DELIVERY o CREDIT).")
        @NotNull(message = "La condición de pago es obligatoria.")
        PaymentTermsType paymentTermsType,

        @Schema(description = "Plazo de pago en días (0 a 180).", example = "30")
        @Min(value = 0, message = "Los días de plazo no pueden ser negativos.")
        @Max(value = 180, message = "Los días de plazo no pueden superar 180.")
        Integer paymentTermsDays,

        @Schema(description = "Tiempo de reposición en días (0 a 90).", example = "2")
        @NotNull(message = "El lead time es obligatorio.")
        @Min(value = 0, message = "El lead time no puede ser negativo.")
        @Max(value = 90, message = "El lead time no puede superar los 90 días.")
        Integer leadTimeDays,

        @Schema(description = "Días ISO de despacho (1 = lunes ... 7 = domingo), sin repetir.", example = "[1, 4]")
        List<Integer> deliveryWeekdays,

        @Schema(description = "Observaciones o notas internas.")
        @Size(max = 2000, message = "Las notas no pueden superar los 2000 caracteres.")
        String notes,

        @Schema(description = "Versión de concurrencia optimista (opcional en creación; validada en actualización si se informa).")
        Long version
) {
}
