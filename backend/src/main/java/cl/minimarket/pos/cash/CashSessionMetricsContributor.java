package cl.minimarket.pos.cash;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Puerto SPI definido por {@code cash} para recolectar métricas operativas del turno sin acoplarse
 * a {@code sales} ni {@code customers}.
 */
public interface CashSessionMetricsContributor {

    /**
     * Aporta los conteos y montos por medio de pago asociados a un turno de caja.
     *
     * @param cashSessionId identificador del turno
     * @return snapshot con conteos y mapas por medio de pago (pueden ser vacíos o nulos)
     */
    SessionMetricsSnapshot contribute(Long cashSessionId);

    /**
     * Instantánea de métricas operativas aportadas por un módulo externo para un turno.
     *
     * @param salesCount cantidad de ventas del turno
     * @param voidCount cantidad de anulaciones del turno
     * @param returnCount cantidad de devoluciones del turno
     * @param salesByMethod total vendido por medio de pago
     * @param refundsByMethod total reembolsado por medio de pago
     * @param creditPaymentsByMethod total de abonos a cuenta corriente por medio de pago
     */
    record SessionMetricsSnapshot(
            int salesCount,
            int voidCount,
            int returnCount,
            Map<PaymentMethod, BigDecimal> salesByMethod,
            Map<PaymentMethod, BigDecimal> refundsByMethod,
            Map<PaymentMethod, BigDecimal> creditPaymentsByMethod
    ) {
    }
}
