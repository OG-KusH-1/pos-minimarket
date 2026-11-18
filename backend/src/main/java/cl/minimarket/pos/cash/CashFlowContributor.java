package cl.minimarket.pos.cash;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Puerto de inversión de dependencia definido por {@code cash} ({@code PLAN.md} A.4).
 *
 * <p>Otros módulos ({@code sales}, {@code customers}) lo implementan para aportar sus montos netos
 * por medio de pago asociados a un turno de caja sin generar dependencias cíclicas hacia {@code cash}.
 */
@FunctionalInterface
public interface CashFlowContributor {

    /**
     * Calcula los montos netos aportados al turno por medio de pago.
     *
     * <p>Para {@link PaymentMethod#CASH}, el valor corresponde al neto en efectivo (ventas cobradas
     * en efectivo ya netas de vuelto y redondeo, menos reembolsos en efectivo, más abonos en
     * efectivo).
     *
     * @param cashSessionId identificador del turno de caja
     * @return mapa de monto neto por {@link PaymentMethod} (puede estar vacío; los montos pueden ser
     *         negativos si hubo más reembolsos que cobros)
     */
    Map<PaymentMethod, BigDecimal> netAmountsBySession(Long cashSessionId);
}
