package cl.minimarket.pos.customers.web;

import cl.minimarket.pos.customers.domain.Customer;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository.CustomerRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Respuesta pública de cliente ({@code API.md} F.3).
 */
@Schema(name = "CustomerResponse", description = "Datos y estado crediticio de un cliente.")
public record CustomerResponse(
        Long id,
        String rut,
        String name,
        String email,
        String phone,
        String address,
        boolean creditEnabled,
        BigDecimal creditLimit,
        BigDecimal creditBalance,
        BigDecimal availableCredit,
        boolean active,
        Long version
) {

    /** Convierte una entidad {@link Customer} en DTO de respuesta. */
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getRut(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.isCreditEnabled(),
                customer.getCreditLimit(),
                customer.getCreditBalance(),
                customer.availableCredit(),
                customer.isActive(),
                customer.getVersion()
        );
    }

    /** Convierte una proyección de listado {@link CustomerRow} en DTO de respuesta. */
    public static CustomerResponse from(CustomerRow row) {
        return new CustomerResponse(
                row.id(),
                row.rut(),
                row.name(),
                row.email(),
                row.phone(),
                row.address(),
                row.creditEnabled(),
                row.creditLimit(),
                row.creditBalance(),
                row.availableCredit(),
                row.active(),
                row.version()
        );
    }
}
