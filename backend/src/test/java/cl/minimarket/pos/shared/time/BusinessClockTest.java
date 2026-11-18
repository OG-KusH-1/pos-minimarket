package cl.minimarket.pos.shared.time;

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessClockTest {

    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");

    /** En noviembre Chile está en horario de verano (UTC-3): 02:30Z del 13 es 23:30 del 12. */
    @Test
    void businessDateUsesBusinessZoneInSummer() {
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-11-13T02:30:00Z"), SANTIAGO);

        assertThat(clock.businessDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-11-13T02:30:00Z"));
    }

    /** En julio rige el horario de invierno (UTC-4): 03:30Z del 1 es 23:30 del 30 de junio. */
    @Test
    void businessDateUsesBusinessZoneInWinter() {
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-07-01T03:30:00Z"), SANTIAGO);

        assertThat(clock.businessDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void businessDateOfAnArbitraryInstant() {
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-01-01T00:00:00Z"), SANTIAGO);

        assertThat(clock.businessDate(Instant.parse("2026-11-13T12:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void startOfDayIsMidnightInBusinessZone() {
        BusinessClock clock = BusinessClock.fixed(Instant.parse("2026-11-13T02:30:00Z"), SANTIAGO);

        assertThat(clock.startOfDay(LocalDate.of(2026, 11, 12))).isEqualTo(Instant.parse("2026-11-12T03:00:00Z"));
        assertThat(clock.zone()).isEqualTo(SANTIAGO);
    }

    @Test
    void springConstructorReadsZoneId() {
        assertThat(new BusinessClock("America/Santiago").zone()).isEqualTo(SANTIAGO);
        assertThatThrownBy(() -> new BusinessClock("Mars/Olympus")).isInstanceOf(DateTimeException.class);
    }
}
