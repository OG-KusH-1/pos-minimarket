package cl.minimarket.pos.dataexchange.domain;

import java.time.Instant;
import java.util.List;

/**
 * Representación de un trabajo de importación masiva persistido en {@code import_jobs}.
 */
public record ImportJob(
        Long id,
        ImportType importType,
        Long fileId,
        ImportJobStatus status,
        boolean dryRun,
        int totalRows,
        int okRows,
        int errorRows,
        List<ImportRowError> errors,
        Instant createdAt,
        Instant finishedAt,
        long version
) {
}
