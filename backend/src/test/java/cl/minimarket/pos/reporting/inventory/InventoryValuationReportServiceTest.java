package cl.minimarket.pos.reporting.inventory;

import cl.minimarket.pos.reporting.inventory.application.InventoryValuationReportService;
import cl.minimarket.pos.reporting.inventory.infrastructure.InventoryValuationReportRepository;
import cl.minimarket.pos.reporting.inventory.infrastructure.InventoryValuationReportRepository.ValuationAggregateRow;
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
 * Pruebas unitarias de {@link InventoryValuationGroupBy} y {@link InventoryValuationReportService}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryValuationReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final BusinessClock CLOCK = BusinessClock.fixed(NOW, ZoneId.of("America/Santiago"));

    @Mock
    private InventoryValuationReportRepository repository;

    @Test
    void groupByFromParamParsesAndDefaultsToCategory() {
        assertThat(InventoryValuationGroupBy.fromParam(null)).isEqualTo(InventoryValuationGroupBy.CATEGORY);
        assertThat(InventoryValuationGroupBy.fromParam(" ")).isEqualTo(InventoryValuationGroupBy.CATEGORY);
        assertThat(InventoryValuationGroupBy.fromParam("category").paramValue()).isEqualTo("category");
        assertThat(InventoryValuationGroupBy.fromParam("STORE")).isEqualTo(InventoryValuationGroupBy.STORE);
        assertThat(InventoryValuationGroupBy.fromParam("Product")).isEqualTo(InventoryValuationGroupBy.PRODUCT);

        assertThatThrownBy(() -> InventoryValuationGroupBy.fromParam("brand"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void generateAllowsOmittedRangeAndComputesPercentages() {
        InventoryValuationReportService service = new InventoryValuationReportService(repository, CLOCK);

        when(repository.findValuationRows(List.of(4L), InventoryValuationGroupBy.CATEGORY))
                .thenReturn(List.of(
                        new ValuationAggregateRow("1", "Lácteos",
                                new BigDecimal("10.000"), new BigDecimal("7500.00")),
                        new ValuationAggregateRow("2", "Panadería",
                                new BigDecimal("5.000"), new BigDecimal("2500.00"))));

        ReportResponse<InventoryValuationRow> response;
        try (var ignored = StoreContext.bind(new StoreContext(4L, null))) {
            response = service.generate(new ReportFilter(null, null, List.of()), null);
        }

        verify(repository).findValuationRows(List.of(4L), InventoryValuationGroupBy.CATEGORY);
        assertThat(response.filters().from()).isNull();
        assertThat(response.filters().to()).isNull();
        assertThat(response.rows()).hasSize(2);
        assertThat(response.rows().get(0).pct()).isEqualByComparingTo("75.00");
        assertThat(response.rows().get(1).pct()).isEqualByComparingTo("25.00");
        assertThat(response.totals().valuation()).isEqualByComparingTo("10000.00");
        assertThat(response.totals().pct()).isEqualByComparingTo("100.00");
    }

    @Test
    void generateWithEmptyStockReturnsZeroTotalPercentageAndRejectsHalfRange() {
        InventoryValuationReportService service = new InventoryValuationReportService(repository, CLOCK);

        when(repository.findValuationRows(List.of(1L), InventoryValuationGroupBy.STORE))
                .thenReturn(List.of());

        ReportResponse<InventoryValuationRow> emptyResponse = service.generate(
                new ReportFilter(null, null, List.of(1L)), InventoryValuationGroupBy.STORE);

        assertThat(emptyResponse.rows()).isEmpty();
        assertThat(emptyResponse.totals().valuation()).isEqualByComparingTo("0.00");
        assertThat(emptyResponse.totals().pct()).isEqualByComparingTo("0.00");

        ReportFilter halfRange = new ReportFilter(LocalDate.of(2026, 11, 1), null, List.of(1L));
        assertThatThrownBy(() -> service.generate(halfRange, InventoryValuationGroupBy.STORE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
