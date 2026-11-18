package cl.minimarket.pos.security.users;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repositorio JDBC para consultas filtradas de usuarios, gestión de {@code user_roles},
 * {@code user_stores} y sesiones activas en {@code refresh_tokens}.
 */
@Repository
public class UserQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "u.id",
            "username", "u.username",
            "fullName", "u.full_name",
            "active", "u.active",
            "createdAt", "u.created_at"
    );

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public UserQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fila base proyectada para el listado paginado de usuarios.
     */
    public record UserRow(
            Long id,
            String username,
            String fullName,
            boolean active,
            Instant lockedUntil
    ) {
    }

    /**
     * Busca usuarios paginados aplicando filtros opcionales y restricción por locales visibles
     * ({@code visibleStoreIds != null} cuando el actor no tiene {@code security:store:all-access}).
     */
    public Page<UserSummary> searchUsers(
            String q,
            Long roleId,
            Long storeId,
            Boolean active,
            Boolean locked,
            Set<Long> visibleStoreIds,
            Instant now,
            Pageable pageable
    ) {
        if (visibleStoreIds != null && visibleStoreIds.isEmpty()) {
            return Page.empty(pageable);
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (q != null && !q.isBlank()) {
            where.append("""
                     AND (
                        f_unaccent(lower(u.username)) LIKE f_unaccent(lower(:qPattern))
                        OR f_unaccent(lower(u.full_name)) LIKE f_unaccent(lower(:qPattern))
                        OR (u.email IS NOT NULL AND lower(u.email) LIKE lower(:qPattern))
                        OR (u.rut IS NOT NULL AND lower(u.rut) LIKE lower(:qPattern))
                    )""");
            params.put("qPattern", "%" + q.trim() + "%");
        }
        if (roleId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role_id = :roleId)");
            params.put("roleId", roleId);
        }
        if (storeId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM user_stores us WHERE us.user_id = u.id AND us.store_id = :storeId)");
            params.put("storeId", storeId);
        }
        if (visibleStoreIds != null) {
            where.append(" AND EXISTS (SELECT 1 FROM user_stores usv WHERE usv.user_id = u.id AND usv.store_id IN (:visibleStoreIds))");
            params.put("visibleStoreIds", visibleStoreIds);
        }
        if (active != null) {
            where.append(" AND u.active = :active");
            params.put("active", active);
        }
        if (locked != null) {
            params.put("now", Timestamp.from(now));
            if (locked) {
                where.append(" AND u.locked_until IS NOT NULL AND u.locked_until > :now");
            } else {
                where.append(" AND (u.locked_until IS NULL OR u.locked_until <= :now)");
            }
        }

        Long total = applyParams(jdbc.sql("SELECT COUNT(*) FROM users u" + where), params)
                .query(Long.class)
                .single();
        long totalElements = total != null ? total : 0L;
        if (totalElements == 0L) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        List<UserRow> rows = applyParams(
                jdbc.sql("SELECT u.id, u.username, u.full_name, u.active, u.locked_until FROM users u"
                        + where + orderBy + " LIMIT :limit OFFSET :offset"),
                pageParams
        ).query((rs, rowNum) -> {
            Timestamp lockedTs = rs.getTimestamp("locked_until");
            return new UserRow(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("full_name"),
                    rs.getBoolean("active"),
                    lockedTs != null ? lockedTs.toInstant() : null
            );
        }).list();

        List<Long> userIds = rows.stream().map(UserRow::id).toList();
        Map<Long, List<String>> roleCodesByUser = findRoleCodesByUserIds(userIds);

        List<UserSummary> content = rows.stream()
                .map(row -> new UserSummary(
                        row.id(),
                        row.username(),
                        row.fullName(),
                        row.active(),
                        row.lockedUntil() != null && row.lockedUntil().isAfter(now),
                        roleCodesByUser.getOrDefault(row.id(), List.of())
                ))
                .toList();

        return new PageImpl<>(content, pageable, totalElements);
    }

    /**
     * Obtiene los roles asignados a un usuario como {@link RoleSummary}.
     */
    public List<RoleSummary> findRolesByUserId(Long userId) {
        return jdbc.sql("""
                        SELECT r.id, r.code, r.name
                        FROM roles r
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new RoleSummary(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name")
                ))
                .list();
    }

    /**
     * Obtiene los códigos de rol agrupados por {@code user_id}.
     */
    public Map<Long, List<String>> findRoleCodesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT ur.user_id, r.code
                        FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id IN (:userIds)
                        ORDER BY ur.user_id, r.code
                        """)
                .param("userIds", userIds)
                .query((rs, rowNum) -> {
                    long userId = rs.getLong("user_id");
                    String code = rs.getString("code");
                    result.computeIfAbsent(userId, k -> new ArrayList<>()).add(code);
                    return null;
                })
                .list();
        return result;
    }

    /**
     * Obtiene los ids de locales asignados en {@code user_stores} para un usuario.
     */
    public Set<Long> findAssignedStoreIds(Long userId) {
        List<Long> ids = jdbc.sql("""
                        SELECT store_id
                        FROM user_stores
                        WHERE user_id = :userId
                        ORDER BY store_id
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();
        return new LinkedHashSet<>(ids);
    }

    /**
     * Obtiene los códigos de permisos efectivos para un conjunto de {@code roleIds}.
     */
    public Set<String> findPermissionCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        List<String> codes = jdbc.sql("""
                        SELECT DISTINCT p.code
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        WHERE rp.role_id IN (:roleIds)
                        ORDER BY p.code
                        """)
                .param("roleIds", roleIds)
                .query(String.class)
                .list();
        return new LinkedHashSet<>(codes);
    }

    /**
     * Reemplaza los roles asignados a un usuario.
     */
    public void replaceUserRoles(Long userId, Collection<Long> roleIds) {
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        for (Long roleId : roleIds) {
            jdbc.sql("""
                            INSERT INTO user_roles (user_id, role_id)
                            VALUES (:userId, :roleId)
                            """)
                    .param("userId", userId)
                    .param("roleId", roleId)
                    .update();
        }
    }

    /**
     * Reemplaza los locales asignados a un usuario y marca {@code is_default} en {@code defaultStoreId}.
     */
    public void replaceUserStores(Long userId, Collection<Long> storeIds, Long defaultStoreId, Instant now) {
        jdbc.sql("DELETE FROM user_stores WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        for (Long storeId : storeIds) {
            boolean isDefault = defaultStoreId != null && defaultStoreId.equals(storeId);
            jdbc.sql("""
                            INSERT INTO user_stores (user_id, store_id, is_default, updated_at)
                            VALUES (:userId, :storeId, :isDefault, :updatedAt)
                            """)
                    .param("userId", userId)
                    .param("storeId", storeId)
                    .param("isDefault", isDefault)
                    .param("updatedAt", Timestamp.from(now))
                    .update();
        }
    }

    /**
     * Cuenta cuántos usuarios activos poseen el rol {@code ADMIN}.
     */
    public long countActiveUsersWithRoleCode(String roleCode) {
        Long count = jdbc.sql("""
                        SELECT COUNT(DISTINCT u.id)
                        FROM users u
                        JOIN user_roles ur ON ur.user_id = u.id
                        JOIN roles r ON r.id = ur.role_id
                        WHERE u.active = true
                          AND r.code = :roleCode
                        """)
                .param("roleCode", roleCode)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Lista las sesiones activas (refresh tokens no revocados y vigentes) de un usuario.
     */
    public List<SessionResponse> findActiveSessionsByUserId(Long userId, Instant now) {
        return jdbc.sql("""
                        SELECT id, issued_at, expires_at, host(ip) AS ip_addr, user_agent, terminal_id
                        FROM refresh_tokens
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                          AND expires_at > :now
                          AND absolute_expires_at > :now
                        ORDER BY issued_at DESC, id DESC
                        """)
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> {
                    long termVal = rs.getLong("terminal_id");
                    Long terminalId = rs.wasNull() ? null : termVal;
                    return new SessionResponse(
                            rs.getLong("id"),
                            rs.getTimestamp("issued_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("ip_addr"),
                            rs.getString("user_agent"),
                            terminalId
                    );
                })
                .list();
    }

    /**
     * Revoca todos los refresh tokens activos de un usuario y devuelve la cantidad afectada.
     */
    public int revokeAllActiveSessions(Long userId, Instant revokedAt, String reason) {
        return jdbc.sql("""
                        UPDATE refresh_tokens
                        SET revoked_at = :revokedAt,
                            revoked_reason = :reason
                        WHERE user_id = :userId
                          AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("reason", reason)
                .param("userId", userId)
                .update();
    }

    private static JdbcClient.StatementSpec applyParams(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        JdbcClient.StatementSpec current = spec;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            current = current.param(entry.getKey(), entry.getValue());
        }
        return current;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY u.id ASC";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY u.id ASC";
        }
        return " ORDER BY " + String.join(", ", clauses);
    }
}
