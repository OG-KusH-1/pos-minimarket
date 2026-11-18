package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio JDBC para operaciones sobre {@code role_permissions}, conteos de {@code user_roles}
 * y resolución eficiente de límites efectivos por usuario en {@code role_limits}.
 */
@Repository
public class RoleQueryRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc cliente JDBC de Spring
     */
    public RoleQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Obtiene los códigos de permiso asignados a un rol, ordenados alfabéticamente.
     */
    public List<String> findPermissionCodesByRoleId(Long roleId) {
        return jdbc.sql("""
                        SELECT p.code
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        WHERE rp.role_id = :roleId
                        ORDER BY p.code
                        """)
                .param("roleId", roleId)
                .query(String.class)
                .list();
    }

    /**
     * Obtiene en un solo query los códigos de permiso agrupados por {@code role_id}.
     */
    public Map<Long, List<String>> findPermissionCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT rp.role_id, p.code
                        FROM role_permissions rp
                        JOIN permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id IN (:roleIds)
                        ORDER BY rp.role_id, p.code
                        """)
                .param("roleIds", roleIds)
                .query((rs, rowNum) -> {
                    long roleId = rs.getLong("role_id");
                    String code = rs.getString("code");
                    result.computeIfAbsent(roleId, k -> new java.util.ArrayList<>()).add(code);
                    return null;
                })
                .list();
        return result;
    }

    /**
     * Reemplaza la colección completa de permisos de un rol (DELETE + INSERT dentro de la tx).
     */
    public void replaceRolePermissions(Long roleId, List<Long> permissionIds) {
        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();

        for (Long permissionId : permissionIds) {
            jdbc.sql("""
                            INSERT INTO role_permissions (role_id, permission_id)
                            VALUES (:roleId, :permissionId)
                            """)
                    .param("roleId", roleId)
                    .param("permissionId", permissionId)
                    .update();
        }
    }

    /**
     * Cuenta cuántos usuarios tienen asignado el rol indicado.
     */
    public long countUsersByRoleId(Long roleId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM user_roles
                        WHERE role_id = :roleId
                        """)
                .param("roleId", roleId)
                .query(Long.class)
                .single();
        return count != null ? count : 0L;
    }

    /**
     * Obtiene en un solo query la cantidad de usuarios asignados por cada {@code role_id}.
     */
    public Map<Long, Long> countUsersByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT role_id, COUNT(*) AS cnt
                        FROM user_roles
                        WHERE role_id IN (:roleIds)
                        GROUP BY role_id
                        """)
                .param("roleIds", roleIds)
                .query((rs, rowNum) -> {
                    result.put(rs.getLong("role_id"), rs.getLong("cnt"));
                    return null;
                })
                .list();
        return result;
    }

    /**
     * Resuelve el límite efectivo de un usuario para un código específico aplicando la semántica
     * <em>fail-closed</em> (D.3): si no hay filas devuelve {@link RoleLimit#zero()}, si algún rol
     * tiene {@code NULL} devuelve {@link RoleLimit#unlimited()}, y en otro caso el máximo valor.
     */
    public RoleLimit findEffectiveLimit(Long userId, RoleLimitCode code) {
        List<BigDecimal> values = jdbc.sql("""
                        SELECT rl.value
                        FROM role_limits rl
                        JOIN user_roles ur ON ur.role_id = rl.role_id
                        WHERE ur.user_id = :userId
                          AND rl.limit_code = :limitCode
                        """)
                .param("userId", userId)
                .param("limitCode", code.name())
                .query((rs, rowNum) -> rs.getBigDecimal("value"))
                .list();

        return aggregateValues(values);
    }

    /**
     * Resuelve todos los límites efectivos de un usuario para todos los códigos de {@link RoleLimitCode}.
     */
    public Map<RoleLimitCode, RoleLimit> findAllEffectiveLimits(Long userId) {
        Map<RoleLimitCode, List<BigDecimal>> grouped = new EnumMap<>(RoleLimitCode.class);
        jdbc.sql("""
                        SELECT rl.limit_code, rl.value
                        FROM role_limits rl
                        JOIN user_roles ur ON ur.role_id = rl.role_id
                        WHERE ur.user_id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    String rawCode = rs.getString("limit_code");
                    RoleLimitCode code;
                    try {
                        code = RoleLimitCode.valueOf(rawCode);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                    BigDecimal val = rs.getBigDecimal("value");
                    grouped.computeIfAbsent(code, k -> new java.util.ArrayList<>()).add(val);
                    return null;
                })
                .list();

        Map<RoleLimitCode, RoleLimit> result = new EnumMap<>(RoleLimitCode.class);
        for (RoleLimitCode code : RoleLimitCode.values()) {
            List<BigDecimal> values = grouped.get(code);
            result.put(code, values == null ? RoleLimit.zero() : aggregateValues(values));
        }
        return result;
    }

    private static RoleLimit aggregateValues(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return RoleLimit.zero();
        }
        BigDecimal max = BigDecimal.ZERO;
        for (BigDecimal val : values) {
            if (val == null) {
                return RoleLimit.unlimited();
            }
            if (val.compareTo(max) > 0) {
                max = val;
            }
        }
        return RoleLimit.limited(max);
    }
}
