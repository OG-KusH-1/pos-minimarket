package cl.minimarket.pos.catalog.product.web;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Solicitud para reemplazar la configuración tributaria de un producto ({@code API.md} F.3).
 */
public record ProductTaxesRequest(
        @NotNull(message = "Debe indicar si el producto es exento de IVA.")
        Boolean vatExempt,

        @NotNull(message = "La lista de impuestos es obligatoria.")
        List<Long> taxIds
) {
}
