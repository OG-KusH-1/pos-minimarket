package cl.minimarket.pos.reporting.purchasing;

import cl.minimarket.pos.reporting.purchasing.application.PurchasingReportService;
import cl.minimarket.pos.reporting.purchasing.infrastructure.PurchasingReportRepository;
import cl.minimarket.pos.reporting.purchasing.infrastructure.PurchasingReportRepository.SupplierPurchaseAggregate;
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
 * Pruebas unitarias de {@link PurchasingReportService}.
 */
@ExtendWith(MockitoExtension.class)
class PurchasingReportServiceTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final BusinessClock CLOCK = BusinessClock.fixed(NOW, SANTIAGO);

    @Mock
    private PurchasingReportRepository repository;

    @Test
    void generateComputesWeightedLeadTimeAndFillRateAndNullFallbacks() {
        PurchasingReportService service = new PurchasingReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 15);
        Instant fromInstant = CLOCK.startOfDay(from);
        Instant toExclusive = CLOCK.startOfDay(to.plusDays(1));

        when(repository.findSupplierAggregates(fromInstant, toExclusive, List.of(2L)))
                .thenReturn(List.of(
                        new SupplierPurchaseAggregate(
                                10L, "Proveedor Alpha", 2L, 2L, new BigDecimal("50000"),
                                new BigDecimal("100.000"), new BigDecimal("80.000"),
                                new BigDecimal("5.00"), 2L),
                        new SupplierPurchaseAggregate(
                                11L, "Proveedor Directo", 0L, 1L, new BigDecimal("10000"),
                                BigDecimal.ZERO, new BigDecimal("20.000"),
                                null, 0L),
                        new SupplierPurchaseAggregate(
                                12L, "Proveedor Beta", 1L, 1L, new BigDecimal("40000"),
                                new BigDecimal("100.000"), new BigDecimal("100.000"),
                                new BigDecimal("4.00"), 1L)));

        ReportResponse<PurchasesBySupplierRow> response;
        try (var ignored = StoreContext.bind(new StoreContext(2L, null))) {
            response = service.generate(new ReportFilter(from, to, List.of()));
        }

        verify(repository).findSupplierAggregates(fromInstant, toExclusive, List.of(2L));
        assertThat(response.rows()).hasSize(3);

        PurchasesBySupplierRow alpha = response.rows().get(0);
        assertThat(alpha.avgLeadTimeDays()).isEqualByComparingTo("2.50");
        assertThat(alpha.fillRatePct()).isEqualByComparingTo("80.00");

        PurchasesBySupplierRow direct = response.rows().get(1);
        assertThat(direct.avgLeadTimeDays()).isNull();
        assertThat(direct.fillRatePct()).isNull();

        PurchasesBySupplierRow totals = response.totals();
        assertThat(totals.supplierId()).isNull();
        assertThat(totals.name()).isEqualTo("Total");
        assertThat(totals.orders()).isEqualTo(3L);
        assertThat(totals.receipts()).isEqualTo(4L);
        assertThat(totals.netTotal()).isEqualByComparingTo("100000");
        // Promedio ponderado: (5 + 4) / 3 = 3.00 días; recibido/pedido = (80 + 20 + 100) / (100 + 0 + 100) = 100.00%
        assertThat(totals.avgLeadTimeDays()).isEqualByComparingTo("3.00");
        assertThat(totals.fillRatePct()).isEqualByComparingTo("100.00");
    }

    @Test
    void generateRequiresDateRange() {
        PurchasingReportService service = new PurchasingReportService(repository, CLOCK);
        ReportFilter missing = new ReportFilter(null, null, List.of(1L));

        assertThatThrownBy(() -> service.generate(missing))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
