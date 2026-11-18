package cl.minimarket.pos.organization.domain;

import cl.minimarket.pos.organization.SettingScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio de parámetros de configuración ({@code settings}).
 */
public interface SettingRepository extends JpaRepository<Setting, Long> {

    /** Busca el parámetro global por clave. */
    Optional<Setting> findByKeyAndScopeAndStoreIdIsNull(String key, SettingScope scope);

    /** Busca el override de un local por clave y {@code storeId}. */
    Optional<Setting> findByKeyAndScopeAndStoreId(String key, SettingScope scope, Long storeId);

    /** Lista todos los parámetros globales ordenados por clave. */
    List<Setting> findByScopeOrderByKeyAsc(SettingScope scope);

    /** Lista todos los parámetros de un local ordenados por clave. */
    List<Setting> findByScopeAndStoreIdOrderByKeyAsc(SettingScope scope, Long storeId);
}
