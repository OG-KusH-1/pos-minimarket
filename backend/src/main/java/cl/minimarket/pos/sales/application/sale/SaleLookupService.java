package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.sales.SaleLookupApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementación de {@link SaleLookupApi} para consultas inter-módulo de ventas (p. ej. alcance P
 * en {@code billing}).
 */
@Service
public class SaleLookupService implements SaleLookupApi {

    private final SaleJdbcRepository jdbcRepository;

    public SaleLookupService(SaleJdbcRepository jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> cashierOf(Long saleId) {
        if (saleId == null) {
            return Optional.empty();
        }
        return jdbcRepository.findCashierIdBySaleId(saleId);
    }
}
