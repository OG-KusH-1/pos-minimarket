package cl.minimarket.pos.reporting.aggregate;

import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del job de las 02:00 (T78): delega en {@link DailySalesAggregateService} con la
 * fecha de negocio del reloj y, siguiendo el patrón de los demás jobs del proyecto (ADR-31),
 * <b>nunca propaga</b> una excepción: un fallo se registra y la corrida siguiente reintenta.
 */
class DailySalesAggregateJobTest {

    private static final Instant RUN_INSTANT = Instant.parse("2030-06-15T06:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");

    @Test
    void delegatesToServiceWithBusinessDate() {
        DailySalesAggregateService service = mock(DailySalesAggregateService.class);
        when(service.refreshRecentDays(LocalDate.of(2030, 6, 15))).thenReturn(42);
        BusinessClock clock = BusinessClock.fixed(RUN_INSTANT, ZONE);
        DailySalesAggregateJob job = new DailySalesAggregateJob(service, clock);

        assertThatCode(job::refreshDailyProductSales).doesNotThrowAnyException();
        verify(service).refreshRecentDays(LocalDate.of(2030, 6, 15));
    }

    @Test
    void neverPropagatesServiceFailures() {
        DailySalesAggregateService service = mock(DailySalesAggregateService.class);
        when(service.refreshRecentDays(LocalDate.of(2030, 6, 15)))
                .thenThrow(new IllegalStateException("BD caída"));
        BusinessClock clock = BusinessClock.fixed(RUN_INSTANT, ZONE);
        DailySalesAggregateJob job = new DailySalesAggregateJob(service, clock);

        assertThatCode(job::refreshDailyProductSales).doesNotThrowAnyException();
        verify(service).refreshRecentDays(LocalDate.of(2030, 6, 15));
    }
}
