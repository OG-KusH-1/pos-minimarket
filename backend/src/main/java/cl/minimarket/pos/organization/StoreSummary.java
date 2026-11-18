package cl.minimarket.pos.organization;

/**
 * Resumen de un local ({@code API.md} F.3 {@code StoreSummary}).
 */
public record StoreSummary(
        Long id,
        String code,
        String name,
        boolean active
) {
}
