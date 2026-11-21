package cl.minimarket.pos.reporting.export.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Utilidades de formateo en español chileno ({@code es-CL}) para las celdas de texto renderizadas
 * en PDF ({@code templates/pdf/report.html}).
 *
 * <p>No utiliza punto flotante ni conserva instancias mutables de {@link NumberFormat} entre
 * invocaciones.
 */
final class ReportExportFormatter {

    private static final Locale ES_CL = Locale.of("es", "CL");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private ReportExportFormatter() {
    }

    /** Formatea montos enteros en CLP (p. ej. {@code $12.350} o {@code -$2.000}). */
    static String formatClp(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getIntegerInstance(ES_CL);
        if (rounded.signum() < 0) {
            return "-$" + nf.format(rounded.abs());
        }
        return "$" + nf.format(rounded);
    }

    /** Formatea decimales con cantidad fija de decimales según {@code es-CL}. */
    static String formatDecimal(BigDecimal value, int scale) {
        if (value == null) {
            return "-";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(ES_CL);
        nf.setMinimumFractionDigits(scale);
        nf.setMaximumFractionDigits(scale);
        nf.setGroupingUsed(true);
        return nf.format(value.setScale(scale, RoundingMode.HALF_UP));
    }

    /** Formatea porcentajes a 2 decimales con sufijo {@code %}. */
    static String formatPercent(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return formatDecimal(value, 2) + " %";
    }

    /** Formatea cantidades enteras (transacciones, conteos, días). */
    static String formatInteger(long value) {
        return NumberFormat.getIntegerInstance(ES_CL).format(value);
    }

    /** Formatea enteros anulables; devuelve {@code "-"} si es nulo. */
    static String formatInteger(Long value) {
        return value == null ? "-" : formatInteger(value.longValue());
    }

    /** Formatea una fecha pura en {@code dd-MM-yyyy}. */
    static String formatDate(LocalDate date) {
        return date == null ? "-" : DATE_FORMAT.format(date);
    }

    /** Formatea un instante UTC convertido a la zona horaria de negocio. */
    static String formatInstant(Instant instant, ZoneId zone) {
        return instant == null ? "-" : DATE_TIME_FORMAT.format(instant.atZone(zone));
    }

    /** Normaliza texto nulo o vacío a {@code "-"}. */
    static String formatText(String text) {
        return (text == null || text.isBlank()) ? "-" : text;
    }
}
