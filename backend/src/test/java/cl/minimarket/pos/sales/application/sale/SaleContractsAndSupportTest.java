package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.cash.CashSessionMetricsContributor;
import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de los contratos públicos del módulo {@code sales} y sus adaptadores de
 * consulta e integración con {@code cash}.
 */
@ExtendWith(MockitoExtension.class)
class SaleContractsAndSupportTest {

    @Mock
    SaleJdbcRepository jdbcRepository;

    @Test
    void saleCompletedEventCopiesLinesDefensively() {
        List<SaleCompletedEvent.Line> mutable = new ArrayList<>();
        mutable.add(new SaleCompletedEvent.Line(
                1, 10L, "SKU-1", "Producto", BigDecimal.ONE,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
                BigDecimal.valueOf(840), BigDecimal.valueOf(160), BigDecimal.ZERO
        ));

        SaleCompletedEvent event = new SaleCompletedEvent(
                1L, "V-001-000001", 1L, 2L, 3L, 4L, null,
                Instant.now(), LocalDate.now(),
                BigDecimal.valueOf(1000), BigDecimal.ZERO, BigDecimal.valueOf(1000),
                BigDecimal.valueOf(840), BigDecimal.valueOf(160), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(1000), mutable
        );

        mutable.clear();
        assertThat(event.lines()).hasSize(1);
        assertThatThrownBy(() -> event.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void saleReturnedEventCopiesLinesDefensively() {
        List<SaleReturnedEvent.Line> mutable = new ArrayList<>();
        mutable.add(new SaleReturnedEvent.Line(
                100L, 10L, "SKU-1", "Producto", BigDecimal.ONE, BigDecimal.valueOf(1000)
        ));

        SaleReturnedEvent event = new SaleReturnedEvent(
                5L, "D-001-000001", 1L, "V-001-000001", "RETURN",
                1L, 2L, 3L, 4L, Instant.now(), LocalDate.now(),
                BigDecimal.valueOf(1000), mutable
        );

        mutable.clear();
        assertThat(event.lines()).hasSize(1);
        assertThatThrownBy(() -> event.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lookupServiceAndCashFlowContributorHandleNullIdsGracefully() {
        SaleLookupService lookupService = new SaleLookupService(jdbcRepository);
        SaleCashFlowContributor contributor = new SaleCashFlowContributor(jdbcRepository);

        assertThat(lookupService.cashierOf(null)).isEmpty();
        assertThat(contributor.netAmountsBySession(null)).isEmpty();
        assertThat(contributor.contribute(null))
                .isEqualTo(new CashSessionMetricsContributor.SessionMetricsSnapshot(
                        0, 0, 0, Map.of(), Map.of(), Map.of()
                ));
        verifyNoInteractions(jdbcRepository);

        when(jdbcRepository.findCashierIdBySaleId(42L)).thenReturn(Optional.of(7L));
        assertThat(lookupService.cashierOf(42L)).contains(7L);

        CashSessionMetricsContributor.SessionMetricsSnapshot expected =
                new CashSessionMetricsContributor.SessionMetricsSnapshot(
                        3, 1, 0,
                        Map.of(PaymentMethod.CASH, BigDecimal.valueOf(5000)),
                        Map.of(PaymentMethod.CASH, BigDecimal.valueOf(1000)),
                        Map.of()
                );
        when(jdbcRepository.sessionMetricsBySession(99L)).thenReturn(expected);
        assertThat(contributor.contribute(99L)).isEqualTo(expected);
    }
}
