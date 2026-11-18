package cl.minimarket.pos.organization.application;

import cl.minimarket.pos.organization.SettingScope;
import cl.minimarket.pos.organization.SettingsApi;
import cl.minimarket.pos.organization.domain.Setting;
import cl.minimarket.pos.organization.domain.SettingCatalog;
import cl.minimarket.pos.organization.domain.SettingRepository;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Casos de uso de parámetros de configuración ({@code settings}) e implementación de
 * {@link SettingsApi}.
 *
 * <p>Aplica precedencia {@code STORE} sobre {@code GLOBAL} y valida tipo, rango y alcance según
 * {@link SettingCatalog}.
 */
@Service
public class SettingsService implements SettingsApi {

    private static final List<String> EFFECTIVE_PREFIXES = List.of("pos.", "ticket.", "alerts.", "cash.");

    private final SettingRepository repository;
    private final JsonMapper jsonMapper;
    private final OrganizationAuditor auditor;

    public SettingsService(SettingRepository repository, JsonMapper jsonMapper, OrganizationAuditor auditor) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.auditor = auditor;
    }

    /** Vista de un parámetro con su valor JSON ya parseado. */
    public record SettingView(
            String key,
            SettingScope scope,
            Long storeId,
            JsonNode value,
            cl.minimarket.pos.organization.SettingValueType valueType,
            String description
    ) {
    }

    @Override
    @Transactional(readOnly = true)
    public <T> T get(String key, Long storeId, Class<T> type) {
        Setting setting = resolveEffectiveSetting(key, storeId);
        JsonNode node = parseJson(setting.getValue());
        if (JsonNode.class.equals(type)) {
            return type.cast(node);
        }
        try {
            return jsonMapper.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("No se pudo convertir el parámetro '" + key + "' a " + type.getSimpleName(), e);
        }
    }

    /** Lista parámetros filtrando opcionalmente por alcance y prefijo de clave. */
    @Transactional(readOnly = true)
    public List<SettingView> list(SettingScope scope, String prefix) {
        Long storeId = StoreContext.require().storeId();
        List<Setting> collected = new ArrayList<>();
        if (scope == null || scope == SettingScope.GLOBAL) {
            collected.addAll(repository.findByScopeOrderByKeyAsc(SettingScope.GLOBAL));
        }
        if (scope == null || scope == SettingScope.STORE) {
            collected.addAll(repository.findByScopeAndStoreIdOrderByKeyAsc(SettingScope.STORE, storeId));
        }
        return collected.stream()
                .filter(s -> prefix == null || prefix.isBlank() || s.getKey().startsWith(prefix))
                .map(this::toView)
                .toList();
    }

    /**
     * Devuelve el mapa efectivo ({@code STORE} sobre {@code GLOBAL}) para las claves operativas
     * {@code pos.*}, {@code ticket.*}, {@code alerts.*} y {@code cash.*}.
     */
    @Transactional(readOnly = true)
    public Map<String, JsonNode> getEffectiveForCurrentStore() {
        Long storeId = StoreContext.require().storeId();
        Map<String, JsonNode> result = new LinkedHashMap<>();

        for (Setting global : repository.findByScopeOrderByKeyAsc(SettingScope.GLOBAL)) {
            if (isEffectiveKey(global.getKey())) {
                result.put(global.getKey(), parseJson(global.getValue()));
            }
        }
        for (Setting storeSetting : repository.findByScopeAndStoreIdOrderByKeyAsc(SettingScope.STORE, storeId)) {
            if (isEffectiveKey(storeSetting.getKey())) {
                result.put(storeSetting.getKey(), parseJson(storeSetting.getValue()));
            }
        }
        return result;
    }

    /**
     * Actualiza (o crea el override de local) un parámetro validando permisos, alcance, tipo y rango.
     */
    @Transactional
    public SettingView update(String key, SettingScope scope, JsonNode value) {
        SettingCatalog.Definition def = SettingCatalog.require(key);
        def.validateScope(scope);
        def.validateValue(value);

        if (scope == SettingScope.GLOBAL && !hasAllStoreAccess()) {
            throw new AccessDeniedException("Solo un administrador puede modificar parámetros GLOBAL.");
        }

        Long targetStoreId = scope == SettingScope.STORE ? StoreContext.require().storeId() : null;
        Optional<Setting> existing = scope == SettingScope.GLOBAL
                ? repository.findByKeyAndScopeAndStoreIdIsNull(key, SettingScope.GLOBAL)
                : repository.findByKeyAndScopeAndStoreId(key, SettingScope.STORE, targetStoreId);

        JsonNode oldValue = existing.map(s -> parseJson(s.getValue())).orElse(null);

        Setting entity = existing.orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(key);
            created.setScope(scope);
            created.setStoreId(targetStoreId);
            created.setValueType(def.valueType());
            created.setDescription(def.description());
            return created;
        });

        entity.setValue(serializeJson(value));
        Setting saved = repository.save(entity);
        auditor.settingChanged(key, scope.name(), targetStoreId, oldValue, value);
        return toView(saved);
    }

    private Setting resolveEffectiveSetting(String key, Long storeId) {
        if (storeId != null) {
            Optional<Setting> storeSetting = repository.findByKeyAndScopeAndStoreId(key, SettingScope.STORE, storeId);
            if (storeSetting.isPresent()) {
                return storeSetting.get();
            }
        }
        return repository.findByKeyAndScopeAndStoreIdIsNull(key, SettingScope.GLOBAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Parámetro de configuración no encontrado: " + key));
    }

    private static boolean isEffectiveKey(String key) {
        return EFFECTIVE_PREFIXES.stream().anyMatch(key::startsWith);
    }

    private static boolean hasAllStoreAccess() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.SECURITY_STORE_ALL_ACCESS::equals);
    }

    private SettingView toView(Setting s) {
        return new SettingView(
                s.getKey(),
                s.getScope(),
                s.getStoreId(),
                parseJson(s.getValue()),
                s.getValueType(),
                s.getDescription()
        );
    }

    private JsonNode parseJson(String raw) {
        try {
            return jsonMapper.readTree(raw);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON inválido almacenado en settings", e);
        }
    }

    private String serializeJson(JsonNode node) {
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalStateException("Error serializando valor de setting", e);
        }
    }
}
