package cl.minimarket.pos.reporting.shrinkage;

import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.reporting.shrinkage.application.ShrinkageReportService;
import cl.minimarket.pos.reporting.shrinkage.infrastructure.ShrinkageReportRepository;
import cl.minimarket.pos.reporting.shrinkage.infrastructure.ShrinkageReportRepository.ShrinkageAggregateRow;
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
 * Pruebas unitarias de {@link ShrinkageGroupBy} y {@link ShrinkageReportService}.
 */
@ExtendWith(MockitoExtension.class)
class ShrinkageReportServiceTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final BusinessClock CLOCK = BusinessClock.fixed(NOW, SANTIAGO);

    @Mock
    private ShrinkageReportRepository repository;

    @Test
    void groupByFromParamParsesAndDefaultsToType() {
        assertThat(ShrinkageGroupBy.fromParam(null)).isEqualTo(ShrinkageGroupBy.TYPE);
        assertThat(ShrinkageGroupBy.fromParam("")).isEqualTo(ShrinkageGroupBy.TYPE);
        assertThat(ShrinkageGroupBy.fromParam("type").paramValue()).isEqualTo("type");
        assertThat(ShrinkageGroupBy.fromParam("PRODUCT")).isEqualTo(ShrinkageGroupBy.PRODUCT);
        assertThat(ShrinkageGroupBy.fromParam("Category")).isEqualTo(ShrinkageGroupBy.CATEGORY);
        assertThat(ShrinkageGroupBy.fromParam("store")).isEqualTo(ShrinkageGroupBy.STORE);
        assertThat(ShrinkageGroupBy.fromParam("Month")).isEqualTo(ShrinkageGroupBy.MONTH);

        assertThatThrownBy(() -> ShrinkageGroupBy.fromParam("week"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void generateTranslatesTypeLabelsAndCalculatesExactPctOfSales() {
        ShrinkageReportService service = new ShrinkageReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        Instant fromInstant = CLOCK.startOfDay(from);
        Instant toExclusive = CLOCK.startOfDay(to.plusDays(1));

        when(repository.findShrinkageRows(fromInstant, toExclusive, List.of(1L),
                ShrinkageGroupBy.TYPE, SANTIAGO.getId()))
                .thenReturn(List.of(
                        new ShrinkageAggregateRow("EXPIRED", "EXPIRED",
                                new BigDecimal("5.000"), new BigDecimal("10000.00"), 2L)));
        when(repository.findTotalNetSales(from, to, List.of(1L)))
                .thenReturn(new BigDecimal("1000000"));

        ReportResponse<ShrinkageReportRow> response;
        try (var ignored = StoreContext.bind(new StoreContext(1L, null))) {
            response = service.generate(new ReportFilter(from, to, List.of()), null);
        }

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("EXPIRED");
            assertThat(row.label()).isEqualTo("Vencimiento");
            assertThat(row.pctOfSales()).isEqualByComparingTo("1.00");
            assertThat(row.count()).isEqualTo(2L);
        });
        assertThat(response.totals().pctOfSales()).isEqualByComparingTo("1.00");
    }

    @Test
    void generateReturnsZeroPctWhenNetSalesIsZero() {
        ShrinkageReportService service = new ShrinkageReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 10);

        when(repository.findShrinkageRows(CLOCK.startOfDay(from), CLOCK.startOfDay(to.plusDays(1)),
                List.of(1L), ShrinkageGroupBy.PRODUCT, SANTIAGO.getId()))
                .thenReturn(List.of(
                        new ShrinkageAggregateRow("99", "Leche Descremada",
                                new BigDecimal("2.000"), new BigDecimal("2000.00"), 1L)));
        when(repository.findTotalNetSales(from, to, List.of(1L)))
                .thenReturn(BigDecimal.ZERO);

        ReportResponse<ShrinkageReportRow> response = service.generate(
                new ReportFilter(from, to, List.of(1L)), ShrinkageGroupBy.PRODUCT);

        assertThat(response.rows().get(0).label()).isEqualTo("Leche Descremada");
        assertThat(response.rows().get(0).pctOfSales()).isEqualByComparingTo("0.00");
        assertThat(response.totals().pctOfSales()).isEqualByComparingTo("0.00");
    }

    @Test
    void generateTopProductsUsesDefaultLimitAndValidatesBounds() {
        ShrinkageReportService service = new ShrinkageReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 10);
        Instant fromInstant = CLOCK.startOfDay(from);
        Instant toExclusive = CLOCK.startOfDay(to.plusDays(1));

        when(repository.findTopProducts(fromInstant, toExclusive, List.of(1L),
                ShrinkageReportService.DEFAULT_TOP_LIMIT))
                .thenReturn(List.of());
        when(repository.findTotalNetSales(from, to, List.of(1L)))
                .thenReturn(BigDecimal.ZERO);

        ReportResponse<ShrinkageReportRow> response = service.generateTopProducts(
                new ReportFilter(from, to, List.of(1L)), null);
        verify(repository).findTopProducts(fromInstant, toExclusive, List.of(1L), 10);
        assertThat(response.rows()).isEmpty();

        assertThatThrownBy(() -> service.generateTopProducts(
                new ReportFilter(from, to, List.of(1L)), 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getErrors()).extracting("field").containsExactly("limit");
                });

        assertThatThrownBy(() -> service.generateTopProducts(
                new ReportFilter(from, to, List.of(1L)), 101))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
