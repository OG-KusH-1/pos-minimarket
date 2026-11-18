package cl.minimarket.pos.sales.payment;

/**
 * Medios de pago con tarjeta admitidos en las autorizaciones de pasarela
 * ({@code MODELO_DATOS.md} B.9 {@code card_authorizations}, {@code PLAN.md} J-02).
 */
public enum CardPaymentMethod {
    /** Tarjeta de débito. */
    DEBIT,
    /** Tarjeta de crédito. */
    CREDIT
}
