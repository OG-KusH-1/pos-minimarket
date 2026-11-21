package cl.minimarket.pos.reporting.export.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Consultas JDBC de solo lectura para enriquecer el encabezado y los filtros legibles de los
 * reportes exportados (nombre de la empresa, locales, categoría o cajero).
 */
@Repository
public class ReportExportMetadataRepository {

    private static final String DEFAULT_COMPANY_NAME = "Minimarket";

    private final JdbcClient jdbc;

    public ReportExportMetadataRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Obtiene el nombre comercial o razón social de la empresa; si aún no se configura, retorna
     * {@value #DEFAULT_COMPANY_NAME}.
     */
    public String findCompanyDisplayName() {
        return jdbc.sql("""
                        SELECT COALESCE(NULLIF(BTRIM(trade_name), ''), NULLIF(BTRIM(legal_name), ''), :fallback)
                        FROM company
                        ORDER BY id ASC
                        LIMIT 1
                        """)
                .param("fallback", DEFAULT_COMPANY_NAME)
                .query(String.class)
                .optional()
                .orElse(DEFAULT_COMPANY_NAME);
    }

    /**
     * Formatea los nombres de los locales solicitados como {@code "CÓDIGO - Nombre"}, preservando el
     * orden recibido.
     */
    public String formatStoreNames(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return "-";
        }
        Map<Long, String> labelsById = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, code, name
                        FROM stores
                        WHERE id IN (:storeIds)
                        """)
                .param("storeIds", storeIds)
                .query((rs, rowNum) -> {
                    long id = rs.getLong("id");
                    String code = rs.getString("code");
                    String name = rs.getString("name");
                    labelsById.put(id, code != null && !code.isBlank() ? code + " - " + name : name);
                    return null;
                })
                .list();
        return storeIds.stream()
                .map(id -> labelsById.getOrDefault(id, "Local #" + id))
                .collect(Collectors.joining(", "));
    }

    /** Busca el nombre legible de una categoría para mostrarlo en el encabezado de filtros. */
    public Optional<String> findCategoryName(Long categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT name FROM categories WHERE id = :id")
                .param("id", categoryId)
                .query(String.class)
                .optional();
    }

    /** Busca el nombre legible de un usuario cajero para el filtro del reporte de diferencias. */
    public Optional<String> findUserDisplayName(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT COALESCE(NULLIF(BTRIM(full_name), ''), username) FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }
}
