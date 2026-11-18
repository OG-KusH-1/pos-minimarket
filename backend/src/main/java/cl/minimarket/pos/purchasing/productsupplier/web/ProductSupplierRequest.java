package cl.minimarket.pos.purchasing.productsupplier.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Solicitud de creación o actualización de asociación producto-proveedor
 * ({@code API.md} F.3 {@code ProductSupplierRequest}).
 */
@Schema(name = "ProductSupplierRequest", description = "Datos de asociación entre producto y proveedor.")
public record ProductSupplierRequest(
        @Schema(description = "Identificador del producto (no puede ser PACK).")
        @NotNull(message = "El producto es obligatorio.")
        Long productId,

        @Schema(description = "Identificador del proveedor.")
        @NotNull(message = "El proveedor es obligatorio.")
        Long supplierId,

        @Schema(description = "Código o SKU asignado por el proveedor.")
        @Size(max = 40, message = "El SKU del proveedor no puede superar los 40 caracteres.")
        String supplierSku,

        @Schema(description = "Nombre de la unidad de compra (p. ej. Caja, Display, Saco).", example = "Caja")
        @NotBlank(message = "La unidad de compra es obligatoria.")
        @Size(min = 1, max = 30, message = "La unidad de compra debe tener entre 1 y 30 caracteres.")
        String purchaseUnitName,

        @Schema(description = "Cantidad de unidades base contenidas en una unidad de compra (> 0).", example = "12")
        @NotNull(message = "Las unidades por unidad de compra son obligatorias.")
        @DecimalMin(value = "0", inclusive = false, message = "Las unidades por unidad de compra deben ser mayores que 0.")
        BigDecimal unitsPerPurchaseUnit,

        @Schema(description = "Costo neto por unidad de compra (>= 0).", example = "6000")
        @NotNull(message = "El costo por unidad de compra es obligatorio.")
        @DecimalMin(value = "0", message = "El costo por unidad de compra no puede ser negativo.")
        BigDecimal costPerPurchaseUnit,

        @Schema(description = "Pedido mínimo en unidades de compra (>= 1).", example = "1")
        @NotNull(message = "La cantidad mínima de pedido es obligatoria.")
        @DecimalMin(value = "1", message = "La cantidad mínima de pedido debe ser mayor o igual a 1.")
        BigDecimal minOrderQty,

        @Schema(description = "Lead time específico en días (0 a 90; null usa el del proveedor).")
        @Min(value = 0, message = "El lead time específico no puede ser negativo.")
        @Max(value = 90, message = "El lead time específico no puede superar los 90 días.")
        Integer leadTimeDaysOverride,

        @Schema(description = "Indica si este proveedor es el preferido para el producto.")
        Boolean isPreferred,

        @Schema(description = "Versión de concurrencia optimista (opcional en creación; validada en actualización si se informa).")
        Long version
) {
}
