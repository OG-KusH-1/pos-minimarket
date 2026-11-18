package cl.minimarket.pos.sales.payment;

/**
 * Estados posibles de una autorización de tarjeta en {@code card_authorizations}
 * ({@code PLAN.md} J-02).
 */
public enum CardAuthorizationStatus {
    /** Autorización aprobada por la pasarela y aún disponible para asociarse a una venta. */
    APPROVED,
    /** Transacción rechazada por el emisor o pasarela. */
    DECLINED,
    /** Error técnico o de comunicación con la pasarela. */
    ERROR,
    /** Autorización ya asociada y capturada por una venta confirmada. */
    CAPTURED,
    /** Autorización aprobada pero anulada antes de asociarse a una venta. */
    VOIDED
}
