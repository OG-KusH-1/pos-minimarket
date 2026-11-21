package cl.minimarket.pos.reporting.dashboard.application;

import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.HourlySalesAggregateRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.PaymentMixRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.StockAlertsRow;
import cl.minimarket.pos.reporting.dashboard.infrastructure.DashboardJdbcRepository.TopProductRow;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias de {@link DashboardService}: cálculos de ticket promedio, variación vs día
 * anterior (incluyendo división por cero y redondeo HALF_UP), serie horaria densa de 24 horas y
 * validación de {@code storeIds}.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    // 2030-07-15 15:00 UTC = 2030-07-15 11:00 America/Santiago (UTC-4)
    private static final Instant FIXED_NOW = Instant.parse("2030-07-15T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2030, 7, 15);
    private static final LocalDate YESTERDAY = LocalDate.of(2030, 7, 14);

    @Mock
    private DashboardJdbcRepository repository;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        BusinessClock clock = BusinessClock.fixed(FIXED_NOW, SANTIAGO);
        service = new DashboardService(repository, clock);
    }

    @Test
    void defaultsDateToTodayBuilds24HoursAndRoundsMetricsHalfUp() {
        when(repository.findHourlySalesForDates(List.of(10L), TODAY, YESTERDAY, "America/Santiago"))
                .thenReturn(List.of(
                        new HourlySalesAggregateRow(YESTERDAY, 11, 2L, new BigDecimal("50000")),
                        new HourlySalesAggregateRow(TODAY, 9, 2L, new BigDecimal("35000")),
                        new HourlySalesAggregateRow(TODAY, 14, 1L, new BigDecimal("63000"))
                ));
        when(repository.findTopProducts(List.of(10L), TODAY))
                .thenReturn(List.of(new TopProductRow(5L, "Café", new BigDecimal("3"), new BigDecimal("15000"))));
        when(repository.findPaymentMix(List.of(10L), TODAY))
                .thenReturn(List.of(new PaymentMixRow("CASH", new BigDecimal("98000"))));
        when(repository.findStockAlerts(List.of(10L))).thenReturn(new StockAlertsRow(2L, 1L));
        when(repository.countExpiringLots(List.of(10L), TODAY)).thenReturn(3L);
        when(repository.countPendingShrinkages(List.of(10L))).thenReturn(4L);
        when(repository.countPendingAdjustments(List.of(10L))).thenReturn(5L);
        when(repository.countPendingCounts(List.of(10L))).thenReturn(6L);
        when(repository.countPendingCashDifferences(List.of(10L))).thenReturn(7L);

        DashboardView view = service.getDashboard(null, null, 10L, 1L, false);

        assertThat(view.date()).isEqualTo(TODAY);
        assertThat(view.salesTotal()).isEqualByComparingTo("98000");
        assertThat(view.transactions()).isEqualTo(3L);
        // 98000 / 3 = 32666.666... -> 32667 HALF_UP
        assertThat(view.avgTicket()).isEqualByComparingTo("32667");
        // (98000 - 50000) / 50000 * 100 = 96.00
        assertThat(view.vsPreviousDayPct()).isEqualByComparingTo("96.00");

        assertThat(view.salesByHour()).hasSize(24);
        assertThat(view.salesByHour().get(0).hour()).isZero();
        assertThat(view.salesByHour().get(0).total()).isEqualByComparingTo("0");
        assertThat(view.salesByHour().get(9).total()).isEqualByComparingTo("35000");
        assertThat(view.salesByHour().get(9).transactions()).isEqualTo(2L);
        assertThat(view.salesByHour().get(14).total()).isEqualByComparingTo("63000");
        assertThat(view.salesByHour().get(14).transactions()).isEqualTo(1L);
        assertThat(view.salesByHour().get(23).hour()).isEqualTo(23);

        assertThat(view.topProducts()).hasSize(1);
        assertThat(view.topProducts().getFirst().qty()).isEqualByComparingTo("3.000");
        assertThat(view.alerts().belowMin()).isEqualTo(2L);
        assertThat(view.alerts().zeroStock()).isEqualTo(1L);
        assertThat(view.alerts().expiringLots()).isEqualTo(3L);
        assertThat(view.alerts().pendingShrinkages()).isEqualTo(4L);
        assertThat(view.alerts().pendingAdjustments()).isEqualTo(5L);
        assertThat(view.alerts().pendingCounts()).isEqualTo(6L);
        assertThat(view.alerts().pendingCashDifferences()).isEqualTo(7L);

        verify(repository, never()).countAccessibleActiveStores(any(), any(), eq(false));
    }

    @Test
    void returnsZeroAvgTicketAndNullVsPreviousDayPctWhenNoSales() {
        when(repository.findHourlySalesForDates(List.of(10L), TODAY, YESTERDAY, "America/Santiago"))
                .thenReturn(List.of());
        when(repository.findTopProducts(List.of(10L), TODAY)).thenReturn(List.of());
        when(repository.findPaymentMix(List.of(10L), TODAY)).thenReturn(List.of());
        when(repository.findStockAlerts(List.of(10L))).thenReturn(new StockAlertsRow(0L, 0L));

        DashboardView view = service.getDashboard(TODAY, List.of(10L, 10L), 10L, 1L, false);

        assertThat(view.salesTotal()).isEqualByComparingTo("0");
        assertThat(view.transactions()).isZero();
        assertThat(view.avgTicket()).isEqualByComparingTo("0");
        assertThat(view.vsPreviousDayPct()).isNull();
        assertThat(view.salesByHour()).hasSize(24);
        assertThat(view.salesByHour()).allSatisfy(h -> {
            assertThat(h.total()).isEqualByComparingTo("0");
            assertThat(h.transactions()).isZero();
        });
    }

    @Test
    void computesNegativeVsPreviousDayPctCorrectly() {
        when(repository.findHourlySalesForDates(List.of(10L), TODAY, YESTERDAY, "America/Santiago"))
                .thenReturn(List.of(
                        new HourlySalesAggregateRow(YESTERDAY, 10, 1L, new BigDecimal("80000")),
                        new HourlySalesAggregateRow(TODAY, 10, 1L, new BigDecimal("20000"))
                ));
        when(repository.findTopProducts(List.of(10L), TODAY)).thenReturn(List.of());
        when(repository.findPaymentMix(List.of(10L), TODAY)).thenReturn(List.of());
        when(repository.findStockAlerts(List.of(10L))).thenReturn(new StockAlertsRow(0L, 0L));

        DashboardView view = service.getDashboard(TODAY, null, 10L, 1L, false);

        // (20000 - 80000) / 80000 * 100 = -75.00
        assertThat(view.vsPreviousDayPct()).isEqualByComparingTo("-75.00");
    }

    @Test
    void rejectsNonPositiveStoreIdsWithValidationError() {
        assertThatThrownBy(() -> service.getDashboard(TODAY, List.of(10L, 0L), 10L, 1L, false))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getErrors()).hasSize(1);
                    assertThat(ex.getErrors().getFirst().field()).isEqualTo("storeIds");
                });
    }

    @Test
    void rejectsUnassignedOrInactiveStoresWithStoreAccessDenied() {
        when(repository.countAccessibleActiveStores(List.of(10L, 20L), 7L, false)).thenReturn(1L);

        assertThatThrownBy(() -> service.getDashboard(TODAY, List.of(10L, 20L), 10L, 7L, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STORE_ACCESS_DENIED));
    }
}
