package cl.minimarket.pos.promotions.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.promotions.PromotionTargetType;
import cl.minimarket.pos.promotions.PromotionType;
import cl.minimarket.pos.promotions.domain.Promotion;
import cl.minimarket.pos.promotions.domain.PromotionItem;
import cl.minimarket.pos.promotions.domain.PromotionRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Quantity;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Casos de uso de administración y consulta POS de promociones ({@code API.md} F.2,
 * {@code MODELO_DATOS.md} B.10).
 */
@Service
public class PromotionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");

    private final PromotionRepository promotionRepository;
    private final PromotionLookup promotionLookup;
    private final ProductCatalogApi productCatalogApi;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public PromotionService(
            PromotionRepository promotionRepository,
            PromotionLookup promotionLookup,
            ProductCatalogApi productCatalogApi,
            AuditService auditService,
            BusinessClock businessClock
    ) {
        this.promotionRepository = Objects.requireNonNull(promotionRepository, "promotionRepository");
        this.promotionLookup = Objects.requireNonNull(promotionLookup, "promotionLookup");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.businessClock = Objects.requireNonNull(businessClock, "businessClock");
    }

    /**
     * Lista promociones paginadas aplicando filtros opcionales.
     */
    @Transactional(readOnly = true)
    public Page<PromotionView> list(PromotionFilter filter, Pageable pageable) {
        Specification<Promotion> spec = buildSpec(filter);
        Page<Promotion> page = promotionRepository.findAll(spec, pageable);
        Map<TargetKey, String> targetNames = resolveTargetNames(page.getContent());
        return page.map(p -> toView(p, targetNames));
    }

    /**
     * Obtiene el detalle de una promoción por id.
     */
    @Transactional(readOnly = true)
    public PromotionView get(Long id) {
        Promotion promo = findOrThrow(id);
        Map<TargetKey, String> targetNames = resolveTargetNames(List.of(promo));
        return toView(promo, targetNames);
    }

    /**
     * Crea una nueva promoción validando todas las reglas de {@code MODELO_DATOS.md} B.10
     * y registra {@code PROMOTION_CHANGED} en la auditoría.
     */
    @Transactional
    public PromotionView create(PromotionCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        validateCommand(cmd, false);

        Promotion promo = new Promotion();
        promo.setActive(true);
        applyCommand(promo, cmd);

        Promotion saved = promotionRepository.saveAndFlush(promo);
        auditService.record(new AuditEvent(
                "PROMOTION_CHANGED",
                "promotion",
                String.valueOf(saved.getId()),
                null,
                toAuditSnapshot(saved),
                null,
                null
        ));

        Map<TargetKey, String> targetNames = resolveTargetNames(List.of(saved));
        return toView(saved, targetNames);
    }

    /**
     * Actualiza una promoción existente verificando bloqueo optimista ({@code version})
     * y registra {@code PROMOTION_CHANGED} en la auditoría.
     */
    @Transactional
    public PromotionView update(Long id, PromotionCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Promotion promo = findOrThrow(id);
        if (cmd.version() == null) {
            throw validationError("version", "REQUIRED", "La versión es obligatoria al actualizar.");
        }
        if (!Objects.equals(cmd.version(), promo.getVersion())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "La promoción fue modificada por otro usuario."
            );
        }

        validateCommand(cmd, true);
        Map<String, Object> oldSnapshot = toAuditSnapshot(promo);

        applyCommand(promo, cmd);
        Promotion saved = promotionRepository.saveAndFlush(promo);

        auditService.record(new AuditEvent(
                "PROMOTION_CHANGED",
                "promotion",
                String.valueOf(saved.getId()),
                oldSnapshot,
                toAuditSnapshot(saved),
                null,
                null
        ));

        Map<TargetKey, String> targetNames = resolveTargetNames(List.of(saved));
        return toView(saved, targetNames);
    }

    /**
     * Desactiva una promoción y registra {@code PROMOTION_CHANGED}.
     */
    @Transactional
    public void deactivate(Long id) {
        setActive(id, false);
    }

    /**
     * Activa una promoción y registra {@code PROMOTION_CHANGED}.
     */
    @Transactional
    public void activate(Long id) {
        setActive(id, true);
    }

    /**
     * Lista las promociones vigentes en el local y la hora actuales según {@link BusinessClock}.
     */
    @Transactional(readOnly = true)
    public List<ActivePromotionView> listActiveForPos(Long storeId) {
        Objects.requireNonNull(storeId, "storeId");
        Instant now = businessClock.now();
        List<Promotion> candidates = promotionRepository.findCandidatesForStoreAt(storeId, now);
        return candidates.stream()
                .filter(p -> PromotionVigency.isVigentAt(
                        p.isActive(),
                        p.getValidFrom(),
                        p.getValidTo(),
                        p.getDaysOfWeek(),
                        p.getTimeFrom(),
                        p.getTimeTo(),
                        now,
                        businessClock.zone()))
                .map(p -> new ActivePromotionView(
                        p.getId(),
                        p.getName(),
                        p.getPromotionType(),
                        buildLabel(p)
                ))
                .toList();
    }

    private void setActive(Long id, boolean active) {
        Promotion promo = findOrThrow(id);
        Map<String, Object> oldSnapshot = toAuditSnapshot(promo);
        promo.setActive(active);
        Promotion saved = promotionRepository.saveAndFlush(promo);
        auditService.record(new AuditEvent(
                "PROMOTION_CHANGED",
                "promotion",
                String.valueOf(saved.getId()),
                oldSnapshot,
                toAuditSnapshot(saved),
                null,
                null
        ));
    }

    private Promotion findOrThrow(Long id) {
        Promotion promo = promotionRepository.findWithItemsById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "Promoción no encontrada: " + id
                ));
        // Inicializar storeIds dentro de la transacción
        promo.getStoreIds().size();
        return promo;
    }

    private Specification<Promotion> buildSpec(PromotionFilter filter) {
        if (filter == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        ProductSnapshot filterProduct = null;
        boolean productLookupAttempted = false;
        if (filter.productId() != null) {
            productLookupAttempted = true;
            filterProduct = productCatalogApi.findByIds(List.of(filter.productId()))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        final ProductSnapshot resolvedProduct = filterProduct;
        final boolean hasProductFilter = productLookupAttempted;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("promotionType"), filter.type()));
            }
            if (filter.vigentAt() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("validFrom"), filter.vigentAt()));
                predicates.add(cb.or(
                        cb.isNull(root.get("validTo")),
                        cb.greaterThan(root.get("validTo"), filter.vigentAt())
                ));
            }
            if (hasProductFilter) {
                query.distinct(true);
                Join<Promotion, PromotionItem> itemsJoin = root.join("items");
                List<Predicate> targetPredicates = new ArrayList<>();
                targetPredicates.add(cb.and(
                        cb.equal(itemsJoin.get("targetType"), PromotionTargetType.PRODUCT),
                        cb.equal(itemsJoin.get("productId"), filter.productId())
                ));
                if (resolvedProduct != null) {
                    if (resolvedProduct.categoryId() != null) {
                        targetPredicates.add(cb.and(
                                cb.equal(itemsJoin.get("targetType"), PromotionTargetType.CATEGORY),
                                cb.equal(itemsJoin.get("categoryId"), resolvedProduct.categoryId())
                        ));
                    }
                    if (resolvedProduct.brandId() != null) {
                        targetPredicates.add(cb.and(
                                cb.equal(itemsJoin.get("targetType"), PromotionTargetType.BRAND),
                                cb.equal(itemsJoin.get("brandId"), resolvedProduct.brandId())
                        ));
                    }
                }
                predicates.add(cb.or(targetPredicates.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private void validateCommand(PromotionCommand cmd, boolean isUpdate) {
        if (cmd.validTo() != null && !cmd.validTo().isAfter(cmd.validFrom())) {
            throw validationError("validTo", "INVALID_RANGE",
                    "La fecha de término debe ser posterior a la fecha de inicio.");
        }
        if ((cmd.timeFrom() == null) != (cmd.timeTo() == null)) {
            throw validationError("timeFrom", "INCOMPLETE_RANGE",
                    "Debe indicar hora de inicio y hora de término, o ninguna.");
        }
        if (cmd.timeFrom() != null && !cmd.timeTo().isAfter(cmd.timeFrom())) {
            throw validationError("timeTo", "INVALID_RANGE",
                    "La hora de término debe ser posterior a la hora de inicio.");
        }
        if (cmd.daysOfWeek() != null) {
            for (Integer day : cmd.daysOfWeek()) {
                if (day == null || day < 1 || day > 7) {
                    throw validationError("daysOfWeek", "OUT_OF_RANGE",
                            "Los días de la semana deben estar entre 1 (lunes) y 7 (domingo).");
                }
            }
        }
        if (cmd.maxApplicationsPerSale() != null && cmd.maxApplicationsPerSale() < 1) {
            throw validationError("maxApplicationsPerSale", "OUT_OF_RANGE",
                    "El máximo de aplicaciones por venta debe ser al menos 1.");
        }

        boolean allStores = cmd.allStores() == null || cmd.allStores();
        if (!allStores) {
            if (cmd.storeIds() == null || cmd.storeIds().isEmpty()) {
                throw validationError("storeIds", "REQUIRED",
                        "Debe indicar al menos un local cuando allStores es false.");
            }
            Set<Long> existingStores = promotionLookup.findExistingStoreIds(cmd.storeIds());
            for (Long storeId : cmd.storeIds()) {
                if (!existingStores.contains(storeId)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "Local no encontrado: " + storeId);
                }
            }
        }

        validateTypeSpecificFields(cmd);
        validateItems(cmd);
    }

    private void validateTypeSpecificFields(PromotionCommand cmd) {
        switch (cmd.promotionType()) {
            case PERCENT -> {
                if (cmd.percent() == null
                        || cmd.percent().signum() <= 0
                        || cmd.percent().compareTo(HUNDRED) > 0) {
                    throw validationError("percent", "OUT_OF_RANGE",
                            "El porcentaje debe ser mayor a 0 y menor o igual a 100.");
                }
            }
            case FIXED_AMOUNT -> {
                if (cmd.amount() == null || cmd.amount().signum() <= 0) {
                    throw validationError("amount", "OUT_OF_RANGE",
                            "El monto de descuento debe ser mayor a 0.");
                }
            }
            case BUY_X_PAY_Y -> {
                if (cmd.buyQty() == null || cmd.payQty() == null
                        || cmd.payQty() < 1 || cmd.buyQty() <= cmd.payQty()) {
                    throw validationError("buyQty", "INVALID_RULE",
                            "Para BUY_X_PAY_Y debe cumplirse buyQty > payQty >= 1.");
                }
            }
            case QUANTITY_PRICE -> {
                if (cmd.minQty() == null || cmd.minQty().signum() <= 0) {
                    throw validationError("minQty", "OUT_OF_RANGE",
                            "La cantidad mínima debe ser mayor a 0.");
                }
                if (cmd.amount() == null || cmd.amount().signum() <= 0) {
                    throw validationError("amount", "OUT_OF_RANGE",
                            "El precio unitario promocional debe ser mayor a 0.");
                }
            }
            case COMBO -> {
                if (cmd.comboPrice() == null || cmd.comboPrice().signum() <= 0) {
                    throw validationError("comboPrice", "OUT_OF_RANGE",
                            "El precio del combo debe ser mayor a 0.");
                }
            }
        }
    }

    private void validateItems(PromotionCommand cmd) {
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw validationError("items", "REQUIRED", "Debe incluir al menos un ítem objetivo.");
        }
        if (cmd.promotionType() == PromotionType.COMBO && cmd.items().size() < 2) {
            throw validationError("items", "COMBO_MIN_ITEMS",
                    "Un combo requiere al menos 2 productos.");
        }

        Set<Long> productIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        Set<Long> brandIds = new HashSet<>();

        for (PromotionItemCommand item : cmd.items()) {
            if (item.requiredQty() != null && item.requiredQty().signum() <= 0) {
                throw validationError("items.requiredQty", "OUT_OF_RANGE",
                        "La cantidad requerida del ítem debe ser mayor a 0.");
            }
            if (cmd.promotionType() == PromotionType.COMBO
                    && item.targetType() != PromotionTargetType.PRODUCT) {
                throw validationError("items.targetType", "COMBO_PRODUCT_ONLY",
                        "Los combos solo pueden referenciar productos.");
            }
            switch (item.targetType()) {
                case PRODUCT -> productIds.add(item.targetId());
                case CATEGORY -> categoryIds.add(item.targetId());
                case BRAND -> brandIds.add(item.targetId());
            }
        }

        if (!productIds.isEmpty()) {
            List<ProductSnapshot> found = productCatalogApi.findByIds(productIds);
            if (found.size() < productIds.size()) {
                Set<Long> foundIds = new HashSet<>();
                found.forEach(p -> foundIds.add(p.id()));
                for (Long pid : productIds) {
                    if (!foundIds.contains(pid)) {
                        throw new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado: " + pid);
                    }
                }
            }
        }
        if (!categoryIds.isEmpty()) {
            Map<Long, String> found = promotionLookup.findCategoryNames(categoryIds);
            for (Long cid : categoryIds) {
                if (!found.containsKey(cid)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "Categoría no encontrada: " + cid);
                }
            }
        }
        if (!brandIds.isEmpty()) {
            Map<Long, String> found = promotionLookup.findBrandNames(brandIds);
            for (Long bid : brandIds) {
                if (!found.containsKey(bid)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "Marca no encontrada: " + bid);
                }
            }
        }
    }

    private void applyCommand(Promotion promo, PromotionCommand cmd) {
        promo.setName(cmd.name().trim());
        promo.setDescription(cmd.description() == null || cmd.description().isBlank()
                ? null
                : cmd.description().trim());
        promo.setPromotionType(cmd.promotionType());
        promo.setPriority(cmd.priority() != null ? cmd.priority() : 100);
        promo.setStackable(Boolean.TRUE.equals(cmd.stackable()));
        promo.setValidFrom(cmd.validFrom());
        promo.setValidTo(cmd.validTo());
        promo.setDaysOfWeek(toDaysArray(cmd.daysOfWeek()));
        promo.setTimeFrom(cmd.timeFrom());
        promo.setTimeTo(cmd.timeTo());

        boolean allStores = cmd.allStores() == null || cmd.allStores();
        promo.setAllStores(allStores);
        promo.replaceStoreIds(allStores ? List.of() : new LinkedHashSet<>(cmd.storeIds()));

        promo.setPercent(cmd.promotionType() == PromotionType.PERCENT ? cmd.percent() : null);
        promo.setAmount(cmd.promotionType() == PromotionType.FIXED_AMOUNT
                || cmd.promotionType() == PromotionType.QUANTITY_PRICE
                ? Rounding.clp(cmd.amount())
                : null);
        promo.setBuyQty(cmd.promotionType() == PromotionType.BUY_X_PAY_Y ? cmd.buyQty() : null);
        promo.setPayQty(cmd.promotionType() == PromotionType.BUY_X_PAY_Y ? cmd.payQty() : null);
        promo.setMinQty(cmd.promotionType() == PromotionType.QUANTITY_PRICE
                ? Quantity.of(cmd.minQty()).value()
                : null);
        promo.setComboPrice(cmd.promotionType() == PromotionType.COMBO
                ? Rounding.clp(cmd.comboPrice())
                : null);
        promo.setMaxApplicationsPerSale(cmd.maxApplicationsPerSale());

        List<PromotionItem> newItems = cmd.items().stream().map(ic -> {
            PromotionItem item = new PromotionItem();
            item.setTargetType(ic.targetType());
            switch (ic.targetType()) {
                case PRODUCT -> item.setProductId(ic.targetId());
                case CATEGORY -> item.setCategoryId(ic.targetId());
                case BRAND -> item.setBrandId(ic.targetId());
            }
            item.setRequiredQty(ic.requiredQty() != null
                    ? Quantity.of(ic.requiredQty()).value()
                    : Quantity.of(1).value());
            return item;
        }).toList();
        promo.replaceItems(newItems);
    }

    private static short[] toDaysArray(List<Integer> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        List<Integer> distinctSorted = days.stream().distinct().sorted().toList();
        short[] arr = new short[distinctSorted.size()];
        for (int i = 0; i < distinctSorted.size(); i++) {
            arr[i] = distinctSorted.get(i).shortValue();
        }
        return arr;
    }

    private Map<TargetKey, String> resolveTargetNames(Collection<Promotion> promotions) {
        Set<Long> productIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        Set<Long> brandIds = new HashSet<>();

        for (Promotion p : promotions) {
            p.getStoreIds().size();
            for (PromotionItem item : p.getItems()) {
                switch (item.getTargetType()) {
                    case PRODUCT -> productIds.add(item.getProductId());
                    case CATEGORY -> categoryIds.add(item.getCategoryId());
                    case BRAND -> brandIds.add(item.getBrandId());
                }
            }
        }

        Map<TargetKey, String> names = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (ProductSnapshot ps : productCatalogApi.findByIds(productIds)) {
                names.put(new TargetKey(PromotionTargetType.PRODUCT, ps.id()), ps.name());
            }
        }
        if (!categoryIds.isEmpty()) {
            promotionLookup.findCategoryNames(categoryIds).forEach(
                    (id, name) -> names.put(new TargetKey(PromotionTargetType.CATEGORY, id), name));
        }
        if (!brandIds.isEmpty()) {
            promotionLookup.findBrandNames(brandIds).forEach(
                    (id, name) -> names.put(new TargetKey(PromotionTargetType.BRAND, id), name));
        }
        return names;
    }

    private PromotionView toView(Promotion p, Map<TargetKey, String> targetNames) {
        List<Integer> days = null;
        if (p.getDaysOfWeek() != null && p.getDaysOfWeek().length > 0) {
            days = new ArrayList<>(p.getDaysOfWeek().length);
            for (short d : p.getDaysOfWeek()) {
                days.add((int) d);
            }
        }
        List<PromotionItemView> itemViews = p.getItems().stream()
                .map(i -> new PromotionItemView(
                        i.getId(),
                        i.getTargetType(),
                        i.targetId(),
                        targetNames.getOrDefault(new TargetKey(i.getTargetType(), i.targetId()), ""),
                        i.getRequiredQty()
                ))
                .toList();

        return new PromotionView(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getPromotionType(),
                p.getPriority(),
                p.isStackable(),
                p.getValidFrom(),
                p.getValidTo(),
                days,
                p.getTimeFrom(),
                p.getTimeTo(),
                p.isAllStores(),
                List.copyOf(p.getStoreIds()),
                p.getPercent(),
                p.getAmount(),
                p.getBuyQty(),
                p.getPayQty(),
                p.getMinQty(),
                p.getComboPrice(),
                p.getMaxApplicationsPerSale(),
                p.isActive(),
                itemViews,
                p.getVersion()
        );
    }

    private static Map<String, Object> toAuditSnapshot(Promotion p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("promotionType", p.getPromotionType().name());
        map.put("priority", p.getPriority());
        map.put("stackable", p.isStackable());
        map.put("validFrom", p.getValidFrom() != null ? p.getValidFrom().toString() : null);
        map.put("validTo", p.getValidTo() != null ? p.getValidTo().toString() : null);
        map.put("allStores", p.isAllStores());
        map.put("storeIds", List.copyOf(p.getStoreIds()));
        map.put("percent", p.getPercent());
        map.put("amount", p.getAmount());
        map.put("buyQty", p.getBuyQty());
        map.put("payQty", p.getPayQty());
        map.put("minQty", p.getMinQty());
        map.put("comboPrice", p.getComboPrice());
        map.put("maxApplicationsPerSale", p.getMaxApplicationsPerSale());
        map.put("active", p.isActive());
        map.put("itemsCount", p.getItems().size());
        return map;
    }

    private static String buildLabel(Promotion p) {
        NumberFormat clp = NumberFormat.getIntegerInstance(ES_CL);
        return switch (p.getPromotionType()) {
            case PERCENT -> p.getPercent().stripTrailingZeros().toPlainString() + "% DCTO";
            case FIXED_AMOUNT -> "$" + clp.format(p.getAmount()) + " DCTO";
            case BUY_X_PAY_Y -> p.getBuyQty() + "x" + p.getPayQty();
            case QUANTITY_PRICE -> "$" + clp.format(p.getAmount())
                    + " c/u desde " + p.getMinQty().stripTrailingZeros().toPlainString();
            case COMBO -> "Combo $" + clp.format(p.getComboPrice());
        };
    }

    private static BusinessException validationError(String field, String code, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                message,
                Map.of(),
                List.of(new FieldErrorDetail(field, code, message))
        );
    }

    private record TargetKey(PromotionTargetType type, Long id) {
    }

    /** Filtros de búsqueda para {@code GET /api/v1/promotions}. */
    public record PromotionFilter(
            Boolean active,
            PromotionType type,
            Instant vigentAt,
            Long productId
    ) {
    }

    /** Comando de creación o actualización de una promoción. */
    public record PromotionCommand(
            String name,
            String description,
            PromotionType promotionType,
            Integer priority,
            Boolean stackable,
            Instant validFrom,
            Instant validTo,
            List<Integer> daysOfWeek,
            LocalTime timeFrom,
            LocalTime timeTo,
            Boolean allStores,
            List<Long> storeIds,
            BigDecimal percent,
            BigDecimal amount,
            Integer buyQty,
            Integer payQty,
            BigDecimal minQty,
            BigDecimal comboPrice,
            Integer maxApplicationsPerSale,
            List<PromotionItemCommand> items,
            Long version
    ) {
    }

    /** Ítem objetivo en {@link PromotionCommand}. */
    public record PromotionItemCommand(
            PromotionTargetType targetType,
            Long targetId,
            BigDecimal requiredQty
    ) {
    }

    /** Vista completa de una promoción para la capa web. */
    public record PromotionView(
            Long id,
            String name,
            String description,
            PromotionType promotionType,
            int priority,
            boolean stackable,
            Instant validFrom,
            Instant validTo,
            List<Integer> daysOfWeek,
            LocalTime timeFrom,
            LocalTime timeTo,
            boolean allStores,
            List<Long> storeIds,
            BigDecimal percent,
            BigDecimal amount,
            Integer buyQty,
            Integer payQty,
            BigDecimal minQty,
            BigDecimal comboPrice,
            Integer maxApplicationsPerSale,
            boolean active,
            List<PromotionItemView> items,
            Long version
    ) {
    }

    /** Ítem objetivo con nombre resuelto. */
    public record PromotionItemView(
            Long id,
            PromotionTargetType targetType,
            Long targetId,
            String targetName,
            BigDecimal requiredQty
    ) {
    }

    /** Resumen compacto de promoción vigente para el POS. */
    public record ActivePromotionView(
            Long id,
            String name,
            PromotionType promotionType,
            String label
    ) {
    }
}
