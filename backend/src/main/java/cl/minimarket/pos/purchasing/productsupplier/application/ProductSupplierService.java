package cl.minimarket.pos.purchasing.productsupplier.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.productsupplier.application.ProductSupplierQueryService.ProductSupplierView;
import cl.minimarket.pos.purchasing.productsupplier.domain.ProductSupplier;
import cl.minimarket.pos.purchasing.productsupplier.domain.ProductSupplierRepository;
import cl.minimarket.pos.purchasing.supplier.domain.Supplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.error.FieldErrorDetail;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Casos de uso de escritura para asociaciones producto-proveedor ({@code product_suppliers},
 * {@code HU-PRV-02}).
 */
@Service
public class ProductSupplierService {

    private static final String ACTION_CHANGED = "PRODUCT_SUPPLIER_CHANGED";
    private static final String ENTITY_TYPE = "product_supplier";
    private static final int MAX_LEAD_TIME_DAYS = 90;

    private final ProductSupplierRepository productSupplierRepository;
    private final SupplierRepository supplierRepository;
    private final ProductCatalogApi productCatalogApi;
    private final AuditService auditService;

    public ProductSupplierService(ProductSupplierRepository productSupplierRepository,
                                  SupplierRepository supplierRepository,
                                  ProductCatalogApi productCatalogApi,
                                  AuditService auditService) {
        this.productSupplierRepository = Objects.requireNonNull(productSupplierRepository, "productSupplierRepository");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "supplierRepository");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    /** Comando de creación o edición REST de producto-proveedor. */
    public record SaveCommand(
            Long productId,
            Long supplierId,
            String supplierSku,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal minOrderQty,
            Integer leadTimeDaysOverride,
            Boolean isPreferred,
            Long version
    ) {
    }

    /**
     * Crea una asociación producto-proveedor validando que el producto no sea {@code PACK},
     * desmarcando el preferido anterior si corresponde y auditando {@code PRODUCT_SUPPLIER_CHANGED}.
     */
    @Transactional
    public ProductSupplierView create(SaveCommand cmd) {
        ProductCatalogApi.ProductSnapshot product = requireNonPackProduct(cmd.productId());
        Supplier supplier = requireSupplier(cmd.supplierId());

        if (productSupplierRepository.existsByProductIdAndSupplierId(cmd.productId(), cmd.supplierId())) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "El producto ya tiene una asociación registrada con ese proveedor.");
        }

        NormalizedInput input = normalizeAndValidate(
                cmd.supplierSku(),
                cmd.purchaseUnitName(),
                cmd.unitsPerPurchaseUnit(),
                cmd.costPerPurchaseUnit(),
                cmd.minOrderQty(),
                cmd.leadTimeDaysOverride());

        boolean preferred = Boolean.TRUE.equals(cmd.isPreferred());
        if (preferred) {
            clearOtherPreferred(cmd.productId(), null);
        }

        ProductSupplier ps = new ProductSupplier();
        ps.setProductId(cmd.productId());
        ps.setSupplierId(cmd.supplierId());
        ps.setSupplierSku(input.supplierSku());
        ps.setPurchaseUnitName(input.purchaseUnitName());
        ps.setUnitsPerPurchaseUnit(input.unitsPerPurchaseUnit());
        ps.setCostPerPurchaseUnit(input.costPerPurchaseUnit());
        ps.setMinOrderQty(input.minOrderQty());
        ps.setLeadTimeDaysOverride(input.leadTimeDaysOverride());
        ps.setPreferred(preferred);
        ps.setActive(true);

        ProductSupplier saved = productSupplierRepository.saveAndFlush(ps);
        auditService.record(new AuditEvent(
                ACTION_CHANGED,
                ENTITY_TYPE,
                saved.getId().toString(),
                null,
                snapshot(saved),
                null,
                null));

        return toView(saved, product, supplier);
    }

    /**
     * Actualiza una asociación producto-proveedor existente, manteniendo único el proveedor
     * preferido por producto y auditando el antes y después en {@code PRODUCT_SUPPLIER_CHANGED}.
     */
    @Transactional
    public ProductSupplierView update(Long id, SaveCommand cmd) {
        ProductSupplier ps = findOrThrow(id);
        if (cmd.version() != null && !cmd.version().equals(ps.getVersion())) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "La asociación producto-proveedor fue modificada por otro usuario. Recargue e intente nuevamente.");
        }

        ProductCatalogApi.ProductSnapshot product = requireNonPackProduct(cmd.productId());
        Supplier supplier = requireSupplier(cmd.supplierId());

        if (productSupplierRepository.existsByProductIdAndSupplierIdAndIdNot(cmd.productId(), cmd.supplierId(), id)) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe otra asociación registrada para ese producto y proveedor.");
        }

        NormalizedInput input = normalizeAndValidate(
                cmd.supplierSku(),
                cmd.purchaseUnitName(),
                cmd.unitsPerPurchaseUnit(),
                cmd.costPerPurchaseUnit(),
                cmd.minOrderQty(),
                cmd.leadTimeDaysOverride());

        boolean preferred = cmd.isPreferred() != null ? cmd.isPreferred() : ps.isPreferred();
        if (preferred && !ps.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede marcar como preferida una asociación inactiva.");
        }

        Map<String, Object> oldSnapshot = snapshot(ps);
        if (preferred) {
            clearOtherPreferred(cmd.productId(), id);
        }

        ps.setProductId(cmd.productId());
        ps.setSupplierId(cmd.supplierId());
        ps.setSupplierSku(input.supplierSku());
        ps.setPurchaseUnitName(input.purchaseUnitName());
        ps.setUnitsPerPurchaseUnit(input.unitsPerPurchaseUnit());
        ps.setCostPerPurchaseUnit(input.costPerPurchaseUnit());
        ps.setMinOrderQty(input.minOrderQty());
        ps.setLeadTimeDaysOverride(input.leadTimeDaysOverride());
        ps.setPreferred(preferred);

        ProductSupplier saved = productSupplierRepository.saveAndFlush(ps);
        auditService.record(new AuditEvent(
                ACTION_CHANGED,
                ENTITY_TYPE,
                saved.getId().toString(),
                oldSnapshot,
                snapshot(saved),
                null,
                null));

        return toView(saved, product, supplier);
    }

    /**
     * Desactiva una asociación producto-proveedor (limpia {@code isPreferred}) y audita
     * {@code PRODUCT_SUPPLIER_CHANGED}.
     */
    @Transactional
    public void deactivate(Long id) {
        ProductSupplier ps = findOrThrow(id);
        if (ps.isActive()) {
            Map<String, Object> oldSnapshot = snapshot(ps);
            ps.setActive(false);
            ps.setPreferred(false);
            ProductSupplier saved = productSupplierRepository.saveAndFlush(ps);
            auditService.record(new AuditEvent(
                    ACTION_CHANGED,
                    ENTITY_TYPE,
                    saved.getId().toString(),
                    oldSnapshot,
                    snapshot(saved),
                    null,
                    null));
        }
    }

    /**
     * Reactiva una asociación producto-proveedor previamente desactivada y audita
     * {@code PRODUCT_SUPPLIER_CHANGED}.
     */
    @Transactional
    public void activate(Long id) {
        ProductSupplier ps = findOrThrow(id);
        if (!ps.isActive()) {
            Map<String, Object> oldSnapshot = snapshot(ps);
            if (ps.isPreferred()) {
                clearOtherPreferred(ps.getProductId(), id);
            }
            ps.setActive(true);
            ProductSupplier saved = productSupplierRepository.saveAndFlush(ps);
            auditService.record(new AuditEvent(
                    ACTION_CHANGED,
                    ENTITY_TYPE,
                    saved.getId().toString(),
                    oldSnapshot,
                    snapshot(saved),
                    null,
                    null));
        }
    }

    /**
     * Marca una asociación activa como proveedor preferido del producto, desmarcando el preferido
     * anterior y auditando {@code PRODUCT_SUPPLIER_CHANGED}.
     */
    @Transactional
    public void markPreferred(Long id) {
        ProductSupplier ps = findOrThrow(id);
        if (!ps.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede marcar como preferido un vínculo producto-proveedor inactivo.");
        }
        if (ps.isPreferred()) {
            return;
        }
        Map<String, Object> oldSnapshot = snapshot(ps);
        clearOtherPreferred(ps.getProductId(), id);
        ps.setPreferred(true);
        ProductSupplier saved = productSupplierRepository.saveAndFlush(ps);
        auditService.record(new AuditEvent(
                ACTION_CHANGED,
                ENTITY_TYPE,
                saved.getId().toString(),
                oldSnapshot,
                snapshot(saved),
                null,
                null));
    }

    /**
     * Implementa el alta o actualización programática para {@link ProductSupplierApi#upsert}.
     */
    @Transactional
    public ProductSupplierApi.ProductSupplierInfo upsert(ProductSupplierApi.ProductSupplierCommand cmd) {
        requireNonPackProduct(cmd.productId());
        requireSupplier(cmd.supplierId());

        ProductSupplier existing = productSupplierRepository
                .findByProductIdAndSupplierId(cmd.productId(), cmd.supplierId())
                .orElse(null);

        if (existing == null) {
            NormalizedInput input = normalizeAndValidate(
                    cmd.supplierSku(),
                    cmd.purchaseUnitName(),
                    cmd.unitsPerPurchaseUnit(),
                    cmd.costPerPurchaseUnit(),
                    cmd.minOrderQty(),
                    cmd.leadTimeDaysOverride());
            boolean preferred = Boolean.TRUE.equals(cmd.isPreferred());
            if (preferred) {
                clearOtherPreferred(cmd.productId(), null);
            }
            ProductSupplier created = new ProductSupplier();
            created.setProductId(cmd.productId());
            created.setSupplierId(cmd.supplierId());
            created.setSupplierSku(input.supplierSku());
            created.setPurchaseUnitName(input.purchaseUnitName());
            created.setUnitsPerPurchaseUnit(input.unitsPerPurchaseUnit());
            created.setCostPerPurchaseUnit(input.costPerPurchaseUnit());
            created.setMinOrderQty(input.minOrderQty());
            created.setLeadTimeDaysOverride(input.leadTimeDaysOverride());
            created.setPreferred(preferred);
            created.setActive(true);

            ProductSupplier saved = productSupplierRepository.saveAndFlush(created);
            auditService.record(new AuditEvent(
                    ACTION_CHANGED,
                    ENTITY_TYPE,
                    saved.getId().toString(),
                    null,
                    snapshot(saved),
                    null,
                    null));
            return toApiInfo(saved);
        }

        String effectiveSupplierSku = cmd.supplierSku() != null ? cmd.supplierSku() : existing.getSupplierSku();
        Integer effectiveLeadTime = cmd.leadTimeDaysOverride() != null
                ? cmd.leadTimeDaysOverride()
                : existing.getLeadTimeDaysOverride();
        NormalizedInput input = normalizeAndValidate(
                effectiveSupplierSku,
                cmd.purchaseUnitName(),
                cmd.unitsPerPurchaseUnit(),
                cmd.costPerPurchaseUnit(),
                cmd.minOrderQty(),
                effectiveLeadTime);

        Map<String, Object> oldSnapshot = snapshot(existing);
        boolean preferred = cmd.isPreferred() != null ? cmd.isPreferred() : existing.isPreferred();
        if (preferred) {
            clearOtherPreferred(cmd.productId(), existing.getId());
        }

        existing.setSupplierSku(input.supplierSku());
        existing.setPurchaseUnitName(input.purchaseUnitName());
        existing.setUnitsPerPurchaseUnit(input.unitsPerPurchaseUnit());
        existing.setCostPerPurchaseUnit(input.costPerPurchaseUnit());
        existing.setMinOrderQty(input.minOrderQty());
        existing.setLeadTimeDaysOverride(input.leadTimeDaysOverride());
        existing.setPreferred(preferred);
        existing.setActive(true);

        ProductSupplier saved = productSupplierRepository.saveAndFlush(existing);
        auditService.record(new AuditEvent(
                ACTION_CHANGED,
                ENTITY_TYPE,
                saved.getId().toString(),
                oldSnapshot,
                snapshot(saved),
                null,
                null));
        return toApiInfo(saved);
    }

    /** Convierte una entidad {@link ProductSupplier} al DTO público del módulo. */
    public static ProductSupplierApi.ProductSupplierInfo toApiInfo(ProductSupplier ps) {
        return new ProductSupplierApi.ProductSupplierInfo(
                ps.getId(),
                ps.getProductId(),
                ps.getSupplierId(),
                ps.getSupplierSku(),
                ps.getPurchaseUnitName(),
                ps.getUnitsPerPurchaseUnit(),
                ps.getCostPerPurchaseUnit(),
                ps.unitCostNet(),
                ps.getMinOrderQty(),
                ps.getLeadTimeDaysOverride(),
                ps.isPreferred(),
                ps.getLastPurchaseAt(),
                ps.isActive()
        );
    }

    private ProductSupplier findOrThrow(Long id) {
        return productSupplierRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Asociación producto-proveedor no encontrada."));
    }

    private ProductCatalogApi.ProductSnapshot requireNonPackProduct(Long productId) {
        ProductCatalogApi.ProductSnapshot product = productCatalogApi.getForSale(productId);
        if (product.saleType() == SaleType.PACK) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Un producto de tipo PACK no puede asociarse a proveedores.",
                    Map.of(),
                    List.of(new FieldErrorDetail("productId", "PACK_NOT_ALLOWED",
                            "Los productos PACK no se compran directamente a proveedores.")));
        }
        return product;
    }

    private Supplier requireSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Proveedor no encontrado."));
    }

    private void clearOtherPreferred(Long productId, Long excludeId) {
        List<ProductSupplier> currentPreferred = productSupplierRepository.findAllByProductIdAndPreferredTrue(productId);
        for (ProductSupplier prev : currentPreferred) {
            if (excludeId == null || !prev.getId().equals(excludeId)) {
                prev.setPreferred(false);
                productSupplierRepository.saveAndFlush(prev);
            }
        }
    }

    private static NormalizedInput normalizeAndValidate(String rawSupplierSku,
                                                      String rawPurchaseUnitName,
                                                      BigDecimal rawUnitsPerPurchaseUnit,
                                                      BigDecimal rawCostPerPurchaseUnit,
                                                      BigDecimal rawMinOrderQty,
                                                      Integer leadTimeDaysOverride) {
        String purchaseUnitName = trimToNull(rawPurchaseUnitName);
        if (purchaseUnitName == null || purchaseUnitName.length() > 30) {
            throw validationError("purchaseUnitName", "INVALID_PURCHASE_UNIT",
                    "El nombre de la unidad de compra debe tener entre 1 y 30 caracteres.");
        }

        String supplierSku = trimToNull(rawSupplierSku);
        if (supplierSku != null && supplierSku.length() > 40) {
            throw validationError("supplierSku", "INVALID_SUPPLIER_SKU",
                    "El SKU del proveedor no puede superar los 40 caracteres.");
        }

        BigDecimal units = Rounding.quantity(requireNonNullNumber(rawUnitsPerPurchaseUnit, "unitsPerPurchaseUnit"));
        if (units.signum() <= 0) {
            throw validationError("unitsPerPurchaseUnit", "INVALID_UNITS_PER_PURCHASE_UNIT",
                    "Las unidades por unidad de compra deben ser mayores que cero.");
        }

        BigDecimal cost = Rounding.unitCost(requireNonNullNumber(rawCostPerPurchaseUnit, "costPerPurchaseUnit"));
        if (cost.signum() < 0) {
            throw validationError("costPerPurchaseUnit", "INVALID_COST",
                    "El costo por unidad de compra no puede ser negativo.");
        }

        BigDecimal minOrderQty = Rounding.quantity(requireNonNullNumber(rawMinOrderQty, "minOrderQty"));
        if (minOrderQty.compareTo(BigDecimal.ONE) < 0) {
            throw validationError("minOrderQty", "INVALID_MIN_ORDER_QTY",
                    "La cantidad mínima de pedido debe ser mayor o igual a 1.");
        }

        if (leadTimeDaysOverride != null && (leadTimeDaysOverride < 0 || leadTimeDaysOverride > MAX_LEAD_TIME_DAYS)) {
            throw validationError("leadTimeDaysOverride", "INVALID_LEAD_TIME",
                    "El lead time específico debe estar entre 0 y " + MAX_LEAD_TIME_DAYS + " días.");
        }

        return new NormalizedInput(supplierSku, purchaseUnitName, units, cost, minOrderQty, leadTimeDaysOverride);
    }

    private static BigDecimal requireNonNullNumber(BigDecimal value, String field) {
        if (value == null) {
            throw validationError(field, "REQUIRED", "El campo " + field + " es obligatorio.");
        }
        return value;
    }

    private static BusinessException validationError(String field, String code, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                message,
                Map.of(),
                List.of(new FieldErrorDetail(field, code, message)));
    }

    private static ProductSupplierView toView(ProductSupplier ps,
                                              ProductCatalogApi.ProductSnapshot product,
                                              Supplier supplier) {
        boolean includeCosts = ProductSupplierQueryService.canReadCosts();
        return new ProductSupplierView(
                ps.getId(),
                ps.getProductId(),
                product.sku(),
                product.name(),
                ps.getSupplierId(),
                supplier.getLegalName(),
                ps.getSupplierSku(),
                ps.getPurchaseUnitName(),
                ps.getUnitsPerPurchaseUnit(),
                includeCosts ? ps.getCostPerPurchaseUnit() : null,
                includeCosts ? ps.unitCostNet() : null,
                ps.getMinOrderQty(),
                ps.getLeadTimeDaysOverride(),
                ps.isPreferred(),
                ps.getLastPurchaseAt(),
                ps.isActive(),
                ps.getVersion()
        );
    }

    private static Map<String, Object> snapshot(ProductSupplier ps) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productId", ps.getProductId());
        map.put("supplierId", ps.getSupplierId());
        map.put("supplierSku", ps.getSupplierSku());
        map.put("purchaseUnitName", ps.getPurchaseUnitName());
        map.put("unitsPerPurchaseUnit", ps.getUnitsPerPurchaseUnit());
        map.put("costPerPurchaseUnit", ps.getCostPerPurchaseUnit());
        map.put("unitCostNet", ps.unitCostNet());
        map.put("minOrderQty", ps.getMinOrderQty());
        map.put("leadTimeDaysOverride", ps.getLeadTimeDaysOverride());
        map.put("isPreferred", ps.isPreferred());
        map.put("active", ps.isActive());
        return map;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record NormalizedInput(
            String supplierSku,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal minOrderQty,
            Integer leadTimeDaysOverride
    ) {
    }
}
