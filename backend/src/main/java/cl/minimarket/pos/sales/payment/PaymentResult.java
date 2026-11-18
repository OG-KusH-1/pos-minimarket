package cl.minimarket.pos.sales.payment;

import java.util.Objects;

/**
 * Respuesta devuelta por {@link PaymentGateway#authorize(PaymentRequest)} ({@code PLAN.md} A.4).
 *
 * <p>Solo expone los últimos 4 dígitos de la tarjeta ({@code last4}); nunca el PAN completo.
 *
 * @param status resultado de la autorización ({@code APPROVED}, {@code DECLINED} o {@code ERROR})
 * @param gatewayReference referencia asignada por la pasarela (puede ser nula si hubo error)
 * @param authorizationCode código de autorización del emisor (presente si fue aprobada)
 * @param cardBrand marca de la tarjeta (p. ej. {@code VISA}, {@code MASTERCARD}, {@code REDCOMPRA})
 * @param last4 últimos 4 dígitos de la tarjeta (nunca el PAN completo)
 * @param errorCode código de error de la pasarela cuando no fue aprobada
 * @param message mensaje legible de la pasarela para el operador POS
 */
public record PaymentResult(
        PaymentResultStatus status,
        String gatewayReference,
        String authorizationCode,
        String cardBrand,
        String last4,
        String errorCode,
        String message
) {
    /** Valida que el estado no sea nulo y que {@code last4}, si viene, tenga como máximo 4 caracteres. */
    public PaymentResult {
        Objects.requireNonNull(status, "status");
        if (last4 != null && last4.length() > 4) {
            throw new IllegalArgumentException("last4 no puede superar los 4 caracteres");
        }
    }
}
