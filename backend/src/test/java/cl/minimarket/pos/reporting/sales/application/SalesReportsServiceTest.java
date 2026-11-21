package cl.minimarket.pos.reporting.sales.application;

import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawNoRotationRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsRepository.RawSalesGroupRow;
import cl.minimarket.pos.reporting.sales.application.SalesReportsService.AbcCandidate;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.AbcView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.NoRotationView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.SalesReportView;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de reglas de negocio, validación de parámetros y cálculos de
 * {@link SalesReportsService} (T84).
 */
@ExtendWith(MockitoExtension.class)
class SalesReportsServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2030-06-15T16:00:00Z");
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final LocalDate FROM = LocalDate.of(2030, 6, 10);
    private static final LocalDate TO = LocalDate.of(2030, 6, 11);

    @Mock
    private SalesReportsRepository repository;

    private SalesReportsService service;

    @BeforeEach
    void setUp() {
        service = new SalesReportsService(repository, BusinessClock.fixed(FIXED_NOW, SANTIAGO));
    }

    @Test
    @DisplayName("Enums de parámetros aceptan minúsculas/mayúsculas y rechazan valores desconocidos")
    void paramEnumsParseCaseInsensitivelyAndRejectInvalid() {
        assertThat(SalesGroupBy.fromParam(null)).isEqualTo(SalesGroupBy.DAY);
        assertThat(SalesGroupBy.fromParam("hour")).isEqualTo(SalesGroupBy.HOUR);
        assertThat(SalesGroupBy.fromParam("WEEKDAY")).isEqualTo(SalesGroupBy.WEEKDAY);
        assertThat(SalesGroupBy.fromParam("Category")).isEqualTo(SalesGroupBy.CATEGORY);
        assertThat(SalesGroupBy.fromParam("store")).isEqualTo(SalesGroupBy.STORE);

        assertThat(RankingOrder.fromParam(null)).isEqualTo(RankingOrder.MOST);
        assertThat(RankingOrder.fromParam("least")).isEqualTo(RankingOrder.LEAST);

        assertThat(RankingMetric.fromParam(null)).isEqualTo(RankingMetric.QTY);
        assertThat(RankingMetric.fromParam("AMOUNT")).isEqualTo(RankingMetric.AMOUNT);

        assertThat(AbcMetric.fromParam(null)).isEqualTo(AbcMetric.AMOUNT);
        assertThat(AbcMetric.fromParam("qty")).isEqualTo(AbcMetric.QTY);
        assertThat(AbcMetric.fromParam("Margin")).isEqualTo(AbcMetric.MARGIN);

        assertThatThrownBy(() -> SalesGroupBy.fromParam("week"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> RankingOrder.fromParam("top"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> RankingMetric.fromParam("margin"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> AbcMetric.fromParam("cost"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Rechaza rango de 400 días y parámetros fuera de cota con VALIDATION_ERROR")
    void rejectsRangeOf400DaysAndInvalidBounds() {
        ReportFilter range400 = new ReportFilter(FROM, FROM.plusDays(399), List.of(1L));

        assertThatThrownBy(() -> service.salesReport(range400, SalesGroupBy.DAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.salesByCashier(range400))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.salesByPaymentMethod(range400))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.productRanking(range400, RankingOrder.MOST, RankingMetric.QTY, 10, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.noRotation(range400, 30, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.abc(range400, AbcMetric.AMOUNT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        ReportFilter valid = new ReportFilter(FROM, TO, List.of(1L));
        assertThatThrownBy(() -> service.productRanking(valid, RankingOrder.MOST, RankingMetric.QTY, 0, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.productRanking(valid, RankingOrder.MOST, RankingMetric.QTY, 101, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.productRanking(valid, RankingOrder.MOST, RankingMetric.QTY, 10, 0L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.noRotation(valid, 0, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("groupBy=category no duplica transacciones en totals y weekday traduce etiquetas")
    void categoryTotalsAvoidsDoubleCountAndWeekdayTranslatesLabels() {
        ReportFilter filter = new ReportFilter(FROM, TO, List.of(1L));
        when(repository.findSalesGrouped(FROM, TO, List.of(1L), SalesGroupBy.CATEGORY, "America/Santiago"))
                .thenReturn(List.of(
                        new RawSalesGroupRow("10", "Bebidas", 2L,
                                new BigDecimal("2380"), new BigDecimal("380"),
                                BigDecimal.ZERO, BigDecimal.ZERO),
                        new RawSalesGroupRow("20", "Snacks", 1L,
                                new BigDecimal("1190"), new BigDecimal("190"),
                                new BigDecimal("100"), BigDecimal.ZERO)));
        when(repository.countDistinctNonVoidedSales(FROM, TO, List.of(1L))).thenReturn(2L);

        ReportResponse<SalesReportView> categoryRes = service.salesReport(filter, SalesGroupBy.CATEGORY);
        assertThat(categoryRes.generatedAt()).isEqualTo(FIXED_NOW);
        assertThat(categoryRes.totals().transactions()).isEqualTo(2L);
        assertThat(categoryRes.totals().grossTotal()).isEqualByComparingTo("3570");
        assertThat(categoryRes.totals().netTotal()).isEqualByComparingTo("3000");
        assertThat(categoryRes.totals().taxTotal()).isEqualByComparingTo("570");
        assertThat(categoryRes.totals().avgTicket()).isEqualByComparingTo("1785");

        when(repository.findSalesGrouped(FROM, TO, List.of(1L), SalesGroupBy.WEEKDAY, "America/Santiago"))
                .thenReturn(List.of(
                        new RawSalesGroupRow("1", "1", 1L,
                                new BigDecimal("1190"), new BigDecimal("190"),
                                BigDecimal.ZERO, BigDecimal.ZERO),
                        new RawSalesGroupRow("7", "7", 1L,
                                new BigDecimal("2380"), new BigDecimal("380"),
                                BigDecimal.ZERO, BigDecimal.ZERO)));

        ReportResponse<SalesReportView> weekdayRes = service.salesReport(filter, SalesGroupBy.WEEKDAY);
        assertThat(weekdayRes.rows()).extracting(SalesReportView::label)
                .containsExactly("Lunes", "Domingo");
    }

    @Test
    @DisplayName("ABC clasifica en 80 % (A) y 95 % (B) exactos y retorna vacío sin positivos")
    void abcClassifiesExactBoundariesAndHandlesEmpty() {
        List<AbcView> rows = SalesReportsService.classifyAbc(List.of(
                new AbcCandidate(1L, "SKU-1", "Prod 1", new BigDecimal("500")),
                new AbcCandidate(2L, "SKU-2", "Prod 2", new BigDecimal("300")),
                new AbcCandidate(3L, "SKU-3", "Prod 3", new BigDecimal("150")),
                new AbcCandidate(4L, "SKU-4", "Prod 4", new BigDecimal("50"))));

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).abcClass()).isEqualTo("A");
        assertThat(rows.get(0).cumulativePct()).isEqualByComparingTo("50.0000");
        assertThat(rows.get(1).abcClass()).isEqualTo("A");
        assertThat(rows.get(1).cumulativePct()).isEqualByComparingTo("80.0000");
        assertThat(rows.get(2).abcClass()).isEqualTo("B");
        assertThat(rows.get(2).cumulativePct()).isEqualByComparingTo("95.0000");
        assertThat(rows.get(3).abcClass()).isEqualTo("C");
        assertThat(rows.get(3).cumulativePct()).isEqualByComparingTo("100.0000");

        assertThat(SalesReportsService.classifyAbc(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Sin rotación oculta valorización sin permiso y calcula días desde última venta")
    void noRotationRespectsCostVisibilityAndComputesDaysWithoutSale() {
        ReportFilter filter = new ReportFilter(FROM, TO, List.of(1L));
        Instant lastSale = Instant.parse("2030-05-01T15:00:00Z");
        when(repository.findNoRotation(TO, TO.minusDays(29), List.of(1L)))
                .thenReturn(List.of(
                        new RawNoRotationRow(1L, "SKU-A", "Sin ventas", new BigDecimal("5.000"),
                                new BigDecimal("800.0000"), null, null),
                        new RawNoRotationRow(2L, "SKU-B", "Antiguo", new BigDecimal("10.000"),
                                new BigDecimal("400.5000"), LocalDate.of(2030, 5, 1), lastSale)));

        ReportResponse<NoRotationView> hidden = service.noRotation(filter, 30, false);
        assertThat(hidden.rows()).hasSize(2);
        assertThat(hidden.rows().get(0).valuation()).isNull();
        assertThat(hidden.rows().get(0).daysWithoutSale()).isNull();
        assertThat(hidden.rows().get(1).valuation()).isNull();
        assertThat(hidden.rows().get(1).daysWithoutSale()).isEqualTo(41L);

        ReportResponse<NoRotationView> visible = service.noRotation(filter, 30, true);
        assertThat(visible.rows().get(0).valuation()).isEqualByComparingTo("4000.00");
        assertThat(visible.rows().get(1).valuation()).isEqualByComparingTo("4005.00");
    }
}
