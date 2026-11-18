package cl.minimarket.pos.organization;

/**
 * Vista pública de un local para otros módulos del sistema.
 */
public record StoreInfo(
        Long id,
        String code,
        String name,
        String address,
        String commune,
        String city,
        String phone,
        String siiBranchCode,
        boolean active
) {
}
