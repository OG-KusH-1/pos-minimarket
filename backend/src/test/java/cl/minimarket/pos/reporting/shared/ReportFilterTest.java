package cl.minimarket.pos.reporting.shared;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de los tipos comunes de reportes (T78, API.md F.3): validación del rango de
 * {@link ReportFilter} en sus dos modos y copia defensiva de {@link ReportResponse}.
 *
 * <p>Los tests están aplanados en la clase externa (sin {@code @Nested}): era la única clase de
 * la suite que usaba anidamiento y surefire le generaba reportes con "Tests run: 0" en el
 * test set externo, lo que impedía auditar la cobertura real de la clase en los reportes del
 * {@code verify}. Los métodos de cada modo se agrupan con {@code @DisplayName} en español.
 */
class ReportFilterTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("Modo rango obligatorio: sin fechas responde VALIDATION_ERROR")
    void rangeModeRejectsMissingDates() {
        ReportFilter filter = new ReportFilter(null, null, List.of());

        assertThatThrownBy(filter::requireRange)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessageContaining("rango");
    }

    @Test
    @DisplayName("Modo rango obligatorio: una sola fecha responde VALIDATION_ERROR")
    void rangeModeRejectsOnlyOneDate() {
        assertThatThrownBy(() -> new ReportFilter(FROM, null, List.of()).requireRange())
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ReportFilter(null, FROM, List.of()).requireRange())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Modo rango obligatorio: 'from' posterior a 'to' responde VALIDATION_ERROR")
    void rangeModeRejectsFromAfterTo() {
        ReportFilter filter = new ReportFilter(FROM, FROM.minusDays(1), List.of());

        assertThatThrownBy(filter::requireRange)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("posterior");
    }

    @Test
    @DisplayName("Modo rango obligatorio: un rango de 400 días responde VALIDATION_ERROR")
    void rangeModeRejectsRangeOf400Days() {
        // Criterio de T84: un rango de 400 días responde 400 VALIDATION_ERROR.
        ReportFilter filter = new ReportFilter(FROM, FROM.plusDays(399), List.of());

        assertThatThrownBy(filter::requireRange)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("400")
                .hasMessageContaining(String.valueOf(ReportFilter.MAX_RANGE_DAYS));
    }

    @Test
    @DisplayName("Modo rango obligatorio: acepta un rango de exactamente 366 días")
    void rangeModeAcceptsRangeOfExactly366Days() {
        ReportFilter filter = new ReportFilter(FROM, FROM.plusDays(365), List.of());

        assertThatCode(filter::requireRange).doesNotThrowAnyException();
        assertThat(filter.requireRange()).isSameAs(filter);
        assertThat(filter.hasRange()).isTrue();
    }

    @Test
    @DisplayName("Modo rango obligatorio: acepta un rango de un solo día")
    void rangeModeAcceptsSingleDayRange() {
        ReportFilter filter = new ReportFilter(FROM, FROM, List.of());

        assertThatCode(filter::requireRange).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Modo rango opcional: acepta ambas fechas ausentes")
    void optionalModeAcceptsBothDatesAbsent() {
        ReportFilter filter = new ReportFilter(null, null, List.of());

        assertThatCode(filter::optionalRange).doesNotThrowAnyException();
        assertThat(filter.hasRange()).isFalse();
    }

    @Test
    @DisplayName("Modo rango opcional: una sola fecha responde VALIDATION_ERROR")
    void optionalModeRejectsOnlyOneDate() {
        assertThatThrownBy(() -> new ReportFilter(FROM, null, List.of()).optionalRange())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("juntas");
        assertThatThrownBy(() -> new ReportFilter(null, FROM, List.of()).optionalRange())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Modo rango opcional: valida rango cuando ambas fechas están presentes")
    void optionalModeValidatesRangeWhenBothPresent() {
        assertThatThrownBy(() -> new ReportFilter(FROM, FROM.plusDays(400), List.of()).optionalRange())
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ReportFilter(FROM, FROM.minusDays(1), List.of()).optionalRange())
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> new ReportFilter(FROM, FROM.plusDays(365), List.of()).optionalRange())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("storeIds: una lista nula se transforma en lista vacía")
    void storeIdsNullListBecomesEmpty() {
        assertThat(new ReportFilter(FROM, FROM, null).storeIds()).isEmpty();
    }

    @Test
    @DisplayName("storeIds: rechaza elementos nulos en la construcción")
    void storeIdsRejectsNullElementsAtConstruction() {
        // List.copyOf del constructor compacto: elemento nulo es error del llamador (NPE).
        assertThatThrownBy(() -> new ReportFilter(FROM, FROM, java.util.Arrays.asList(1L, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("storeIds: copia defensiva de la lista entregada")
    void storeIdsCopiedDefensively() {
        List<Long> ids = new ArrayList<>(List.of(1L));
        ReportFilter filter = new ReportFilter(FROM, FROM, ids);
        ids.add(2L);

        assertThat(filter.storeIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("ReportResponse: filas nulas se vuelven vacías y totals puede ser nulo")
    void responseNullRowsBecomeEmptyAndTotalsMayBeNull() {
        ReportFilter filter = new ReportFilter(FROM, FROM, List.of()).requireRange();
        ReportResponse<String> response = new ReportResponse<>(filter, Instant.EPOCH, null, null);

        assertThat(response.rows()).isEmpty();
        assertThat(response.totals()).isNull();
        assertThat(response.filters()).isSameAs(filter);
    }

    @Test
    @DisplayName("ReportResponse: copia defensiva de las filas")
    void responseRowsAreCopiedDefensively() {
        ReportFilter filter = new ReportFilter(null, null, List.of()).optionalRange();
        List<String> rows = new ArrayList<>(List.of("a"));
        ReportResponse<String> response = new ReportResponse<>(filter, Instant.EPOCH, rows, null);
        rows.add("b");

        assertThat(response.rows()).containsExactly("a");
    }

    @Test
    @DisplayName("ReportResponse: exige filters y generatedAt no nulos")
    void responseRequiresFiltersAndGeneratedAt() {
        ReportFilter filter = new ReportFilter(FROM, FROM, List.of());

        assertThatThrownBy(() -> new ReportResponse<>(null, Instant.EPOCH, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("filters");
        assertThatThrownBy(() -> new ReportResponse<>(filter, null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generatedAt");
    }
}
