package cl.minimarket.pos.notification.domain;

/**
 * Estado de procesamiento de un correo en la outbox ({@code outbound_emails.status}).
 */
public enum OutboundEmailStatus {
    /** Pendiente de envío o en espera de reintento. */
    PENDING,
    /** Enviado exitosamente por el gateway SMTP. */
    SENT,
    /** Agotó el máximo de reintentos sin éxito. */
    FAILED
}
