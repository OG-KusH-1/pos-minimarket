package cl.minimarket.pos.customers.web;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository.AccountMovementRow;
import cl.minimarket.pos.customers.domain.CustomerMovementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Movimiento de cuenta corriente de cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerAccountMovementResponse", description = "Movimiento de la cartola de cuenta corriente.")
public record AccountMovementResponse(
        Long id,
        Instant occurredAt,
        CustomerMovementType movementType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String saleNumber,
        PaymentMethod paymentMethod,
        String storeName,
        String username,
        String notes
) {

    /** Convierte una proyección {@link AccountMovementRow} en DTO de respuesta. */
    public static AccountMovementResponse from(AccountMovementRow row) {
        return new AccountMovementResponse(
                row.id(),
                row.occurredAt(),
                row.movementType(),
                row.amount(),
                row.balanceAfter(),
                row.saleNumber(),
                row.paymentMethod(),
                row.storeName(),
                row.username(),
                row.notes()
        );
    }
}
