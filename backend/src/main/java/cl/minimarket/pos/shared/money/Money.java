package cl.minimarket.pos.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Monto en pesos chilenos (CLP): value object inmutable de escala 0 con redondeo HALF_UP.
 *
 * <p>Decisión de diseño: el constructor normaliza siempre a escala 0, de modo que {@code equals}
 * y {@code hashCode} no dependan de la escala de entrada (con {@link BigDecimal} puro,
 * {@code 10} y {@code 10.0} serían distintos). Es el tipo previsto para precios, totales,
 * impuestos y pagos (A.5); los costos (escalas 4 y 2) se redondean con {@link Rounding} porque no
 * son montos de venta.
 *
 * <p>Las operaciones {@link #plus}, {@link #minus} y {@link #negate} son exactas; solo
 * {@link #times(Quantity)} y {@link #times(BigDecimal)} redondean, y lo hacen una única vez al
 * final sobre el producto exacto (sin intermedios con pérdida).
 *
 * @param amount monto con escala 0 (se redondea HALF_UP si llega con decimales; no puede ser nulo)
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    /** Cero pesos. */
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    /**
     * Normaliza el monto a escala 0 con HALF_UP.
     *
     * @throws NullPointerException si {@code amount} es nulo
     */
    public Money {
        amount = Rounding.clp(Objects.requireNonNull(amount, "amount"));
    }

    /** Crea un monto desde pesos enteros. */
    public static Money of(long pesos) {
        return new Money(BigDecimal.valueOf(pesos));
    }

    /** Crea un monto desde un decimal, redondeando a escala 0 con HALF_UP. */
    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    /** Suma una colección de montos; vacía devuelve {@link #ZERO}. */
    public static Money sum(Iterable<Money> values) {
        Money total = ZERO;
        for (Money value : values) {
            total = total.plus(value);
        }
        return total;
    }

    /** Suma exacta. */
    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    /** Resta exacta. */
    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Cambia el signo. */
    public Money negate() {
        return new Money(amount.negate());
    }

    /**
     * Multiplica por una cantidad: {@code round(amount × quantity, 0)} (HALF_UP). Es la fórmula de
     * la línea pesable de A.5: {@code line_subtotal_gross = round(qty_kg × price_per_kg, 0)}.
     */
    public Money times(Quantity quantity) {
        return times(quantity.value());
    }

    /** Multiplica por un factor decimal: {@code round(amount × factor, 0)} (HALF_UP). */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor).setScale(Rounding.CLP_SCALE, RoundingMode.HALF_UP));
    }

    /** Signo del monto: -1, 0 o 1. */
    public int signum() {
        return amount.signum();
    }

    /** Indica si el monto es cero. */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** Indica si el monto es menor que cero. */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Devuelve el monto como {@code long}.
     *
     * @throws ArithmeticException si el monto excede el rango de {@code long} (irreal en CLP)
     */
    public long toLong() {
        return amount.longValueExact();
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
