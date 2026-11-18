package cl.minimarket.pos.shared.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Retención de los datos que purgan los jobs de {@link DataPurgeJobs} (prefijo {@code pos.purge}).
 * Los defaults viven en código (H.1). T95 puede consolidarlos en {@code application.yml}.
 *
 * <p>Los horarios (cron) de cada job se configuran con sus propias propiedades, descritas en
 * {@link DataPurgeJobs}; el interruptor {@code pos.purge.enabled=false} desactiva los jobs.
 *
 * @param loginAttemptsRetention antigüedad máxima de {@code login_attempts} (90 días, MODELO_DATOS B.2)
 * @param refreshTokensRetention tiempo que se conserva un refresh token ya inservible (vencido o
 *        revocado) antes de purgarlo. Se conserva un tiempo para que la detección de reuso (RN-32)
 *        reconozca tokens rotados recientes y revoque su familia
 * @param storedFilesOrphanRetention antigüedad desde la que un archivo {@code IMPORT}/{@code EXPORT}
 *        se considera huérfano y se marca con {@code deleted_at} (30 días)
 */
@ConfigurationProperties(prefix = "pos.purge")
public record PurgeProperties(
        @DefaultValue("90d") Duration loginAttemptsRetention,
        @DefaultValue("7d") Duration refreshTokensRetention,
        @DefaultValue("30d") Duration storedFilesOrphanRetention) {

    /** Valida que las retenciones no sean negativas. */
    public PurgeProperties {
        requireNotNegative(loginAttemptsRetention, "login-attempts-retention");
        requireNotNegative(refreshTokensRetention, "refresh-tokens-retention");
        requireNotNegative(storedFilesOrphanRetention, "stored-files-orphan-retention");
    }

    private static void requireNotNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException("pos.purge." + name + " no puede ser negativa");
        }
    }
}
