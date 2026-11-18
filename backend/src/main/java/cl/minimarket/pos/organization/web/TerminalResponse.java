package cl.minimarket.pos.organization.web;

import cl.minimarket.pos.organization.domain.Terminal;

import java.time.Instant;

/**
 * DTO de respuesta de una terminal POS ({@code API.md} F.3 {@code TerminalResponse}).
 */
public record TerminalResponse(
        Long id,
        Long storeId,
        String code,
        String name,
        boolean active,
        Instant lastSeenAt,
        boolean hasOpenSession
) {

    /** Construye el DTO desde la entidad persistida. */
    public static TerminalResponse from(Terminal terminal) {
        return new TerminalResponse(
                terminal.getId(),
                terminal.getStoreId(),
                terminal.getCode(),
                terminal.getName(),
                terminal.isActive(),
                terminal.getLastSeenAt(),
                false
        );
    }
}
