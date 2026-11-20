package cl.minimarket.pos.customers;

import java.math.BigDecimal;

/**
 * API pública del módulo {@code customers} para operar la cuenta corriente (fiado) desde ventas y
 * devoluciones ({@code PLAN.md} A.4, {@code MODELO_DATOS.md} B.7, RN-15).
 */
public interface CustomerCreditApi {

    /**
     * Consulta el estado crediticio vigente de un cliente.
     *
     * @param customerId identificador del cliente
     * @return resumen del cupo y deuda vigente
     * @throws cl.minimarket.pos.shared.error.BusinessException 404 {@code NOT_FOUND} si no existe
     */
    CustomerCreditSnapshot creditStatus(Long customerId);

    /**
     * Registra un cargo a la cuenta corriente del cliente por una venta a fiado sin autorización
     * sobre el cupo.
     *
     * @param customerId identificador del cliente
     * @param amount monto del cargo en CLP ({@code > 0})
     * @param saleId identificador de la venta asociada
     * @param storeId identificador del local donde ocurre la venta
     * @throws cl.minimarket.pos.shared.error.BusinessException 404 {@code NOT_FOUND} si el cliente no existe;
     *         409 {@code INVALID_STATE_TRANSITION} si el cliente está inactivo;
     *         422 {@code CREDIT_NOT_ENABLED} si el cliente no tiene crédito habilitado;
     *         422 {@code CREDIT_LIMIT_EXCEEDED} si {@code creditBalance + amount > creditLimit} (RN-15)
     */
    void charge(Long customerId, BigDecimal amount, Long saleId, Long storeId);

    /**
     * Registra un cargo a la cuenta corriente del cliente por una venta a fiado, permitiendo
     * exceder el cupo cuando existe una autorización de supervisor ({@code CREDIT_OVER_LIMIT}).
     *
     * @param customerId identificador del cliente
     * @param amount monto del cargo en CLP ({@code > 0})
     * @param saleId identificador de la venta asociada
     * @param storeId identificador del local donde ocurre la venta
     * @param authorizationId id de {@code supervisor_authorizations} si medió override {@code CREDIT_OVER_LIMIT},
     *        o {@code null} si debe respetarse el cupo
     */
    void charge(Long customerId, BigDecimal amount, Long saleId, Long storeId, Long authorizationId);

    /**
     * Reversa deuda en la cuenta corriente del cliente producto de una devolución de venta.
     * Toma el local desde {@code StoreContext.require()}.
     *
     * @param customerId identificador del cliente
     * @param amount monto a descontar de la deuda en CLP ({@code > 0})
     * @param saleReturnId identificador de la devolución asociada
     */
    void reverse(Long customerId, BigDecimal amount, Long saleReturnId);

    /**
     * Reversa deuda en la cuenta corriente del cliente producto de una anulación o devolución de
     * venta especificando el local explícitamente.
     *
     * @param customerId identificador del cliente
     * @param amount monto a descontar de la deuda en CLP ({@code > 0})
     * @param saleId identificador de la venta anulada (opcional si {@code saleReturnId} está informado)
     * @param saleReturnId identificador de la devolución (opcional si {@code saleId} está informado)
     * @param storeId identificador del local donde ocurre la operación
     */
    void reverse(Long customerId, BigDecimal amount, Long saleId, Long saleReturnId, Long storeId);
}
