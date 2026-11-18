package cl.minimarket.pos.sales.payment.web;

import cl.minimarket.pos.sales.payment.CardAuthorizationStatus;
import cl.minimarket.pos.sales.payment.domain.CardAuthorization;

/**
 * Respuesta pública de una autorización de tarjeta ({@code API.md} F.3).
 *
 * @param id identificador interno de la autorización
 * @param status estado resultante ({@code APPROVED}, {@code DECLINED}, {@code ERROR}, {@code CAPTURED} o {@code VOIDED})
 * @param gatewayReference referencia de la pasarela de pagos
 * @param authorizationCode código de autorización del emisor
 * @param cardBrand marca de la tarjeta
 * @param last4 últimos 4 dígitos de la tarjeta (nunca el PAN completo)
 * @param message mensaje informativo de la pasarela
 */
public record CardAuthorizationResponse(
        Long id,
        CardAuthorizationStatus status,
        String gatewayReference,
        String authorizationCode,
        String cardBrand,
        String last4,
        String message
) {
    /** Construye el DTO de respuesta a partir de la entidad persistida. */
    public static CardAuthorizationResponse from(CardAuthorization auth) {
        return new CardAuthorizationResponse(
                auth.getId(),
                auth.getStatus(),
                auth.getGatewayReference(),
                auth.getAuthorizationCode(),
                auth.getCardBrand(),
                auth.getCardLast4(),
                auth.getMessage()
        );
    }
}
