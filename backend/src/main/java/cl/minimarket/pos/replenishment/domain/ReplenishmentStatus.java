package cl.minimarket.pos.replenishment.domain;

/**
 * Estados de una línea de reabastecimiento definidos en {@code MODELO_DATOS.md} B.14.
 *
 * <p>El calculador genera únicamente {@link #SUGGESTED}, {@link #NO_ACTION},
 * {@link #NO_SUPPLIER} o {@link #EXCLUDED}. Los dos estados restantes pertenecen a la revisión
 * posterior de la sugerencia por parte del usuario.</p>
 */
public enum ReplenishmentStatus {
    SUGGESTED,
    NO_ACTION,
    NO_SUPPLIER,
    EXCLUDED,
    ACCEPTED,
    DISCARDED
}
