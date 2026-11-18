package cl.minimarket.pos.security;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Representación explícita del límite efectivo de un usuario para una acción ({@code ROLES_PERMISOS.md} D.3).
 *
 * <p>Nunca devuelve {@code null} ni un {@code Optional} vacío con significado de "sin límite":
 * {@link Limited} acota al valor máximo (0 en modo <em>fail-closed</em> cuando no hay fila) y
 * {@link Unlimited} representa explícitamente ausencia de tope ({@code value IS NULL} en {@code role_limits}).
 */
public sealed interface RoleLimit permits RoleLimit.Limited, RoleLimit.Unlimited {

    /**
     * Límite numérico acotado (mayor o igual a cero).
     *
     * @param max valor máximo permitido; nunca nulo ni negativo
     */
    record Limited(BigDecimal max) implements RoleLimit {

        /** Valida que el tope sea no nulo y mayor o igual a cero. */
        public Limited {
            Objects.requireNonNull(max, "max");
            if (max.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("El límite no puede ser negativo: " + max);
            }
        }
    }

    /**
     * Límite explícitamente ilimitado (∞).
     */
    record Unlimited() implements RoleLimit {

        /** Instancia compartida para evitar asignaciones innecesarias. */
        public static final Unlimited INSTANCE = new Unlimited();
    }

    /**
     * Indica si este límite es ilimitado.
     *
     * @return {@code true} cuando es {@link Unlimited}
     */
    default boolean isUnlimited() {
        return this instanceof Unlimited;
    }

    /**
     * Evalúa si un monto o porcentaje dado se encuentra dentro del límite permitido (inclusive).
     *
     * @param value valor a evaluar (no nulo)
     * @return {@code true} si es {@link Unlimited} o si {@code value <= max}
     */
    default boolean allows(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case Unlimited ignored -> true;
            case Limited(BigDecimal max) -> value.compareTo(max) <= 0;
        };
    }

    /**
     * Límite cero (deniega cualquier valor mayor a cero; valor por defecto <em>fail-closed</em>).
     */
    static RoleLimit zero() {
        return new Limited(BigDecimal.ZERO);
    }

    /**
     * Crea un límite acotado al valor indicado.
     */
    static RoleLimit limited(BigDecimal max) {
        return new Limited(max);
    }

    /**
     * Retorna el límite ilimitado.
     */
    static RoleLimit unlimited() {
        return Unlimited.INSTANCE;
    }
}
