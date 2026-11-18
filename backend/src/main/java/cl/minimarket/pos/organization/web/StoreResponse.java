package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.domain.Store;

/**
 * DTO de respuesta de un local ({@code API.md} F.3 {@code StoreResponse}).
 */
public record StoreResponse(
        Long id,
        String code,
        String name,
        String address,
        String commune,
        String city,
        String phone,
        String siiBranchCode,
        boolean active,
        Long version
) {

    /** Construye el DTO desde la entidad persistida. */
    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getId(),
                store.getCode(),
                store.getName(),
                store.getAddress(),
                store.getCommune(),
                store.getCity(),
                store.getPhone(),
                store.getSiiBranchCode(),
                store.isActive(),
                store.getVersion()
        );
    }
}
