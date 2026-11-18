package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.cash.CashFlowContributor;
import cl.minimarket.pos.cash.CashSessionMetricsContributor;
import cl.minimarket.pos.cash.PaymentMethod;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aporta al módulo {@code cash} los montos netos y las métricas de ventas y reembolsos por medio de
 * pago de un turno de caja ({@code PLAN.md} A.4).
 */
@Component
public class SaleCashFlowContributor implements CashFlowContributor, CashSessionMetricsContributor {

    private final SaleJdbcRepository jdbcRepository;

    public SaleCashFlowContributor(SaleJdbcRepository jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PaymentMethod, BigDecimal> netAmountsBySession(Long cashSessionId) {
        if (cashSessionId == null) {
            return Map.of();
        }
        return jdbcRepository.netCashFlowBySession(cashSessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionMetricsSnapshot contribute(Long cashSessionId) {
        if (cashSessionId == null) {
            return new SessionMetricsSnapshot(0, 0, 0, Map.of(), Map.of(), Map.of());
        }
        return jdbcRepository.sessionMetricsBySession(cashSessionId);
    }
}
