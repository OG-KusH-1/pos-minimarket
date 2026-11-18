package cl.minimarket.pos.shared.persistence;

import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.temporal.TemporalAccessor;
import java.util.Optional;

/**
 * Configuración de infraestructura de persistencia compartida.
 *
 * <ul>
 *   <li><b>Auditoría JPA</b> ({@link EnableJpaAuditing}): usuario desde {@link CurrentUserAuditor} y
 *       fecha desde {@link BusinessClock}, no desde el reloj de la JVM, para que las pruebas puedan
 *       fijar la hora y todo instante sea UTC (A.5 "Fechas").</li>
 *   <li><b>Scheduling</b> ({@link EnableScheduling}): habilita los {@code @Scheduled} de la
 *       aplicación (jobs de purga de {@link DataPurgeJobs}; ADR-31). Habilitarlo más de una vez en
 *       el proyecto es inofensivo.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
@EnableScheduling
@EnableConfigurationProperties(PurgeProperties.class)
public class PersistenceConfiguration {

    /**
     * Proveedor de fecha de la auditoría basado en {@link BusinessClock}.
     *
     * @param clock reloj de negocio
     * @return proveedor que entrega el instante actual (UTC)
     */
    @Bean
    DateTimeProvider auditingDateTimeProvider(BusinessClock clock) {
        return () -> Optional.<TemporalAccessor>of(clock.now());
    }
}
