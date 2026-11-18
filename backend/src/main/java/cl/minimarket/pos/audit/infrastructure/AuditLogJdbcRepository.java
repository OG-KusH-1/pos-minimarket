package cl.minimarket.pos.audit.infrastructure;

import cl.minimarket.pos.audit.application.AuditLogFilter;
import cl.minimarket.pos.audit.domain.AuditHasher;
import cl.minimarket.pos.audit.domain.AuditLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio JDBC de {@code audit_log}.
 *
 * <p>Usa {@link JdbcClient} en lugar de JPA porque la tabla combina columnas específicas de
 * PostgreSQL ({@code inet}, {@code jsonb}), el advisory lock transaccional
 * ({@code pg_advisory_xact_lock}) y lectura por lotes ordenada por {@code id} para verificar la
 * cadena de hash sin cargar toda la bitácora en memoria.
 */
@Repository
public class AuditLogJdbcRepository {

    private static final String SELECT_COLUMNS = """
            id, occurred_at, user_id, username, store_id, terminal_id,
            host(ip) AS ip_text, action, entity_type, entity_id,
            old_value::text AS old_value_text, new_value::text AS new_value_text,
            reason, authorization_id, trace_id, prev_hash, hash
            """;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "occurredAt", "occurred_at",
            "action", "action",
            "entityType", "entity_type",
            "username", "username",
            "userId", "user_id",
            "storeId", "store_id"
    );

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC administrado por Spring (participa en la transacción activa)
     */
    public AuditLogJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Toma el advisory lock transaccional sobre {@code audit_log} para serializar el cálculo de
     * {@code prev_hash} y evitar bifurcaciones en la cadena bajo concurrencia (ADR-18).
     */
    public void acquireChainLock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('audit_log'))")
                .query(Object.class)
                .optional();
    }

    /**
     * Canonicaliza una dirección IP usando exactamente las mismas reglas de PostgreSQL
     * ({@code host(CAST(:ip AS inet))}) que se emplean al leer la columna para verificar la
     * cadena, evitando falsos positivos en IPv6 (p. ej. {@code 0:0:0:0:0:0:0:1} vs {@code ::1}).
     *
     * @param ip literal IPv4 o IPv6 sin máscara, o {@code null}
     * @return representación textual canónica devuelta por PostgreSQL, o {@code null} si la entrada es nula/vacía
     */
    public String canonicalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return jdbc.sql("SELECT host(CAST(:ip AS inet))")
                .param("ip", ip, Types.VARCHAR)
                .query(String.class)
                .single();
    }

    /**
     * Canonicaliza un {@link JsonNode} pasándolo por {@code jsonb::text} en PostgreSQL para que
     * la normalización numérica (p. ej. exponentes {@code 1.0E10} a {@code 10000000000}) coincida
     * exactamente con el valor que leerá la verificación posterior.
     *
     * @param node nodo JSON a normalizar, o {@code null}
     * @return nodo JSON reparseado desde {@code jsonb::text}, o {@code null} si la entrada es nula
     */
    public JsonNode canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = AuditHasher.toJsonString(node);
        String normalized = jdbc.sql("SELECT CAST(:json AS jsonb)::text")
                .param("json", raw, Types.VARCHAR)
                .query(String.class)
                .single();
        return AuditHasher.parseJson(normalized);
    }

    /**
     * Obtiene el hash del último registro insertado en {@code audit_log}.
     *
     * @return hash del último registro, o vacío si la bitácora está vacía
     */
    public Optional<String> findLastHash() {
        return jdbc.sql("SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .optional();
    }

    /**
     * Obtiene el {@code username} vigente de un usuario para guardarlo como copia legible.
     *
     * @param userId id del usuario en {@code users}
     * @return nombre de usuario si existe
     */
    public Optional<String> findUsernameById(Long userId) {
        return jdbc.sql("SELECT username FROM users WHERE id = :userId")
                .param("userId", userId, Types.BIGINT)
                .query(String.class)
                .optional();
    }

    /**
     * Inserta un nuevo registro inmutable en {@code audit_log}.
     *
     * @param entry registro completo con {@code prevHash} y {@code hash} calculados
     * @return identificador autogenerado de la fila insertada
     */
    public long insert(AuditLogEntry entry) {
        OffsetDateTime occurredAtUtc = OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC);
        String oldJson = AuditHasher.toJsonString(entry.oldValue());
        String newJson = AuditHasher.toJsonString(entry.newValue());

        Long id = jdbc.sql("""
                        INSERT INTO audit_log (
                            occurred_at, user_id, username, store_id, terminal_id, ip,
                            action, entity_type, entity_id, old_value, new_value,
                            reason, authorization_id, trace_id, prev_hash, hash
                        ) VALUES (
                            :occurredAt, :userId, :username, :storeId, :terminalId, CAST(:ip AS inet),
                            :action, :entityType, :entityId, CAST(:oldValue AS jsonb), CAST(:newValue AS jsonb),
                            :reason, :authorizationId, :traceId, :prevHash, :hash
                        )
                        RETURNING id
                        """)
                .param("occurredAt", occurredAtUtc, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("userId", entry.userId(), Types.BIGINT)
                .param("username", entry.username(), Types.VARCHAR)
                .param("storeId", entry.storeId(), Types.BIGINT)
                .param("terminalId", entry.terminalId(), Types.BIGINT)
                .param("ip", entry.ip(), Types.VARCHAR)
                .param("action", entry.action(), Types.VARCHAR)
                .param("entityType", entry.entityType(), Types.VARCHAR)
                .param("entityId", entry.entityId(), Types.VARCHAR)
                .param("oldValue", oldJson, Types.VARCHAR)
                .param("newValue", newJson, Types.VARCHAR)
                .param("reason", entry.reason(), Types.VARCHAR)
                .param("authorizationId", entry.authorizationId(), Types.BIGINT)
                .param("traceId", entry.traceId(), Types.VARCHAR)
                .param("prevHash", entry.prevHash(), Types.CHAR)
                .param("hash", entry.hash(), Types.CHAR)
                .query(Long.class)
                .single();
        return id;
    }

    /**
     * Busca un registro por su identificador, aplicando restricción por locales del usuario cuando
     * no tiene acceso global.
     *
     * @param id identificador del registro
     * @param scopedUserId id del usuario para filtrar por {@code user_stores}, o {@code null} si
     *        tiene acceso a todos los locales
     * @return registro encontrado, o vacío si no existe o no pertenece a sus locales
     */
    public Optional<AuditLogEntry> findById(long id, Long scopedUserId) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM audit_log WHERE id = :id");
        if (scopedUserId != null) {
            sql.append(" AND store_id IN (SELECT store_id FROM user_stores WHERE user_id = :scopedUserId)");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("id", id, Types.BIGINT);
        if (scopedUserId != null) {
            spec = spec.param("scopedUserId", scopedUserId, Types.BIGINT);
        }
        return spec.query(this::mapRow).optional();
    }

    /**
     * Consulta paginada de {@code audit_log} con filtros dinámicos y orden validado.
     *
     * @param filter filtros opcionales de búsqueda
     * @param scopedUserId id del usuario para restringir a sus locales, o {@code null} si ve todos
     * @param pageable paginación y orden ya validados
     * @return página de registros encontrados
     */
    public Page<AuditLogEntry> search(AuditLogFilter filter, Long scopedUserId, Pageable pageable) {
        List<String> predicates = new ArrayList<>();
        List<NamedParam> params = new ArrayList<>();

        if (filter.from() != null) {
            predicates.add("occurred_at >= :from");
            params.add(new NamedParam("from",
                    OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE));
        }
        if (filter.to() != null) {
            predicates.add("occurred_at <= :to");
            params.add(new NamedParam("to",
                    OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE));
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            predicates.add("action = :action");
            params.add(new NamedParam("action", filter.action().trim(), Types.VARCHAR));
        }
        if (filter.entityType() != null && !filter.entityType().isBlank()) {
            predicates.add("entity_type = :entityType");
            params.add(new NamedParam("entityType", filter.entityType().trim(), Types.VARCHAR));
        }
        if (filter.entityId() != null && !filter.entityId().isBlank()) {
            predicates.add("entity_id = :entityId");
            params.add(new NamedParam("entityId", filter.entityId().trim(), Types.VARCHAR));
        }
        if (filter.userId() != null) {
            predicates.add("user_id = :userId");
            params.add(new NamedParam("userId", filter.userId(), Types.BIGINT));
        }
        if (filter.storeId() != null) {
            predicates.add("store_id = :storeId");
            params.add(new NamedParam("storeId", filter.storeId(), Types.BIGINT));
        }
        if (scopedUserId != null) {
            predicates.add("store_id IN (SELECT store_id FROM user_stores WHERE user_id = :scopedUserId)");
            params.add(new NamedParam("scopedUserId", scopedUserId, Types.BIGINT));
        }

        String whereClause = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        JdbcClient.StatementSpec countSpec = jdbc.sql("SELECT COUNT(*) FROM audit_log" + whereClause);
        for (NamedParam param : params) {
            countSpec = countSpec.param(param.name(), param.value(), param.sqlType());
        }
        long total = countSpec.query(Long.class).single();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderClause = buildOrderBy(pageable.getSort());
        String dataSql = "SELECT " + SELECT_COLUMNS + " FROM audit_log" + whereClause
                + orderClause + " LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec dataSpec = jdbc.sql(dataSql);
        for (NamedParam param : params) {
            dataSpec = dataSpec.param(param.name(), param.value(), param.sqlType());
        }
        dataSpec = dataSpec
                .param("limit", pageable.getPageSize(), Types.INTEGER)
                .param("offset", pageable.getOffset(), Types.BIGINT);

        List<AuditLogEntry> content = dataSpec.query(this::mapRow).list();
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Obtiene el hash de la fila inmediatamente anterior a {@code fromId}, para anclar la
     * verificación cuando se audita un subrango de la cadena.
     *
     * @param fromId límite inferior inclusivo del rango a verificar
     * @return hash de la fila previa, o vacío si {@code fromId} es el inicio de la tabla
     */
    public Optional<String> findHashBefore(long fromId) {
        return jdbc.sql("SELECT hash FROM audit_log WHERE id < :fromId ORDER BY id DESC LIMIT 1")
                .param("fromId", fromId, Types.BIGINT)
                .query(String.class)
                .optional();
    }

    /**
     * Lee un lote ordenado por {@code id ASC} para la verificación incremental de la cadena.
     *
     * @param minIdInclusive cota inferior inclusiva (incluso para paginación por keyset)
     * @param toIdInclusive cota superior inclusiva, o {@code null} si no hay tope
     * @param limit tamaño máximo del lote
     * @return filas ordenadas ascendentemente por {@code id}
     */
    public List<AuditLogEntry> findBatchForVerification(long minIdInclusive, Long toIdInclusive, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM audit_log WHERE id >= :minId");
        if (toIdInclusive != null) {
            sql.append(" AND id <= :toId");
        }
        sql.append(" ORDER BY id ASC LIMIT :limit");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("minId", minIdInclusive, Types.BIGINT)
                .param("limit", limit, Types.INTEGER);
        if (toIdInclusive != null) {
            spec = spec.param("toId", toIdInclusive, Types.BIGINT);
        }
        return spec.query(this::mapRow).list();
    }

    private static String buildOrderBy(Sort sort) {
        List<String> clauses = new ArrayList<>();
        boolean hasId = false;
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Columna de orden no permitida: " + order.getProperty());
            }
            if ("id".equals(column)) {
                hasId = true;
            }
            clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        if (clauses.isEmpty()) {
            return " ORDER BY occurred_at DESC, id DESC";
        }
        if (!hasId) {
            clauses.add("id DESC");
        }
        return " ORDER BY " + String.join(", ", clauses);
    }

    private AuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
        return new AuditLogEntry(
                rs.getLong("id"),
                occurredAt.toInstant().truncatedTo(ChronoUnit.MICROS),
                rs.getObject("user_id", Long.class),
                rs.getString("username"),
                rs.getObject("store_id", Long.class),
                rs.getObject("terminal_id", Long.class),
                rs.getString("ip_text"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                AuditHasher.parseJson(rs.getString("old_value_text")),
                AuditHasher.parseJson(rs.getString("new_value_text")),
                rs.getString("reason"),
                rs.getObject("authorization_id", Long.class),
                rs.getString("trace_id"),
                rs.getString("prev_hash"),
                rs.getString("hash")
        );
    }

    private record NamedParam(String name, Object value, int sqlType) {
    }
}
