package cl.minimarket.pos.dataexchange.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia de {@link ImportJob} en {@code import_jobs}.
 */
public interface ImportJobRepository {

    ImportJob insert(ImportType importType,
                     Long fileId,
                     ImportJobStatus status,
                     boolean dryRun,
                     int totalRows,
                     int okRows,
                     int errorRows,
                     List<ImportRowError> errors,
                     Instant finishedAt,
                     Long userId,
                     Instant now);

    Optional<ImportJob> findById(Long id);

    ImportJob markCompleted(Long id, boolean dryRun, Instant finishedAt, Long userId, Instant now);
}
