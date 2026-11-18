package cl.minimarket.pos.sales.payment;

/**
 * Resultado devuelto por {@link PaymentGateway#refund(RefundRequest)}.
 *
 * @param success {@code true} si la reversa o reembolso fue aceptado por la pasarela
 * @param gatewayReference referencia de la operación de reversa en la pasarela
 * @param errorCode código de error si falló
 * @param message mensaje descriptivo del resultado
 */
public record RefundResult(
        boolean success,
        String gatewayReference,
        String errorCode,
        String message
) {
}
