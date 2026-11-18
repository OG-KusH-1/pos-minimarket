package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.BarcodeType;
import cl.minimarket.pos.catalog.LotControlGuard;
import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.ProductCreatedEvent;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.catalog.brand.domain.Brand;
import cl.minimarket.pos.catalog.brand.domain.BrandRepository;
import cl.minimarket.pos.catalog.category.domain.Category;
import cl.minimarket.pos.catalog.category.domain.CategoryRepository;
import cl.minimarket.pos.catalog.product.domain.BarcodeValidator;
import cl.minimarket.pos.catalog.product.domain.PriceHistory;
import cl.minimarket.pos.catalog.product.domain.PriceHistoryRepository;
import cl.minimarket.pos.catalog.product.domain.Product;
import cl.minimarket.pos.catalog.product.domain.ProductBarcode;
import cl.minimarket.pos.catalog.product.domain.ProductBarcodeRepository;
import cl.minimarket.pos.catalog.product.domain.ProductPack;
import cl.minimarket.pos.catalog.product.domain.ProductPackRepository;
import cl.minimarket.pos.catalog.product.domain.ProductRepository;
import cl.minimarket.pos.catalog.tax.domain.Tax;
import cl.minimarket.pos.catalog.tax.domain.TaxRepository;
import cl.minimarket.pos.catalog.tax.domain.TaxType;
import cl.minimarket.pos.files.FileApi;
import cl.minimarket.pos.files.FileCategory;
import cl.minimarket.pos.files.FileMetadata;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Casos de uso de productos del catálogo: creación, edición, códigos de barras, packs, impuestos
 * por producto, precio, costo, historial, imagen y activación/desactivación (T25).
 */
@Service
public class ProductService {

    private static final String ACTION_PRICE_CHANGED = "PRODUCT_PRICE_CHANGED";
    private static final String ACTION_COST_CHANGED = "PRODUCT_COST_CHANGED";
    private static final String ACTION_TAXES_CHANGED = "PRODUCT_TAXES_CHANGED";
    private static final String ACTION_DEACTIVATED = "PRODUCT_DEACTIVATED";
    private static final String ENTITY_PRODUCT = "product";
    private static final String DEFAULT_VAT_CODE = "IVA";

    private final ProductRepository productRepository;
    private final ProductBarcodeRepository barcodeRepository;
    private final ProductPackRepository packRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final TaxRepository taxRepository;
    private final LotControlGuard lotControlGuard;
    private final AuditService auditService;
    private final FileApi fileApi;
    private final ApplicationEventPublisher eventPublisher;
    private final BusinessClock clock;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;

    public ProductService(ProductRepository productRepository,
                          ProductBarcodeRepository barcodeRepository,
                          ProductPackRepository packRepository,
                          PriceHistoryRepository priceHistoryRepository,
                          CategoryRepository categoryRepository,
                          BrandRepository brandRepository,
                          TaxRepository taxRepository,
                          LotControlGuard lotControlGuard,
                          AuditService auditService,
                          FileApi fileApi,
                          ApplicationEventPublisher eventPublisher,
                          BusinessClock clock,
                          JdbcClient jdbc,
                          EntityManager entityManager) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.barcodeRepository = Objects.requireNonNull(barcodeRepository, "barcodeRepository");
        this.packRepository = Objects.requireNonNull(packRepository, "packRepository");
        this.priceHistoryRepository = Objects.requireNonNull(priceHistoryRepository, "priceHistoryRepository");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository");
        this.brandRepository = Objects.requireNonNull(brandRepository, "brandRepository");
        this.taxRepository = Objects.requireNonNull(taxRepository, "taxRepository");
        this.lotControlGuard = Objects.requireNonNull(lotControlGuard, "lotControlGuard");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.fileApi = Objects.requireNonNull(fileApi, "fileApi");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    // ---- Comandos y proyecciones de lectura ----

    /** Comando de creación o edición de producto. */
    public record ProductCommand(
            String sku,
            String name,
            String description,
            Long brandId,
            Long categoryId,
            SaleType saleType,
            UnitOfMeasure unitOfMeasure,
            Boolean vatExempt,
            RestrictedType restrictedType,
            Boolean lotControlled,
            Integer shelfLifeDays,
            String shortcutCode,
            Boolean allowManualDiscount,
            BigDecimal initialPriceGross,
            List<Long> taxIds,
            List<BarcodeCommand> barcodes,
            PackCommand pack,
            Long version
    ) {
    }

    /** Datos de un código de barras a registrar. */
    public record BarcodeCommand(
            String code,
            BarcodeType barcodeType,
            Boolean isPrimary
    ) {
    }

    /** Datos de definición de pack. */
    public record PackCommand(
            Long baseProductId,
            Integer factor
    ) {
    }

    /** Filtros de listado paginado de productos. */
    public record ProductFilter(
            String q,
            Long categoryId,
            Long brandId,
            SaleType saleType,
            Boolean active,
            Boolean restricted,
            Boolean lotControlled
    ) {
    }

    /** Fila resumida para listado paginado. */
    public record ProductSummaryView(
            Long id,
            String sku,
            String name,
            String brandName,
            String categoryName,
            SaleType saleType,
            BigDecimal priceGross,
            String primaryBarcode,
            boolean active,
            BigDecimal avgCost,
            BigDecimal marginPct
    ) {
    }

    /** Vista detallada completa de un producto. */
    public record ProductDetailView(
            Product product,
            Brand brand,
            Category category,
            Category parentCategory,
            List<ProductBarcode> barcodes,
            List<Tax> taxes,
            PackDetailView pack,
            BigDecimal netPrice,
            BigDecimal marginPct,
            boolean includeCosts
    ) {
    }

    /** Vista detallada de un pack con datos del producto base. */
    public record PackDetailView(
            Long baseProductId,
            String baseSku,
            String baseName,
            int factor
    ) {
    }

    // ---- Casos de uso ----

    /**
     * Lista productos paginados aplicando filtros de catálogo; oculta {@code avgCost} y
     * {@code marginPct} si el usuario no posee {@code catalog:cost:read}.
     */
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> list(ProductFilter filter, Pageable pageable) {
        boolean includeCosts = canReadCosts();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();

        if (filter != null) {
            if (filter.q() != null && !filter.q().isBlank()) {
                String trimmed = filter.q().trim();
                where.append("""
                         AND (f_unaccent(lower(p.name)) ILIKE f_unaccent(:qLike)
                              OR lower(p.sku) LIKE :qLike
                              OR p.shortcut_code = :qExact
                              OR EXISTS (
                                  SELECT 1 FROM product_barcodes pbq
                                  WHERE pbq.product_id = p.id AND pbq.code ILIKE :qLike
                              ))
                        """);
                params.put("qLike", "%" + trimmed.toLowerCase() + "%");
                params.put("qExact", trimmed);
            }
            if (filter.categoryId() != null) {
                where.append(" AND (p.category_id = :categoryId OR c.parent_id = :categoryId) ");
                params.put("categoryId", filter.categoryId());
            }
            if (filter.brandId() != null) {
                where.append(" AND p.brand_id = :brandId ");
                params.put("brandId", filter.brandId());
            }
            if (filter.saleType() != null) {
                where.append(" AND p.sale_type = :saleType ");
                params.put("saleType", filter.saleType().name());
            }
            if (filter.active() != null) {
                where.append(" AND p.active = :active ");
                params.put("active", filter.active());
            }
            if (filter.restricted() != null) {
                where.append(filter.restricted()
                        ? " AND p.restricted_type IS NOT NULL "
                        : " AND p.restricted_type IS NULL ");
            }
            if (filter.lotControlled() != null) {
                where.append(" AND p.lot_controlled = :lotControlled ");
                params.put("lotControlled", filter.lotControlled());
            }
        }

        String fromClause = """
                FROM products p
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN brands b ON b.id = p.brand_id
                """ + where;

        long total = jdbc.sql("SELECT COUNT(*) " + fromClause)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        String selectSql = """
                SELECT p.id,
                       p.sku,
                       p.name,
                       b.name AS brand_name,
                       c.name AS category_name,
                       p.sale_type,
                       p.price_gross,
                       p.avg_cost,
                       (SELECT pb.code
                        FROM product_barcodes pb
                        WHERE pb.product_id = p.id
                        ORDER BY pb.is_primary DESC, pb.id ASC
                        LIMIT 1) AS primary_barcode,
                       p.active
                """ + fromClause + orderBy + " LIMIT :limit OFFSET :offset";

        List<RawProductSummary> rows = jdbc.sql(selectSql)
                .params(params)
                .query((rs, rowNum) -> new RawProductSummary(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("brand_name"),
                        rs.getString("category_name"),
                        SaleType.valueOf(rs.getString("sale_type")),
                        rs.getBigDecimal("price_gross"),
                        rs.getBigDecimal("avg_cost"),
                        rs.getString("primary_barcode"),
                        rs.getBoolean("active")
                ))
                .list();

        Map<Long, List<BigDecimal>> ratesByProduct = loadTaxRatesForProducts(
                rows.stream().map(RawProductSummary::id).toList());

        List<ProductSummaryView> content = rows.stream().map(row -> {
            BigDecimal avgCost = includeCosts ? row.avgCost() : null;
            BigDecimal marginPct = null;
            if (includeCosts) {
                List<BigDecimal> rates = ratesByProduct.getOrDefault(row.id(), List.of());
                BigDecimal netPrice = ProductSnapshotMapper.computeNetPrice(row.priceGross(), rates);
                marginPct = ProductSnapshotMapper.computeMarginPct(netPrice, row.avgCost());
            }
            return new ProductSummaryView(
                    row.id(),
                    row.sku(),
                    row.name(),
                    row.brandName(),
                    row.categoryName(),
                    row.saleType(),
                    row.priceGross(),
                    row.primaryBarcode(),
                    row.active(),
                    avgCost,
                    marginPct
            );
        }).toList();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Obtiene el detalle completo de un producto por su identificador.
     */
    @Transactional(readOnly = true)
    public ProductDetailView getDetail(Long id) {
        Product product = findProductOrThrow(id);
        return buildDetailView(product);
    }

    /**
     * Crea un producto nuevo, sus códigos de barras, su definición de pack (si aplica), sus
     * impuestos y publica {@link ProductCreatedEvent}.
     */
    @Transactional
    public ProductDetailView create(ProductCommand cmd) {
        String sku = cmd.sku().trim();
        String name = cmd.name().trim();
        String description = trimToNull(cmd.description());
        String shortcutCode = trimToNull(cmd.shortcutCode());
        boolean vatExempt = Boolean.TRUE.equals(cmd.vatExempt());
        boolean lotControlled = Boolean.TRUE.equals(cmd.lotControlled());
        boolean allowManualDiscount = cmd.allowManualDiscount() == null || cmd.allowManualDiscount();

        validateSaleTypeAndUom(cmd.saleType(), cmd.unitOfMeasure(), lotControlled);
        validateCategoryAndBrand(cmd.categoryId(), cmd.brandId());
        assertUniqueSku(sku, null);
        assertUniqueShortcut(shortcutCode, null);

        BigDecimal initialPrice = cmd.initialPriceGross() != null
                ? Rounding.clp(cmd.initialPriceGross())
                : BigDecimal.ZERO;
        if (initialPrice.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El precio inicial no puede ser negativo.");
        }
        if (initialPrice.signum() > 0 && !hasAuthority(Permissions.CATALOG_PRODUCT_UPDATE_PRICE)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Se requiere el permiso catalog:product:update-price para fijar un precio inicial.");
        }

        List<Tax> resolvedTaxes = resolveTaxesForCreate(vatExempt, cmd.taxIds());

        if (cmd.saleType() == SaleType.PACK && cmd.pack() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Un producto de tipo PACK requiere definir el producto base y su factor.");
        }
        if (cmd.saleType() != SaleType.PACK && cmd.pack() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Solo los productos de tipo PACK pueden tener definición de pack.");
        }

        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setBrandId(cmd.brandId());
        product.setCategoryId(cmd.categoryId());
        product.setSaleType(cmd.saleType());
        product.setUnitOfMeasure(cmd.unitOfMeasure());
        product.setPriceGross(initialPrice);
        product.setAvgCost(BigDecimal.ZERO);
        product.setVatExempt(vatExempt);
        product.setRestrictedType(cmd.restrictedType());
        product.setLotControlled(lotControlled);
        product.setShelfLifeDays(cmd.shelfLifeDays());
        product.setShortcutCode(shortcutCode);
        product.setAllowManualDiscount(allowManualDiscount);
        product.setActive(true);

        Product saved = productRepository.saveAndFlush(product);

        replaceProductTaxes(saved.getId(), resolvedTaxes);

        if (cmd.barcodes() != null && !cmd.barcodes().isEmpty()) {
            createBarcodes(saved, cmd.barcodes());
        }

        if (cmd.pack() != null) {
            savePackDefinition(saved, cmd.pack());
        }

        if (initialPrice.signum() > 0) {
            recordPriceHistory(saved.getId(), PriceChangeType.SALE_PRICE,
                    BigDecimal.ZERO, initialPrice, null, null, null, null, "Precio inicial");
        }

        eventPublisher.publishEvent(new ProductCreatedEvent(saved.getId(), saved.getSku(), saved.getSaleType()));

        return buildDetailView(saved);
    }

    /**
     * Actualiza los datos no económicos de un producto. Ignora precio, costo, {@code vatExempt},
     * {@code taxIds}, {@code barcodes} y {@code pack} (API.md F.2).
     */
    @Transactional
    public ProductDetailView update(Long id, ProductCommand cmd) {
        Product product = findProductOrThrow(id);
        validateVersion(cmd.version(), product.getVersion());

        String sku = cmd.sku().trim();
        String name = cmd.name().trim();
        String description = trimToNull(cmd.description());
        String shortcutCode = trimToNull(cmd.shortcutCode());
        boolean allowManualDiscount = cmd.allowManualDiscount() == null
                ? product.isAllowManualDiscount()
                : cmd.allowManualDiscount();
        boolean desiredLotControlled = cmd.lotControlled() == null
                ? product.isLotControlled()
                : cmd.lotControlled();

        validateSaleTypeAndUom(cmd.saleType(), cmd.unitOfMeasure(), desiredLotControlled);
        if (product.getSaleType() != cmd.saleType() && hasSales(id)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede cambiar el tipo de venta de un producto que ya tiene ventas.");
        }

        validateCategoryAndBrand(cmd.categoryId(), cmd.brandId());
        assertUniqueSku(sku, id);
        assertUniqueShortcut(shortcutCode, id);

        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setBrandId(cmd.brandId());
        product.setCategoryId(cmd.categoryId());
        product.setSaleType(cmd.saleType());
        product.setUnitOfMeasure(cmd.unitOfMeasure());
        product.setRestrictedType(cmd.restrictedType());
        product.setShelfLifeDays(cmd.shelfLifeDays());
        product.setShortcutCode(shortcutCode);
        product.setAllowManualDiscount(allowManualDiscount);
        // Se ignora precio, costo, vatExempt, taxIds, barcodes y pack según contrato F.2.

        Product saved = productRepository.saveAndFlush(product);

        if (desiredLotControlled != saved.isLotControlled()) {
            lotControlGuard.apply(id, desiredLotControlled);
            entityManager.refresh(saved);
        }

        return buildDetailView(saved);
    }

    /** Desactiva un producto y registra {@code PRODUCT_DEACTIVATED} en auditoría. */
    @Transactional
    public void deactivate(Long id) {
        Product product = findProductOrThrow(id);
        if (product.isActive()) {
            Map<String, Object> oldSnapshot = Map.of("active", true);
            product.setActive(false);
            productRepository.save(product);
            auditService.record(new AuditEvent(
                    ACTION_DEACTIVATED,
                    ENTITY_PRODUCT,
                    id.toString(),
                    oldSnapshot,
                    Map.of("active", false),
                    null,
                    null
            ));
        }
    }

    /** Reactiva un producto desactivado. */
    @Transactional
    public void activate(Long id) {
        Product product = findProductOrThrow(id);
        if (!product.isActive()) {
            product.setActive(true);
            productRepository.save(product);
        }
    }

    /**
     * Cambia el precio de venta bruto (CLP) del producto, escribe en {@code price_history} y
     * audita {@code PRODUCT_PRICE_CHANGED} (RN-35).
     */
    @Transactional
    public ProductDetailView changePrice(Long id, BigDecimal newPriceGross, String reason, Long version) {
        Product product = findProductOrThrow(id);
        validateVersion(version, product.getVersion());

        BigDecimal normalized = Rounding.clp(newPriceGross);
        if (normalized.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El precio debe ser mayor o igual a 1.");
        }

        BigDecimal oldPrice = product.getPriceGross();
        product.setPriceGross(normalized);
        Product saved = productRepository.saveAndFlush(product);

        String cleanReason = reason.trim();
        recordPriceHistory(id, PriceChangeType.SALE_PRICE, oldPrice, normalized,
                null, null, null, null, cleanReason);

        auditService.record(new AuditEvent(
                ACTION_PRICE_CHANGED,
                ENTITY_PRODUCT,
                id.toString(),
                Map.of("priceGross", oldPrice),
                Map.of("priceGross", normalized),
                cleanReason,
                null
        ));

        return buildDetailView(saved);
    }

    /**
     * Cambia manualmente el costo promedio del producto ({@code COST_MANUAL}), escribe en
     * {@code price_history} y audita {@code PRODUCT_COST_CHANGED}.
     */
    @Transactional
    public ProductDetailView changeCost(Long id, BigDecimal newAvgCost, String reason, Long version) {
        Product product = findProductOrThrow(id);
        validateVersion(version, product.getVersion());

        BigDecimal normalized = Rounding.unitCost(newAvgCost);
        if (normalized.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El costo no puede ser negativo.");
        }

        BigDecimal oldCost = product.getAvgCost();
        product.setAvgCost(normalized);
        Product saved = productRepository.saveAndFlush(product);

        String cleanReason = reason.trim();
        recordPriceHistory(id, PriceChangeType.COST_MANUAL, null, null,
                oldCost, normalized, null, null, cleanReason);

        auditService.record(new AuditEvent(
                ACTION_COST_CHANGED,
                ENTITY_PRODUCT,
                id.toString(),
                Map.of("avgCost", oldCost),
                Map.of("avgCost", normalized),
                cleanReason,
                null
        ));

        return buildDetailView(saved);
    }

    /**
     * Lista el historial de precios y costos de un producto; oculta costos si falta
     * {@code catalog:cost:read}.
     */
    @Transactional(readOnly = true)
    public Page<PriceHistory> listPriceHistory(Long id, Pageable pageable) {
        findProductOrThrow(id);
        return priceHistoryRepository.findByProductId(id, pageable);
    }

    /** Agrega un código de barras a un producto existente. */
    @Transactional
    public ProductBarcode addBarcode(Long id, BarcodeCommand cmd) {
        Product product = findProductOrThrow(id);
        String code = cmd.code().trim();
        BarcodeValidator.validate(code, cmd.barcodeType());
        validateScalePluAllowed(product, cmd.barcodeType());
        assertUniqueBarcode(code, cmd.barcodeType());

        boolean primary = Boolean.TRUE.equals(cmd.isPrimary());
        if (primary) {
            barcodeRepository.clearPrimaryForProduct(id);
        }

        ProductBarcode barcode = new ProductBarcode();
        barcode.setProductId(id);
        barcode.setCode(code);
        barcode.setBarcodeType(cmd.barcodeType());
        barcode.setPrimary(primary);
        return barcodeRepository.save(barcode);
    }

    /** Elimina un código de barras de un producto. */
    @Transactional
    public void deleteBarcode(Long productId, Long barcodeId) {
        findProductOrThrow(productId);
        ProductBarcode barcode = barcodeRepository.findByIdAndProductId(barcodeId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Código de barras no encontrado."));
        barcodeRepository.delete(barcode);
    }

    /** Marca un código de barras como principal del producto. */
    @Transactional
    public void setPrimaryBarcode(Long productId, Long barcodeId) {
        findProductOrThrow(productId);
        ProductBarcode barcode = barcodeRepository.findByIdAndProductId(barcodeId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Código de barras no encontrado."));
        barcodeRepository.clearPrimaryForProduct(productId);
        barcode.setPrimary(true);
        barcodeRepository.save(barcode);
    }

    /** Define o actualiza el producto base y factor de un producto {@code PACK}. */
    @Transactional
    public PackDetailView setPack(Long id, PackCommand cmd) {
        Product product = findProductOrThrow(id);
        if (product.getSaleType() != SaleType.PACK) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Solo los productos de tipo PACK pueden configurar un pack.");
        }
        if (hasSales(id)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede modificar el pack de un producto que ya tiene ventas.");
        }
        Product base = savePackDefinition(product, cmd);
        return new PackDetailView(base.getId(), base.getSku(), base.getName(), cmd.factor());
    }

    /**
     * Reemplaza la configuración tributaria ({@code vatExempt} y {@code product_taxes}) y audita
     * {@code PRODUCT_TAXES_CHANGED}.
     */
    @Transactional
    public List<Tax> updateTaxes(Long id, boolean vatExempt, List<Long> taxIds) {
        Product product = findProductOrThrow(id);
        List<Tax> newTaxes = validateAndLoadTaxes(vatExempt, taxIds);

        List<Long> oldTaxIds = loadTaxesForProduct(id).stream().map(Tax::getId).toList();
        Map<String, Object> oldSnapshot = Map.of(
                "vatExempt", product.isVatExempt(),
                "taxIds", oldTaxIds
        );

        product.setVatExempt(vatExempt);
        productRepository.save(product);
        replaceProductTaxes(id, newTaxes);

        List<Long> newTaxIds = newTaxes.stream().map(Tax::getId).toList();
        auditService.record(new AuditEvent(
                ACTION_TAXES_CHANGED,
                ENTITY_PRODUCT,
                id.toString(),
                oldSnapshot,
                Map.of("vatExempt", vatExempt, "taxIds", newTaxIds),
                null,
                null
        ));

        return newTaxes;
    }

    /** Asocia una imagen previamente subida ({@link FileCategory#PRODUCT_IMAGE}) al producto. */
    @Transactional
    public ProductDetailView updateImage(Long id, Long fileId) {
        Product product = findProductOrThrow(id);
        FileMetadata metadata = fileApi.get(fileId);
        if (metadata.category() != FileCategory.PRODUCT_IMAGE) {
            throw new BusinessException(ErrorCode.FILE_INVALID,
                    "El archivo debe corresponder a la categoría PRODUCT_IMAGE.");
        }
        product.setImageFileId(fileId);
        Product saved = productRepository.saveAndFlush(product);
        return buildDetailView(saved);
    }

    /** Desvincula la imagen actual del producto. */
    @Transactional
    public void deleteImage(Long id) {
        Product product = findProductOrThrow(id);
        product.setImageFileId(null);
        productRepository.save(product);
    }

    /** Indica si el usuario autenticado posee permiso de lectura de costos. */
    public boolean canReadCosts() {
        return hasAuthority(Permissions.CATALOG_COST_READ);
    }

    // ---- Helpers privados ----

    private Product findProductOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado."));
    }

    private void validateVersion(Long requestVersion, Long currentVersion) {
        if (requestVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "La versión del registro es obligatoria.");
        }
        if (!requestVersion.equals(currentVersion)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "El producto fue modificado por otro usuario. Recarga e intenta nuevamente.");
        }
    }

    private void validateSaleTypeAndUom(SaleType saleType, UnitOfMeasure uom, boolean lotControlled) {
        boolean isWeight = saleType == SaleType.WEIGHT;
        boolean isKg = uom == UnitOfMeasure.KG;
        if (isWeight != isKg) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La unidad de medida debe ser KG para productos WEIGHT y UNIT para UNIT/PACK.");
        }
        if (saleType == SaleType.PACK && lotControlled) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Un producto de tipo PACK no puede tener control por lotes.");
        }
    }

    private void validateCategoryAndBrand(Long categoryId, Long brandId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Categoría no encontrada.");
        }
        if (brandId != null && !brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Marca no encontrada.");
        }
    }

    private void assertUniqueSku(String sku, Long excludeId) {
        boolean exists = excludeId == null
                ? productRepository.existsBySku(sku)
                : productRepository.existsBySkuAndIdNot(sku, excludeId);
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un producto con el SKU '" + sku + "'.");
        }
    }

    private void assertUniqueShortcut(String shortcutCode, Long excludeId) {
        if (shortcutCode == null) {
            return;
        }
        boolean exists = excludeId == null
                ? productRepository.existsByShortcutCode(shortcutCode)
                : productRepository.existsByShortcutCodeAndIdNot(shortcutCode, excludeId);
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "Ya existe un producto con el código de atajo '" + shortcutCode + "'.");
        }
    }

    private List<Tax> resolveTaxesForCreate(boolean vatExempt, List<Long> taxIds) {
        Tax defaultVat = findDefaultVatOrNull();
        boolean departingFromDefault = vatExempt || isCustomTaxSelection(taxIds, defaultVat);

        if (departingFromDefault && !hasAuthority(Permissions.CONFIG_TAX_MANAGE)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Se requiere el permiso config:tax:manage para modificar los impuestos por defecto.");
        }

        if (taxIds == null) {
            if (vatExempt) {
                return List.of();
            }
            return defaultVat != null ? List.of(defaultVat) : List.of();
        }
        return validateAndLoadTaxes(vatExempt, taxIds);
    }

    private boolean isCustomTaxSelection(List<Long> taxIds, Tax defaultVat) {
        if (taxIds == null) {
            return false;
        }
        if (defaultVat == null) {
            return !taxIds.isEmpty();
        }
        return taxIds.size() != 1 || !defaultVat.getId().equals(taxIds.getFirst());
    }

    private Tax findDefaultVatOrNull() {
        return taxRepository.findAllByOrderByIdAsc().stream()
                .filter(t -> DEFAULT_VAT_CODE.equals(t.getCode()) && t.isActive())
                .findFirst()
                .orElse(null);
    }

    private List<Tax> validateAndLoadTaxes(boolean vatExempt, List<Long> taxIds) {
        if (taxIds == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "La lista de impuestos es obligatoria.");
        }
        List<Long> distinctIds = taxIds.stream().distinct().toList();
        List<Tax> taxes = distinctIds.isEmpty() ? List.of() : taxRepository.findAllById(distinctIds);
        if (taxes.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Uno o más impuestos indicados no existen.");
        }
        boolean hasVat = taxes.stream().anyMatch(t -> t.getTaxType() == TaxType.VAT);
        if (vatExempt && hasVat) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Un producto exento de IVA no puede incluir impuestos de tipo VAT.");
        }
        if (!vatExempt && !hasVat) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Un producto afecto a IVA debe incluir el impuesto IVA vigente.");
        }
        return taxes;
    }

    private void replaceProductTaxes(Long productId, List<Tax> taxes) {
        jdbc.sql("DELETE FROM product_taxes WHERE product_id = :productId")
                .param("productId", productId)
                .update();
        for (Tax tax : taxes) {
            jdbc.sql("INSERT INTO product_taxes (product_id, tax_id) VALUES (:productId, :taxId)")
                    .param("productId", productId)
                    .param("taxId", tax.getId())
                    .update();
        }
    }

    private void createBarcodes(Product product, List<BarcodeCommand> commands) {
        int primaryCount = 0;
        Set<String> seenRegular = new HashSet<>();
        Set<String> seenScalePlu = new HashSet<>();

        for (BarcodeCommand cmd : commands) {
            String code = cmd.code().trim();
            BarcodeValidator.validate(code, cmd.barcodeType());
            validateScalePluAllowed(product, cmd.barcodeType());
            if (Boolean.TRUE.equals(cmd.isPrimary())) {
                primaryCount++;
            }
            Set<String> targetSet = cmd.barcodeType() == BarcodeType.SCALE_PLU ? seenScalePlu : seenRegular;
            if (!targetSet.add(code)) {
                throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                        "Código de barras duplicado en la solicitud: '" + code + "'.");
            }
            assertUniqueBarcode(code, cmd.barcodeType());
        }
        if (primaryCount > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Solo puede existir un código de barras principal por producto.");
        }

        for (BarcodeCommand cmd : commands) {
            ProductBarcode barcode = new ProductBarcode();
            barcode.setProductId(product.getId());
            barcode.setCode(cmd.code().trim());
            barcode.setBarcodeType(cmd.barcodeType());
            barcode.setPrimary(Boolean.TRUE.equals(cmd.isPrimary()));
            barcodeRepository.save(barcode);
        }
    }

    private void validateScalePluAllowed(Product product, BarcodeType type) {
        if (type != BarcodeType.SCALE_PLU) {
            return;
        }
        if (product.getSaleType() == SaleType.WEIGHT) {
            return;
        }
        if (product.getSaleType() == SaleType.UNIT && hasActivePriceScaleConfig()) {
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "SCALE_PLU solo se permite en productos WEIGHT o con balanza de precio embebido.");
    }

    private boolean hasActivePriceScaleConfig() {
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM scale_barcode_configs
                    WHERE active = true AND embedded_type = 'PRICE'
                )
                """)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private void assertUniqueBarcode(String code, BarcodeType type) {
        boolean exists = type == BarcodeType.SCALE_PLU
                ? barcodeRepository.existsByCodeAndBarcodeType(code, BarcodeType.SCALE_PLU)
                : barcodeRepository.existsByCodeAndBarcodeTypeNot(code, BarcodeType.SCALE_PLU);
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATED_VALUE,
                    "El código de barras '" + code + "' ya está asignado a otro producto.");
        }
    }

    private Product savePackDefinition(Product packProduct, PackCommand cmd) {
        if (packProduct.getId().equals(cmd.baseProductId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Un pack no puede referenciarse a sí mismo como producto base.");
        }
        if (cmd.factor() == null || cmd.factor() < 2 || cmd.factor() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El factor del pack debe ser un entero entre 2 y 1000.");
        }
        Product base = productRepository.findById(cmd.baseProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Producto base no encontrado."));
        if (base.getSaleType() != SaleType.UNIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El producto base de un pack debe ser de tipo UNIT.");
        }

        ProductPack pack = packRepository.findByProductId(packProduct.getId())
                .orElseGet(() -> {
                    ProductPack created = new ProductPack();
                    created.setProductId(packProduct.getId());
                    return created;
                });
        pack.setBaseProductId(base.getId());
        pack.setFactor(BigDecimal.valueOf(cmd.factor()));
        packRepository.save(pack);
        return base;
    }

    private boolean hasSales(Long productId) {
        Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM sale_lines WHERE product_id = :productId)")
                .param("productId", productId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    private void recordPriceHistory(Long productId,
                                    PriceChangeType changeType,
                                    BigDecimal oldPrice,
                                    BigDecimal newPrice,
                                    BigDecimal oldCost,
                                    BigDecimal newCost,
                                    String sourceType,
                                    Long sourceId,
                                    String reason) {
        PriceHistory entry = new PriceHistory();
        entry.setProductId(productId);
        entry.setChangeType(changeType);
        entry.setOldPriceGross(oldPrice);
        entry.setNewPriceGross(newPrice);
        entry.setOldCost(oldCost);
        entry.setNewCost(newCost);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setReason(reason);
        entry.setChangedBy(CurrentUserId.find().orElse(null));
        entry.setChangedAt(OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC));
        priceHistoryRepository.save(entry);
    }

    private ProductDetailView buildDetailView(Product product) {
        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);
        Category parentCategory = (category != null && category.getParentId() != null)
                ? categoryRepository.findById(category.getParentId()).orElse(null)
                : null;
        Brand brand = product.getBrandId() != null
                ? brandRepository.findById(product.getBrandId()).orElse(null)
                : null;
        List<ProductBarcode> barcodes = barcodeRepository.findByProductIdOrderByPrimaryDescIdAsc(product.getId());
        List<Tax> taxes = loadTaxesForProduct(product.getId());
        PackDetailView packView = packRepository.findByProductId(product.getId())
                .map(pack -> {
                    Product base = productRepository.findById(pack.getBaseProductId()).orElse(null);
                    return new PackDetailView(
                            pack.getBaseProductId(),
                            base != null ? base.getSku() : null,
                            base != null ? base.getName() : null,
                            pack.getFactor().intValueExact()
                    );
                })
                .orElse(null);

        List<BigDecimal> rates = taxes.stream().map(Tax::getRate).toList();
        BigDecimal netPrice = ProductSnapshotMapper.computeNetPrice(product.getPriceGross(), rates);
        boolean includeCosts = canReadCosts();
        BigDecimal marginPct = includeCosts
                ? ProductSnapshotMapper.computeMarginPct(netPrice, product.getAvgCost())
                : null;

        return new ProductDetailView(
                product,
                brand,
                category,
                parentCategory,
                barcodes,
                taxes,
                packView,
                netPrice,
                marginPct,
                includeCosts
        );
    }

    private List<Tax> loadTaxesForProduct(Long productId) {
        List<Long> taxIds = jdbc.sql("""
                SELECT pt.tax_id
                FROM product_taxes pt
                WHERE pt.product_id = :productId
                ORDER BY pt.tax_id ASC
                """)
                .param("productId", productId)
                .query(Long.class)
                .list();
        if (taxIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Tax> byId = taxRepository.findAllById(taxIds).stream()
                .collect(Collectors.toMap(Tax::getId, t -> t));
        List<Tax> ordered = new ArrayList<>(taxIds.size());
        for (Long taxId : taxIds) {
            Tax tax = byId.get(taxId);
            if (tax != null) {
                ordered.add(tax);
            }
        }
        return ordered;
    }

    private Map<Long, List<BigDecimal>> loadTaxRatesForProducts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<BigDecimal>> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT pt.product_id, t.rate
                FROM product_taxes pt
                JOIN taxes t ON t.id = pt.tax_id
                WHERE pt.product_id IN (:ids)
                ORDER BY pt.product_id ASC, t.id ASC
                """)
                .param("ids", productIds)
                .query(rs -> {
                    long pid = rs.getLong("product_id");
                    BigDecimal rate = rs.getBigDecimal("rate");
                    result.computeIfAbsent(pid, k -> new ArrayList<>()).add(rate);
                });
        return result;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY p.name ASC, p.id ASC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "p.id";
                case "sku" -> "p.sku";
                case "name" -> "p.name";
                case "priceGross" -> "p.price_gross";
                case "active" -> "p.active";
                case "createdAt" -> "p.created_at";
                default -> null;
            };
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY p.name ASC, p.id ASC ";
        }
        return " ORDER BY " + String.join(", ", clauses) + ", p.id ASC ";
    }

    private static boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RawProductSummary(
            Long id,
            String sku,
            String name,
            String brandName,
            String categoryName,
            SaleType saleType,
            BigDecimal priceGross,
            BigDecimal avgCost,
            String primaryBarcode,
            boolean active
    ) {
    }
}
