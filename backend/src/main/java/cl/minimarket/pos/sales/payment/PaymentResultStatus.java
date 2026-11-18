package cl.minimarket.pos.sales.payment;

/**
 * Resultado devuelto por {@link PaymentGateway#authorize(PaymentRequest)} ({@code PLAN.md} A.4).
 */
public enum PaymentResultStatus {
    /** Transacción aprobada por la pasarela. */
    APPROVED,
    /** Transacción rechazada por el emisor o reglas del medio de pago. */
    DECLINED,
    /** Error de comunicación o fallo técnico de la pasarela. */
    ERROR
}
