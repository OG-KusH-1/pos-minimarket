package cl.minimarket.pos.dataexchange.web;

import cl.minimarket.pos.dataexchange.domain.ImportJob;
import cl.minimarket.pos.dataexchange.domain.ImportJobStatus;
import cl.minimarket.pos.dataexchange.domain.ImportRowError;
import cl.minimarket.pos.dataexchange.domain.ImportType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta pública de un trabajo de importación masiva ({@code API.md} F.3).
 */
@Schema(name = "DataExchangeImportJobResponse")
public record ImportJobResponse(
        Long id,
        ImportType importType,
        ImportJobStatus status,
        boolean dryRun,
        int totalRows,
        int okRows,
        int errorRows,
        List<RowErrorItem> errors,
        Instant createdAt,
        Instant finishedAt
) {

    /** Error de validación en una fila de importación. */
    @Schema(name = "DataExchangeImportRowError")
    public record RowErrorItem(
            int row,
            String field,
            String message
    ) {
        public static RowErrorItem from(ImportRowError error) {
            return new RowErrorItem(error.row(), error.field(), error.message());
        }
    }

    /** Convierte un {@link ImportJob} de dominio a DTO de respuesta. */
    public static ImportJobResponse from(ImportJob job) {
        List<RowErrorItem> items = job.errors() == null
                ? List.of()
                : job.errors().stream().map(RowErrorItem::from).toList();
        return new ImportJobResponse(
                job.id(),
                job.importType(),
                job.status(),
                job.dryRun(),
                job.totalRows(),
                job.okRows(),
                job.errorRows(),
                items,
                job.createdAt(),
                job.finishedAt()
        );
    }
}
