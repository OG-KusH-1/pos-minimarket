package cl.minimarket.pos.catalog.brand.web;

import cl.minimarket.pos.catalog.brand.domain.Brand;

/**
 * Respuesta pública de marca ({@code API.md} F.3).
 */
public record BrandResponse(
        Long id,
        String name,
        boolean active
) {

    /** Convierte una entidad {@link Brand} a su DTO de respuesta. */
    public static BrandResponse from(Brand brand) {
        return new BrandResponse(brand.getId(), brand.getName(), brand.isActive());
    }
}
