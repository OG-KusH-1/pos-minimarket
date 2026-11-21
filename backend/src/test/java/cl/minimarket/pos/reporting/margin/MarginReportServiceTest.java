package cl.minimarket.pos.reporting.margin;

import cl.minimarket.pos.reporting.margin.application.MarginReportService;
import cl.minimarket.pos.reporting.margin.infrastructure.MarginReportRepository;
import cl.minimarket.pos.reporting.margin.infrastructure.MarginReportRepository.MarginAggregateRow;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link MarginGroupBy} y {@link MarginReportService}.
 */
@ExtendWith(MockitoExtension.class)
class MarginReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final BusinessClock CLOCK = BusinessClock.fixed(NOW, ZoneId.of("America/Santiago"));

    @Mock
    private MarginReportRepository repository;

    @Test
    void groupByFromParamParsesAndDefaultsCorrectly() {
        assertThat(MarginGroupBy.fromParam(null)).isEqualTo(MarginGroupBy.PRODUCT);
        assertThat(MarginGroupBy.fromParam("   ")).isEqualTo(MarginGroupBy.PRODUCT);
        assertThat(MarginGroupBy.fromParam("PRODUCT").paramValue()).isEqualTo("product");
        assertThat(MarginGroupBy.fromParam(" Category ").paramValue()).isEqualTo("category");
        assertThat(MarginGroupBy.fromParam("brand")).isEqualTo(MarginGroupBy.BRAND);
        assertThat(MarginGroupBy.fromParam("DAY")).isEqualTo(MarginGroupBy.DAY);

        assertThatThrownBy(() -> MarginGroupBy.fromParam("supplier"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getErrors()).extracting("field").containsExactly("groupBy");
                });
    }

    @Test
    void generateComputesMarginHandlesZeroNetSalesAndFallsBackToStoreContext() {
        MarginReportService service = new MarginReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 10);

        when(repository.findMarginRows(from, to, List.of(3L), MarginGroupBy.PRODUCT))
                .thenReturn(List.of(
                        new MarginAggregateRow("10", "Producto A",
                                new BigDecimal("2.000"), new BigDecimal("1000"), new BigDecimal("600.00")),
                        new MarginAggregateRow("11", "Producto B",
                                new BigDecimal("1.000"), BigDecimal.ZERO, new BigDecimal("50.00"))));

        ReportResponse<MarginRow> response;
        try (var ignored = StoreContext.bind(new StoreContext(3L, null))) {
            response = service.generate(new ReportFilter(from, to, List.of()), null);
        }

        verify(repository).findMarginRows(from, to, List.of(3L), MarginGroupBy.PRODUCT);
        assertThat(response.generatedAt()).isEqualTo(NOW);
        assertThat(response.filters().storeIds()).containsExactly(3L);
        assertThat(response.rows()).hasSize(2);

        MarginRow rowA = response.rows().get(0);
        assertThat(rowA.grossMargin()).isEqualByComparingTo("400.00");
        assertThat(rowA.marginPct()).isEqualByComparingTo("40.00");

        MarginRow rowB = response.rows().get(1);
        assertThat(rowB.grossMargin()).isEqualByComparingTo("-50.00");
        assertThat(rowB.marginPct()).isEqualByComparingTo("0.00");

        assertThat(response.totals().key()).isEqualTo("TOTAL");
        assertThat(response.totals().qty()).isEqualByComparingTo("3.000");
        assertThat(response.totals().netSales()).isEqualByComparingTo("1000");
        assertThat(response.totals().cost()).isEqualByComparingTo("650.00");
        assertThat(response.totals().grossMargin()).isEqualByComparingTo("350.00");
        assertThat(response.totals().marginPct()).isEqualByComparingTo("35.00");
    }

    @Test
    void generateRequiresDateRange() {
        MarginReportService service = new MarginReportService(repository, CLOCK);
        ReportFilter missingDates = new ReportFilter(null, null, List.of(1L));

        assertThatThrownBy(() -> service.generate(missingDates, MarginGroupBy.DAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
