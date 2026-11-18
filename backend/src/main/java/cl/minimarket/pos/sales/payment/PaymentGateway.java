package cl.minimarket.pos.sales.payment;

/**
 * Puerto externo para la integración con pasarelas de pago con tarjeta ({@code PLAN.md} A.4).
 *
 * <p>En el MVP se implementa mediante {@code SimulatedPaymentGateway}; en producción puede
 * reemplazarse por un adaptador real (p. ej. Transbank POSIntegrado) sin afectar los casos de uso
 * de ventas y devoluciones.
 */
public interface PaymentGateway {

    /**
     * Solicita la autorización de un cobro con tarjeta.
     *
     * @param request datos de la transacción (sin PAN completo)
     * @return resultado de la autorización ({@code APPROVED}, {@code DECLINED} o {@code ERROR})
     */
    PaymentResult authorize(PaymentRequest request);

    /**
     * Solicita la anulación o reembolso total/parcial de una transacción previamente autorizada.
     *
     * @param request referencia de pasarela y monto a reversar
     * @return resultado de la reversa
     */
    RefundResult refund(RefundRequest request);

    /**
     * @return código estable del proveedor implementado (p. ej. {@code SIMULATED})
     */
    String providerCode();
}
