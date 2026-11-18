package cl.minimarket.pos.organization.application;

import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreCreatedEvent;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.organization.StoreUsageGuard;
import cl.minimarket.pos.organization.domain.Company;
import cl.minimarket.pos.organization.domain.CompanyRepository;
import cl.minimarket.pos.organization.domain.Store;
import cl.minimarket.pos.organization.domain.StoreRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Casos de uso de locales ({@code stores}) e implementación de {@link StoreApi}.
 */
@Service
public class StoreService implements StoreApi {

    private final StoreRepository storeRepository;
    private final CompanyRepository companyRepository;
    private final StoreUsageGuard storeUsageGuard;
    private final ApplicationEventPublisher eventPublisher;
    private final OrganizationAuditor auditor;
    private final ObjectProvider<StoreAccessValidator> storeAccessValidatorProvider;

    public StoreService(StoreRepository storeRepository,
                        CompanyRepository companyRepository,
                        StoreUsageGuard storeUsageGuard,
                        ApplicationEventPublisher eventPublisher,
                        OrganizationAuditor auditor,
                        ObjectProvider<StoreAccessValidator> storeAccessValidatorProvider) {
        this.storeRepository = storeRepository;
        this.companyRepository = companyRepository;
        this.storeUsageGuard = storeUsageGuard;
        this.eventPublisher = eventPublisher;
        this.auditor = auditor;
        this.storeAccessValidatorProvider = storeAccessValidatorProvider;
    }

    /** Comando de creación o edición de un local. */
    public record StoreCommand(
            String code,
            String name,
            String address,
            String commune,
            String city,
            String phone,
            String siiBranchCode
    ) {
    }

    @Override
    @Transactional(readOnly = true)
    public StoreInfo get(Long id) {
        return find(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoreInfo> find(Long id) {
        return storeRepository.findById(id).map(StoreService::toInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> activeStoreIds() {
        return storeRepository.findActiveIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreInfo> findActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return storeRepository.findByIdInAndActive(ids, true, PageRequest.of(0, ids.size(), Sort.by("id")))
                .stream()
                .map(StoreService::toInfo)
                .toList();
    }

    /**
     * Lista los locales visibles para el usuario actual (todos si tiene
     * {@code security:store:all-access}; solo los asignados en caso contrario).
     */
    @Transactional(readOnly = true)
    public Page<Store> listForCurrentUser(Boolean active, Pageable pageable) {
        if (hasAllStoreAccess()) {
            return active == null
                    ? storeRepository.findAll(pageable)
                    : storeRepository.findByActive(active, pageable);
        }
        Long userId = CurrentUserId.find().orElse(null);
        if (userId == null) {
            return Page.empty(pageable);
        }
        List<Long> assigned = storeRepository.findAssignedStoreIdsByUserId(userId);
        if (assigned.isEmpty()) {
            return Page.empty(pageable);
        }
        return active == null
                ? storeRepository.findByIdIn(assigned, pageable)
                : storeRepository.findByIdInAndActive(assigned, active, pageable);
    }

    /** Obtiene un local verificando acceso del usuario actual. */
    @Transactional(readOnly = true)
    public Store getForCurrentUser(Long id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado."));
        requireStoreAccess(id);
        return store;
    }

    /** Crea un local nuevo y publica {@link StoreCreatedEvent}. */
    @Transactional
    public Store create(StoreCommand cmd) {
        String code = cmd.code().trim();
        if (storeRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe un local con el código " + code + ".");
        }
        Company company = companyRepository.findTopByOrderByIdAsc()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Debe configurar la empresa antes de crear locales."));

        Store store = new Store();
        store.setCompanyId(company.getId());
        applyFields(store, cmd, code);
        store.setActive(true);

        Store saved = storeRepository.save(store);
        eventPublisher.publishEvent(new StoreCreatedEvent(saved.getId(), saved.getCode(), saved.getName()));
        auditor.storeChanged(saved.getId(), "CREATED", null, snapshot(saved));
        return saved;
    }

    /** Actualiza los datos de un local existente. */
    @Transactional
    public Store update(Long id, StoreCommand cmd) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado."));
        String code = cmd.code().trim();
        if (storeRepository.existsByCodeAndIdNot(code, id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE, "Ya existe un local con el código " + code + ".");
        }
        Map<String, Object> oldSnapshot = snapshot(store);
        applyFields(store, cmd, code);
        Store saved = storeRepository.save(store);
        auditor.storeChanged(saved.getId(), "UPDATED", oldSnapshot, snapshot(saved));
        return saved;
    }

    /** Desactiva un local previa validación con {@link StoreUsageGuard}. */
    @Transactional
    public void deactivate(Long id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado."));
        storeUsageGuard.assertCanDeactivate(id);
        if (store.isActive()) {
            Map<String, Object> oldSnapshot = snapshot(store);
            store.setActive(false);
            Store saved = storeRepository.save(store);
            auditor.storeChanged(saved.getId(), "DEACTIVATED", oldSnapshot, snapshot(saved));
        }
    }

    /** Reactiva un local. */
    @Transactional
    public void activate(Long id) {
        Store store = storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado."));
        if (!store.isActive()) {
            Map<String, Object> oldSnapshot = snapshot(store);
            store.setActive(true);
            Store saved = storeRepository.save(store);
            auditor.storeChanged(saved.getId(), "ACTIVATED", oldSnapshot, snapshot(saved));
        }
    }

    /** Verifica que el usuario actual tenga acceso al local indicado. */
    public void requireStoreAccess(Long storeId) {
        if (hasAllStoreAccess()) {
            return;
        }
        Long userId = CurrentUserId.find()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local."));
        StoreAccessValidator validator = storeAccessValidatorProvider.getIfAvailable();
        if (validator != null) {
            validator.requireAccess(userId, storeId, null);
            return;
        }
        List<Long> assigned = storeRepository.findAssignedStoreIdsByUserId(userId);
        if (!assigned.contains(storeId)) {
            throw new BusinessException(ErrorCode.STORE_ACCESS_DENIED, "Sin acceso al local " + storeId);
        }
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

    private static void applyFields(Store store, StoreCommand cmd, String normalizedCode) {
        store.setCode(normalizedCode);
        store.setName(cmd.name().trim());
        store.setAddress(cmd.address().trim());
        store.setCommune(cmd.commune().trim());
        store.setCity(cmd.city().trim());
        store.setPhone(trimToNull(cmd.phone()));
        store.setSiiBranchCode(trimToNull(cmd.siiBranchCode()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static StoreInfo toInfo(Store s) {
        return new StoreInfo(
                s.getId(),
                s.getCode(),
                s.getName(),
                s.getAddress(),
                s.getCommune(),
                s.getCity(),
                s.getPhone(),
                s.getSiiBranchCode(),
                s.isActive()
        );
    }

    private static Map<String, Object> snapshot(Store s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", s.getCode());
        map.put("name", s.getName());
        map.put("address", s.getAddress());
        map.put("commune", s.getCommune());
        map.put("city", s.getCity());
        map.put("phone", s.getPhone());
        map.put("siiBranchCode", s.getSiiBranchCode());
        map.put("active", s.isActive());
        return map;
    }
}
