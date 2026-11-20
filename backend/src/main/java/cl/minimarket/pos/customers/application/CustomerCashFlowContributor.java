package cl.minimarket.pos.customers.application;

import cl.minimarket.pos.cash.CashFlowContributor;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.customers.domain.CustomerJdbcRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aporta al módulo {@code cash} los abonos en efectivo recibidos durante un turno de caja
 * ({@code PLAN.md} A.4, HU-POS-17).
 */
@Component
public class CustomerCashFlowContributor implements CashFlowContributor {

    private final CustomerJdbcRepository jdbcRepository;

    public CustomerCashFlowContributor(CustomerJdbcRepository jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PaymentMethod, BigDecimal> netAmountsBySession(Long cashSessionId) {
        if (cashSessionId == null) {
            return Map.of();
        }
        BigDecimal cashPayments = jdbcRepository.sumCashPaymentsBySession(cashSessionId);
        if (cashPayments.compareTo(BigDecimal.ZERO) == 0) {
            return Map.of();
        }
        return Map.of(PaymentMethod.CASH, cashPayments);
    }
}
