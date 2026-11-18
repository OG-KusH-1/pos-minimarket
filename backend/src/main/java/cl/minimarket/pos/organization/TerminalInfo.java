package cl.minimarket.pos.organization;

import java.time.Instant;

/**
 * Vista pública de una terminal POS.
 */
public record TerminalInfo(
        Long id,
        Long storeId,
        String code,
        String name,
        boolean active,
        Instant lastSeenAt
) {
}
