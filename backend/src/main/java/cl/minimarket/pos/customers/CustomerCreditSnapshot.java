package cl.minimarket.pos.customers;

import java.math.BigDecimal;

/**
 * Estado resumido del crédito de un cliente expuesto por {@link CustomerCreditApi}.
 *
 * @param id identificador del cliente
 * @param name nombre completo o razón social
 * @param active indica si el cliente está activo
 * @param creditEnabled indica si tiene habilitada la cuenta corriente (fiado)
 * @param creditLimit cupo máximo de crédito autorizado en CLP
 * @param creditBalance deuda vigente en CLP
 * @param availableCredit cupo disponible en CLP ({@code max(0, creditLimit - creditBalance)})
 */
public record CustomerCreditSnapshot(
        Long id,
        String name,
        boolean active,
        boolean creditEnabled,
        BigDecimal creditLimit,
        BigDecimal creditBalance,
        BigDecimal availableCredit
) {
}
