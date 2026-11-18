package cl.minimarket.pos.shared.money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Cantidad de producto (unidades o kilos): value object inmutable de escala 3, HALF_UP (A.5).
 *
 * <p>Que un producto se venda solo en unidades enteras se valida en el servicio de negocio (A.5:
 * "unidades enteras validadas en servicio"); aquí se ofrece {@link #isWholeNumber()} para ello.
 *
 * @param value cantidad con escala 3 (se redondea HALF_UP si llega con más decimales; no nula)
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    /** Cantidad cero. */
    public static final Quantity ZERO = new Quantity(BigDecimal.ZERO);

    /**
     * Normaliza la cantidad a escala 3 con HALF_UP.
     *
     * @throws NullPointerException si {@code value} es nulo
     */
    public Quantity {
        value = Rounding.quantity(Objects.requireNonNull(value, "value"));
    }

    /** Crea una cantidad desde un entero (unidades). */
    public static Quantity of(long units) {
        return new Quantity(BigDecimal.valueOf(units));
    }

    /** Crea una cantidad desde un decimal, normalizando a escala 3. */
    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    /**
     * Crea una cantidad desde texto decimal (p. ej. {@code "0.750"}).
     *
     * @throws NumberFormatException si el texto no es un decimal válido
     */
    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    /** Suma exacta. */
    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    /** Resta exacta. */
    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.value));
    }

    /** Signo de la cantidad: -1, 0 o 1. */
    public int signum() {
        return value.signum();
    }

    /** Indica si la cantidad no tiene parte decimal (p. ej. {@code 3.000}). */
    public boolean isWholeNumber() {
        return value.remainder(BigDecimal.ONE).signum() == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
