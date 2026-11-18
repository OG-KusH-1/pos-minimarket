package cl.minimarket.pos.shared.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Utilidades de redondeo por tipo de dato, según la tabla "Dinero y redondeo" de A.5 del plan.
 *
 * <p>Todos los redondeos son {@link RoundingMode#HALF_UP}. Los cálculos intermedios se hacen con
 * precisión exacta (o {@link #INTERMEDIATE}) y se redondea <b>solo</b> al persistir o presentar,
 * aplicando la escala del dato correspondiente:
 *
 * <ul>
 *   <li>{@link #clp}: montos CLP (precios, totales, impuestos, pagos), escala 0.</li>
 *   <li>{@link #unitCost}: costo unitario (avg, último, por línea), escala 4.</li>
 *   <li>{@link #costTotal}: totales de costo (línea, valorización, merma), escala 2.</li>
 *   <li>{@link #quantity}: cantidades, escala 3.</li>
 *   <li>{@link #percentage}: tasas de impuesto y porcentajes (en %, p. ej. 19.0000), escala 4.</li>
 * </ul>
 *
 * <p>Punto de extensión: si aparece un nuevo tipo de dato con escala propia, agregar aquí su
 * método y su constante de escala en lugar de llamar a {@code setScale} en el código de negocio.
 */
public final class Rounding {

    /** Precisión para los intermedios de cálculo que no pueden ser exactos (p. ej. divisiones). */
    public static final MathContext INTERMEDIATE = MathContext.DECIMAL64;

    /** Escala de los montos CLP. */
    public static final int CLP_SCALE = 0;
    /** Escala del costo unitario. */
    public static final int UNIT_COST_SCALE = 4;
    /** Escala de los totales de costo. */
    public static final int COST_TOTAL_SCALE = 2;
    /** Escala de las cantidades. */
    public static final int QUANTITY_SCALE = 3;
    /** Escala de las tasas y porcentajes. */
    public static final int PERCENTAGE_SCALE = 4;

    private Rounding() {
    }

    /** Redondea un monto CLP a escala 0, HALF_UP. */
    public static BigDecimal clp(BigDecimal value) {
        return round(value, CLP_SCALE);
    }

    /** Redondea un costo unitario a escala 4, HALF_UP. */
    public static BigDecimal unitCost(BigDecimal value) {
        return round(value, UNIT_COST_SCALE);
    }

    /** Redondea un total de costo a escala 2, HALF_UP. */
    public static BigDecimal costTotal(BigDecimal value) {
        return round(value, COST_TOTAL_SCALE);
    }

    /** Redondea una cantidad a escala 3, HALF_UP. */
    public static BigDecimal quantity(BigDecimal value) {
        return round(value, QUANTITY_SCALE);
    }

    /** Normaliza una tasa o porcentaje a escala 4 (p. ej. {@code 19} pasa a {@code 19.0000}). */
    public static BigDecimal percentage(BigDecimal value) {
        return round(value, PERCENTAGE_SCALE);
    }

    private static BigDecimal round(BigDecimal value, int scale) {
        Objects.requireNonNull(value, "value");
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
