package cl.minimarket.pos.shared.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Conversiones compartidas por los escritores y lectores de archivos (uso interno del paquete).
 *
 * <p>Centraliza la zona de negocio {@code America/Santiago} (A.5 del plan): todo instante se muestra
 * en hora local de Chile, nunca en UTC.
 */
final class ExportFormats {

    /** Zona horaria de negocio. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Santiago");

    /** Formato ISO de fecha-hora local usado en CSV. */
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ExportFormats() {
    }

    /**
     * Convierte cualquier tipo temporal soportado a fecha-hora local de negocio.
     *
     * @param value {@link LocalDateTime}, {@link Instant}, {@link OffsetDateTime} o {@link ZonedDateTime}
     * @return fecha-hora en {@link #BUSINESS_ZONE}
     * @throws IllegalArgumentException si el tipo no es soportado
     */
    static LocalDateTime toBusinessDateTime(Object value) {
        if (value instanceof LocalDateTime local) {
            return local;
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, BUSINESS_ZONE);
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        }
        if (value instanceof ZonedDateTime zoned) {
            return zoned.withZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        }
        throw new IllegalArgumentException("Tipo de fecha-hora no soportado: " + typeName(value));
    }

    /**
     * Convierte enteros y {@link BigDecimal} a {@link BigDecimal}; el dinero y las cantidades nunca
     * pasan por punto flotante (A.5).
     *
     * @throws IllegalArgumentException si el valor no es {@link BigDecimal}, {@link Integer} ni {@link Long}
     */
    static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw new IllegalArgumentException("Se esperaba BigDecimal, Integer o Long y llegó " + typeName(value));
    }

    /** Indica si el valor es una fecha-hora (no una fecha pura) soportada. */
    static boolean isDateTime(Object value) {
        return value instanceof LocalDateTime || value instanceof Instant
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime;
    }

    /** Nombre de tipo seguro para mensajes de error. */
    static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /** Texto ISO de una fecha pura. */
    static String formatDate(LocalDate date) {
        return date.toString();
    }
}
