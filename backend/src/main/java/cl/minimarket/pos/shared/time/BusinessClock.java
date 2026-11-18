package cl.minimarket.pos.shared.time;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Reloj de negocio inyectable (A.5 "Fechas").
 *
 * <p>Todo instante se guarda en UTC ({@code timestamptz}) y la JVM corre en {@code TZ=UTC}; la
 * <b>zona de negocio</b> ({@code company.timezone}, por defecto {@code America/Santiago}) solo se
 * usa para decidir a qué día calendario pertenece un instante: {@code business_date} de ventas,
 * turnos y agregados. Centralizar esa conversión aquí evita usar {@code LocalDate.now()} (que
 * depende de la zona de la JVM) y permite fijar la hora en las pruebas.
 *
 * <p>Configuración: propiedad {@value #ZONE_PROPERTY} (definida en {@code application.yml}). Una
 * zona inválida hace fallar el arranque (fail-fast).
 *
 * <p>En pruebas se construye con {@link #BusinessClock(Clock, ZoneId)} y un
 * {@link Clock#fixed(Instant, ZoneId)}, o con {@link #fixed(Instant, ZoneId)}.
 *
 * <p>Punto de extensión: si la zona pasa a leerse de {@code company.timezone} en BD, reemplazar
 * el campo {@code zone} por un proveedor sin cambiar la API pública.
 */
@Component
public class BusinessClock {

    /** Propiedad de Spring con la zona de negocio. */
    public static final String ZONE_PROPERTY = "pos.business-zone";

    private final Clock clock;
    private final ZoneId zone;

    /**
     * Constructor de Spring: reloj del sistema y zona leída de {@value #ZONE_PROPERTY}.
     *
     * @param zoneId identificador de zona IANA (p. ej. {@code America/Santiago})
     * @throws java.time.DateTimeException si la zona no existe
     */
    @Autowired
    public BusinessClock(@Value("${pos.business-zone:America/Santiago}") String zoneId) {
        this(Clock.systemUTC(), ZoneId.of(zoneId));
    }

    /**
     * Constructor para pruebas o composición manual.
     *
     * @param clock fuente de tiempo
     * @param zone zona de negocio
     */
    public BusinessClock(Clock clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Reloj con un instante fijo, para pruebas. */
    public static BusinessClock fixed(Instant instant, ZoneId zone) {
        return new BusinessClock(Clock.fixed(instant, ZoneId.of("UTC")), zone);
    }

    /** Instante actual (UTC), apto para persistir en {@code timestamptz}. */
    public Instant now() {
        return clock.instant();
    }

    /** Zona de negocio. */
    public ZoneId zone() {
        return zone;
    }

    /** Fecha de negocio actual: el día calendario de {@link #now()} en la zona de negocio. */
    public LocalDate businessDate() {
        return businessDate(now());
    }

    /**
     * Fecha de negocio de un instante dado (p. ej. para recalcular el día de una venta antigua).
     *
     * @param instant instante a convertir
     * @return día calendario de ese instante en la zona de negocio
     */
    public LocalDate businessDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /**
     * Primer instante de una fecha de negocio (00:00 en la zona de negocio). Útil para filtros de
     * rango {@code [startOfDay(d), startOfDay(d + 1))}, que además son correctos en días con
     * cambio de hora.
     *
     * @param date fecha de negocio
     * @return instante de inicio del día
     */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }
}
