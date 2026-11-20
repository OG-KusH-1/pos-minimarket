package cl.minimarket.pos.customers.web;

import cl.minimarket.pos.customers.application.CustomerService.AccountStatement;
import cl.minimarket.pos.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Estado de cuenta corriente de un cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerAccountStatementResponse", description = "Estado de cuenta y cartola paginada de movimientos.")
public record AccountStatementResponse(
        CustomerResponse customer,
        PageResponse<AccountMovementResponse> movements
) {

    /** Convierte el resultado de aplicación {@link AccountStatement} en DTO de respuesta. */
    public static AccountStatementResponse from(AccountStatement statement) {
        return new AccountStatementResponse(
                CustomerResponse.from(statement.customer()),
                PageResponse.from(statement.movements(), AccountMovementResponse::from)
        );
    }
}
