package cl.minimarket.pos.reporting.cash;

import cl.minimarket.pos.reporting.cash.application.CashDifferenceReportService;
import cl.minimarket.pos.reporting.cash.infrastructure.CashDifferenceReportRepository;
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
 * Pruebas unitarias de {@link CashDifferenceReportService}.
 */
@ExtendWith(MockitoExtension.class)
class CashDifferenceReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final BusinessClock CLOCK = BusinessClock.fixed(NOW, ZoneId.of("America/Santiago"));

    @Mock
    private CashDifferenceReportRepository repository;

    @Test
    void generateSumsExpectedCountedAndDifferenceAndFallsBackToStoreContext() {
        CashDifferenceReportService service = new CashDifferenceReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 10);

        when(repository.findCashDifferences(from, to, List.of(5L), 7L, Boolean.TRUE))
                .thenReturn(List.of(
                        new CashDifferenceRow(101L, "CJ-101", LocalDate.of(2026, 11, 2),
                                "Cajero 1", "Caja 1",
                                new BigDecimal("50000"), new BigDecimal("49500"), new BigDecimal("-500"),
                                "Supervisor A"),
                        new CashDifferenceRow(102L, "CJ-102", LocalDate.of(2026, 11, 3),
                                "Cajero 1", "Caja 1",
                                new BigDecimal("30000"), new BigDecimal("30200"), new BigDecimal("200"),
                                null)));

        ReportResponse<CashDifferenceRow> response;
        try (var ignored = StoreContext.bind(new StoreContext(5L, null))) {
            response = service.generate(new ReportFilter(from, to, List.of()), 7L, Boolean.TRUE);
        }

        verify(repository).findCashDifferences(from, to, List.of(5L), 7L, Boolean.TRUE);
        assertThat(response.rows()).hasSize(2);
        assertThat(response.totals().sessionId()).isNull();
        assertThat(response.totals().number()).isEqualTo("TOTAL");
        assertThat(response.totals().expected()).isEqualByComparingTo("80000");
        assertThat(response.totals().counted()).isEqualByComparingTo("79700");
        assertThat(response.totals().difference()).isEqualByComparingTo("-300");
        assertThat(response.totals().approvedBy()).isNull();
    }

    @Test
    void generateRejectsNonPositiveCashierIdAndMissingRange() {
        CashDifferenceReportService service = new CashDifferenceReportService(repository, CLOCK);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 10);

        assertThatThrownBy(() -> service.generate(new ReportFilter(from, to, List.of(1L)), 0L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getErrors()).extracting("field").containsExactly("cashierId");
                });

        assertThatThrownBy(() -> service.generate(new ReportFilter(null, null, List.of(1L)), null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
