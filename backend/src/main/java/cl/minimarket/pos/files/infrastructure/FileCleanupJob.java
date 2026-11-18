package cl.minimarket.pos.files.infrastructure;

import cl.minimarket.pos.files.application.FileCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job programado (ADR-31) que elimina del almacenamiento físico los binarios de los registros
 * {@code stored_files} que ya tienen {@code deleted_at}.
 *
 * <p>Corre después de {@code DataPurgeJobs.markOrphanStoredFiles} (03:45) en la zona de negocio.
 */
@Component
@ConditionalOnProperty(prefix = "pos.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(FileCleanupJob.class);

    private final FileCleanupService cleanupService;

    public FileCleanupJob(FileCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /** Borra los binarios de {@code stored_files} marcados como eliminados. */
    @Scheduled(cron = "${pos.files.cleanup-cron:0 0 4 * * *}", zone = "${pos.business-zone:America/Santiago}")
    public void cleanupDeletedFiles() {
        try {
            int processed = cleanupService.deleteMarkedBinaries();
            LOG.info("Limpieza física de stored_files: {} registros procesados", processed);
        } catch (RuntimeException e) {
            LOG.error("Falló la limpieza física de archivos eliminados; se reintentará en la próxima ejecución", e);
        }
    }
}
