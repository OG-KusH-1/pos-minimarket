package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.security.auth.dto.StoreSummary;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de lectura directa para autenticación, permisos efectivos, locales y límites por rol.
 *
 * <p>Usa {@link JdbcClient} para tablas propias del módulo {@code security} y {@link StoreApi} para
 * consultar locales del módulo {@code organization} respetando fronteras entre módulos.
 */
@Repository
public class AuthQueryRepository {

    private final JdbcClient jdbc;
    private final StoreApi storeApi;

    /**
     * @param jdbc cliente JDBC de Spring
     * @param storeApi API pública de locales del módulo {@code organization}
     */
    public AuthQueryRepository(JdbcClient jdbc, StoreApi storeApi) {
        this.jdbc = jdbc;
        this.storeApi = storeApi;
    }

    /**
     * Obtiene los códigos de rol asignados al usuario ordenados alfabéticamente.
     */
    public List<String> findRoleCodesByUserId(Long userId) {
        return jdbc.sql("""
                        SELECT r.code
                        FROM roles r
                        JOIN user_roles ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    /**
     * Obtiene los códigos de permiso efectivos del usuario (unión de los permisos de sus roles).
     */
    public List<String> findPermissionCodesByUserId(Long userId) {
        return jdbc.sql("""
                        SELECT DISTINCT p.code
                        FROM permissions p
                        JOIN role_permissions rp ON rp.permission_id = p.id
                        JOIN user_roles ur ON ur.role_id = rp.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY p.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    /**
     * Obtiene los locales activos accesibles para el usuario.
     *
     * <p>Si {@code allAccess} es {@code true} ({@link Permissions#SECURITY_STORE_ALL_ACCESS}),
     * retorna todos los locales activos del sistema vía {@link StoreApi}; en caso contrario,
     * obtiene los ids asignados en {@code user_stores} y los resuelve vía {@link StoreApi}.
     */
    public List<StoreSummary> findAccessibleStores(Long userId, boolean allAccess) {
        List<Long> storeIds = allAccess
                ? storeApi.activeStoreIds()
                : jdbc.sql("""
                                SELECT us.store_id
                                FROM user_stores us
                                WHERE us.user_id = :userId
                                ORDER BY us.store_id
                                """)
                        .param("userId", userId)
                        .query(Long.class)
                        .list();

        return storeApi.findActiveByIds(storeIds).stream()
                .map(s -> new StoreSummary(s.id(), s.code(), s.name(), s.active()))
                .toList();
    }

    /**
     * Obtiene el identificador del local marcado por defecto en {@code user_stores}, si existe y está activo.
     */
    public Optional<Long> findDefaultStoreId(Long userId) {
        return jdbc.sql("""
                        SELECT us.store_id
                        FROM user_stores us
                        WHERE us.user_id = :userId AND us.is_default = true
                        """)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .flatMap(storeApi::find)
                .filter(StoreInfo::active)
                .map(StoreInfo::id);
    }

    /**
     * Verifica si el usuario tiene acceso a un local activo (por {@code user_stores} o {@code allAccess}).
     */
    public boolean hasStoreAccess(Long userId, Long storeId, boolean allAccess) {
        boolean storeActive = storeApi.find(storeId)
                .map(StoreInfo::active)
                .orElse(false);
        if (!storeActive) {
            return false;
        }
        if (allAccess) {
            return true;
        }
        Boolean assigned = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM user_stores us
                            WHERE us.user_id = :userId AND us.store_id = :storeId
                        )
                        """)
                .param("userId", userId)
                .param("storeId", storeId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(assigned);
    }

    /**
     * Calcula los límites efectivos del usuario combinando sus roles (el más permisivo gana;
     * {@code null} representa ilimitado según D.3).
     */
    public Map<String, BigDecimal> findEffectiveLimitsByUserId(Long userId) {
        Map<String, BigDecimal> limits = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT rl.limit_code,
                               CASE WHEN BOOL_OR(rl.value IS NULL) THEN NULL ELSE MAX(rl.value) END AS effective_value
                        FROM role_limits rl
                        JOIN user_roles ur ON ur.role_id = rl.role_id
                        WHERE ur.user_id = :userId
                        GROUP BY rl.limit_code
                        ORDER BY rl.limit_code
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    String code = rs.getString("limit_code");
                    BigDecimal value = rs.getBigDecimal("effective_value");
                    limits.put(code, value);
                    return null;
                })
                .list();
        return limits;
    }
}
