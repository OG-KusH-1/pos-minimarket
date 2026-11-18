package cl.minimarket.pos.security.roles;

import cl.minimarket.pos.security.RoleLimit;
import cl.minimarket.pos.security.RoleLimitApi;
import cl.minimarket.pos.security.RoleLimitCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Implementación de {@link RoleLimitApi} que resuelve los límites efectivos de un usuario
 * aplicando la semántica <em>fail-closed</em> de {@code ROLES_PERMISOS.md} D.3.
 */
@Service
public class RoleLimitService implements RoleLimitApi {

    private final RoleQueryRepository roleQueryRepository;

    /**
     * @param roleQueryRepository repositorio de consultas sobre {@code role_limits}
     */
    public RoleLimitService(RoleQueryRepository roleQueryRepository) {
        this.roleQueryRepository = roleQueryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RoleLimit limitFor(Long userId, RoleLimitCode code) {
        if (userId == null || code == null) {
            return RoleLimit.zero();
        }
        return roleQueryRepository.findEffectiveLimit(userId, code);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<RoleLimitCode, RoleLimit> limitsFor(Long userId) {
        if (userId == null) {
            Map<RoleLimitCode, RoleLimit> zeros = new EnumMap<>(RoleLimitCode.class);
            for (RoleLimitCode code : RoleLimitCode.values()) {
                zeros.put(code, RoleLimit.zero());
            }
            return Map.copyOf(zeros);
        }
        return Map.copyOf(roleQueryRepository.findAllEffectiveLimits(userId));
    }
}
