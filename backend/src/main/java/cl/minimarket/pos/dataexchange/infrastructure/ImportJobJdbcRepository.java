package cl.minimarket.pos.dataexchange.infrastructure;

import cl.minimarket.pos.dataexchange.domain.ImportJob;
import cl.minimarket.pos.dataexchange.domain.ImportJobRepository;
import cl.minimarket.pos.dataexchange.domain.ImportJobStatus;
import cl.minimarket.pos.dataexchange.domain.ImportRowError;
import cl.minimarket.pos.dataexchange.domain.ImportType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JDBC para la tabla {@code import_jobs}.
 */
@Repository
public class ImportJobJdbcRepository implements ImportJobRepository {

    private static final TypeReference<List<ImportRowError>> ERROR_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ImportJobJdbcRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ImportJob insert(ImportType importType,
                            Long fileId,
                            ImportJobStatus status,
                            boolean dryRun,
                            int totalRows,
                            int okRows,
                            int errorRows,
                            List<ImportRowError> errors,
                            Instant finishedAt,
                            Long userId,
                            Instant now) {
        String errorsJson = errors == null || errors.isEmpty() ? null : jsonMapper.writeValueAsString(errors);
        return jdbc.sql("""
                        INSERT INTO import_jobs (
                            import_type, file_id, status, dry_run,
                            total_rows, ok_rows, error_rows, errors,
                            finished_at, created_at, created_by, updated_at, updated_by, version
                        )
                        VALUES (
                            :importType, :fileId, :status, :dryRun,
                            :totalRows, :okRows, :errorRows, CAST(:errors AS jsonb),
                            :finishedAt, :now, :userId, :now, :userId, 0
                        )
                        RETURNING id, import_type, file_id, status, dry_run,
                                  total_rows, ok_rows, error_rows, errors::text AS errors_text,
                                  created_at, finished_at, version
                        """)
                .param("importType", importType.name())
                .param("fileId", fileId)
                .param("status", status.name())
                .param("dryRun", dryRun)
                .param("totalRows", totalRows)
                .param("okRows", okRows)
                .param("errorRows", errorRows)
                .param("errors", errorsJson)
                .param("finishedAt", toTimestamp(finishedAt))
                .param("now", toTimestamp(now))
                .param("userId", userId)
                .query(this::mapRow)
                .single();
    }

    @Override
    public Optional<ImportJob> findById(Long id) {
        return jdbc.sql("""
                        SELECT id, import_type, file_id, status, dry_run,
                               total_rows, ok_rows, error_rows, errors::text AS errors_text,
                               created_at, finished_at, version
                        FROM import_jobs
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public ImportJob markCompleted(Long id, boolean dryRun, Instant finishedAt, Long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE import_jobs
                        SET status = :status,
                            dry_run = :dryRun,
                            finished_at = :finishedAt,
                            updated_at = :now,
                            updated_by = :userId,
                            version = version + 1
                        WHERE id = :id
                        RETURNING id, import_type, file_id, status, dry_run,
                                  total_rows, ok_rows, error_rows, errors::text AS errors_text,
                                  created_at, finished_at, version
                        """)
                .param("status", ImportJobStatus.COMPLETED.name())
                .param("dryRun", dryRun)
                .param("finishedAt", toTimestamp(finishedAt))
                .param("now", toTimestamp(now))
                .param("userId", userId)
                .param("id", id)
                .query(this::mapRow)
                .single();
    }

    private ImportJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        String errorsText = rs.getString("errors_text");
        List<ImportRowError> errors = errorsText == null || errorsText.isBlank()
                ? List.of()
                : jsonMapper.readValue(errorsText, ERROR_LIST_TYPE);
        Timestamp finishedTs = rs.getTimestamp("finished_at");
        return new ImportJob(
                rs.getLong("id"),
                ImportType.valueOf(rs.getString("import_type")),
                rs.getLong("file_id"),
                ImportJobStatus.valueOf(rs.getString("status")),
                rs.getBoolean("dry_run"),
                rs.getInt("total_rows"),
                rs.getInt("ok_rows"),
                rs.getInt("error_rows"),
                errors,
                rs.getTimestamp("created_at").toInstant(),
                finishedTs != null ? finishedTs.toInstant() : null,
                rs.getLong("version")
        );
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
