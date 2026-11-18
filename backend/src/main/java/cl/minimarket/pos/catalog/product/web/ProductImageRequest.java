package cl.minimarket.pos.catalog.product.web;

import jakarta.validation.constraints.NotNull;

/**
 * Solicitud para asociar una imagen al producto ({@code API.md} F.3).
 */
public record ProductImageRequest(
        @NotNull(message = "El identificador del archivo es obligatorio.")
        Long fileId
) {
}
