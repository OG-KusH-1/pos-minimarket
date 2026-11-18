package cl.minimarket.pos.shared.money;

/**
 * Redondeo del efectivo a la decena (Ley 20.956), según A.5 del plan.
 *
 * <p>Reglas:
 * <ul>
 *   <li>Solo aplica a la porción pagada en efectivo. Si {@code x} es el efectivo adeudado y
 *       {@code d = x mod 10}: si {@code d ≤ 5} se resta {@code d}; si no se suma {@code 10 − d}.
 *       Por eso el ajuste está siempre en [−5, +4].</li>
 *   <li>Si no hay efectivo por cobrar ({@code x ≤ 0}) o la configuración
 *       {@link #SETTING_KEY} es {@code false}, el ajuste es 0 y el efectivo se cobra exacto.</li>
 * </ul>
 *
 * <p>Esta clase no lee configuración: el llamador (sales) resuelve
 * {@code SettingsApi.get("pos.cash_rounding.enabled", ...)} y pasa el booleano, para mantener
 * {@code shared} sin dependencias hacia otros módulos.
 *
 * <p>Ejemplos (HU-POS-08): 1.995 → −5 (se cobra 1.990); 1.996 → +4 (se cobra 2.000).
 */
public final class CashRounding {

    /** Clave de configuración que activa o desactiva el redondeo (global o por local). */
    public static final String SETTING_KEY = "pos.cash_rounding.enabled";

    private static final long TEN = 10L;
    private static final long HALF = 5L;

    private CashRounding() {
    }

    /**
     * Calcula el ajuste de redondeo de la porción en efectivo.
     *
     * @param cashDue efectivo adeudado (antes del redondeo)
     * @param enabled valor de {@link #SETTING_KEY}
     * @return ajuste en [−5, +4]; 0 si está desactivado o no hay efectivo por cobrar
     */
    public static Money adjustment(Money cashDue, boolean enabled) {
        if (!enabled || cashDue.signum() <= 0) {
            return Money.ZERO;
        }
        long digit = Math.floorMod(cashDue.toLong(), TEN);
        return Money.of(digit <= HALF ? -digit : TEN - digit);
    }

    /**
     * Efectivo a cobrar ya redondeado: {@code cashDue + adjustment(cashDue)}.
     *
     * @param cashDue efectivo adeudado
     * @param enabled valor de {@link #SETTING_KEY}
     * @return monto en efectivo redondeado a la decena (o el mismo si no aplica)
     */
    public static Money round(Money cashDue, boolean enabled) {
        return cashDue.plus(adjustment(cashDue, enabled));
    }

    /**
     * Ajuste para un pago mixto: el redondeo recae solo sobre lo que queda por pagar en efectivo,
     * {@code cashDue = totalPayable − nonCashPortion}; la porción con tarjeta, crédito u otros
     * medios nunca se redondea.
     *
     * @param totalPayable total de la venta antes del redondeo
     * @param nonCashPortion suma de lo pagado con medios distintos del efectivo
     * @param enabled valor de {@link #SETTING_KEY}
     * @return ajuste en [−5, +4]; 0 si el efectivo adeudado no es positivo o está desactivado
     */
    public static Money adjustmentForMixedPayment(Money totalPayable, Money nonCashPortion, boolean enabled) {
        return adjustment(totalPayable.minus(nonCashPortion), enabled);
    }
}
