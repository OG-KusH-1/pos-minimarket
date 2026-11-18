package cl.minimarket.pos.catalog.product.web;

import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Solicitud de creación o actualización de producto ({@code API.md} F.3).
 */
public record ProductRequest(
        @NotBlank(message = "El SKU es obligatorio.")
        @Pattern(regexp = "^[A-Za-z0-9._-]{1,40}$",
                message = "El SKU debe tener entre 1 y 40 caracteres (letras, números, '.', '_' o '-').")
        String sku,

        @NotBlank(message = "El nombre del producto es obligatorio.")
        @Size(min = 2, max = 200, message = "El nombre debe tener entre 2 y 200 caracteres.")
        String name,

        @Size(max = 2000, message = "La descripción no puede superar los 2000 caracteres.")
        String description,

        Long brandId,

        @NotNull(message = "La categoría es obligatoria.")
        Long categoryId,

        @NotNull(message = "El tipo de venta es obligatorio.")
        SaleType saleType,

        @NotNull(message = "La unidad de medida es obligatoria.")
        UnitOfMeasure unitOfMeasure,

        Boolean vatExempt,

        RestrictedType restrictedType,

        Boolean lotControlled,

        @Min(value = 1, message = "La vida útil mínima es 1 día.")
        @Max(value = 3650, message = "La vida útil máxima es 3650 días.")
        Integer shelfLifeDays,

        @Pattern(regexp = "^[A-Z0-9]{1,10}$",
                message = "El código de atajo debe tener entre 1 y 10 caracteres alfanuméricos en mayúscula.")
        String shortcutCode,

        Boolean allowManualDiscount,

        @DecimalMin(value = "0", message = "El precio inicial no puede ser negativo.")
        BigDecimal initialPriceGross,

        List<Long> taxIds,

        @Valid
        List<BarcodeRequest> barcodes,

        @Valid
        PackRequest pack,

        Long version
) {
}
