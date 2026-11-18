package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.catalog.BarcodeType;
import cl.minimarket.pos.catalog.PriceChangeType;
import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.catalog.product.domain.PriceHistory;
import cl.minimarket.pos.catalog.product.domain.PriceHistoryRepository;
import cl.minimarket.pos.catalog.product.domain.Product;
import cl.minimarket.pos.catalog.product.domain.ProductRepository;
import cl.minimarket.pos.catalog.tax.domain.Tax;
import cl.minimarket.pos.catalog.tax.domain.TaxRepository;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.rut.Ean;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementación de la API pública {@link ProductCatalogApi} del módulo {@code catalog}.
 */
@Service
public class ProductCatalogApiImpl implements ProductCatalogApi {

    private static final String ACTION_COST_CHANGED = "PRODUCT_COST_CHANGED";
    private static final String ENTITY_PRODUCT = "product";

    private final ProductRepository productRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final TaxRepository taxRepository;
    private final ProductSnapshotMapper snapshotMapper;
    private final CodeResolver codeResolver;
    private final ProductSearch productSearch;
    private final ProductService productService;
    private final AuditService auditService;
    private final BusinessClock clock;
    private final JdbcClient jdbc;

    public ProductCatalogApiImpl(ProductRepository productRepository,
                                 PriceHistoryRepository priceHistoryRepository,
                                 TaxRepository taxRepository,
                                 ProductSnapshotMapper snapshotMapper,
                                 CodeResolver codeResolver,
                                 ProductSearch productSearch,
                                 ProductService productService,
                                 AuditService auditService,
                                 BusinessClock clock,
                                 JdbcClient jdbc) {
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.priceHistoryRepository = Objects.requireNonNull(priceHistoryRepository, "priceHistoryRepository");
        this.taxRepository = Objects.requireNonNull(taxRepository, "taxRepository");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper");
        this.codeResolver = Objects.requireNonNull(codeResolver, "codeResolver");
        this.productSearch = Objects.requireNonNull(productSearch, "productSearch");
        this.productService = Objects.requireNonNull(productService, "productService");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSnapshot getForSale(Long id) {
        return snapshotMapper.findSnapshotsByIds(List.of(id)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScanResult> resolveCode(String code) {
        return codeResolver.resolve(code);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInactiveCodeMatch(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String trimmed = code.trim();
        Boolean regularOrShortcutOrSku = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM products p
                    WHERE p.active = false
                      AND (
                          p.shortcut_code = :code
                          OR p.sku = :code
                          OR EXISTS (
                              SELECT 1 FROM product_barcodes pb
                              WHERE pb.product_id = p.id
                                AND pb.code = :code
                                AND pb.barcode_type <> 'SCALE_PLU'
                          )
                      )
                )
                """)
                .param("code", trimmed)
                .query(Boolean.class)
                .single();

        if (Boolean.TRUE.equals(regularOrShortcutOrSku)) {
            return true;
        }

        if (trimmed.length() == 13 && Ean.isValid(trimmed)) {
            Boolean inactiveScale = jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM scale_barcode_configs sc
                        JOIN product_barcodes pb
                          ON pb.barcode_type = 'SCALE_PLU'
                         AND pb.code = substring(:code FROM sc.plu_start + 1 FOR sc.plu_length)
                        JOIN products p ON p.id = pb.product_id
                        WHERE sc.active = true
                          AND :code LIKE sc.prefix || '%'
                          AND sc.plu_start + sc.plu_length <= 12
                          AND p.active = false
                    )
                    """)
                    .param("code", trimmed)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(inactiveScale);
        }

        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSnapshot> search(String q, int limit) {
        return productSearch.search(q, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSnapshot> findByIds(Collection<Long> ids) {
        return snapshotMapper.findSnapshotsByIds(ids);
    }

    @Override
    @Transactional
    public void applyAverageCost(Long productId, BigDecimal newAvg, PriceChangeType changeType, String sourceRef) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Producto no encontrado."));
        BigDecimal normalized = Rounding.unitCost(newAvg);
        if (normalized.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "El costo promedio no puede ser negativo.");
        }

        BigDecimal oldCost = product.getAvgCost();
        product.setAvgCost(normalized);
        productRepository.save(product);

        String sourceType = null;
        Long sourceId = null;
        String reason = sourceRef;
        if (sourceRef != null && sourceRef.contains(":")) {
            String[] parts = sourceRef.split(":", 2);
            sourceType = parts[0];
            try {
                sourceId = Long.parseLong(parts[1]);
                reason = null;
            } catch (NumberFormatException ignored) {
                sourceType = null;
            }
        }

        PriceHistory history = new PriceHistory();
        history.setProductId(productId);
        history.setChangeType(changeType);
        history.setOldCost(oldCost);
        history.setNewCost(normalized);
        history.setSourceType(sourceType);
        history.setSourceId(sourceId);
        history.setReason(reason);
        history.setChangedBy(CurrentUserId.find().orElse(null));
        history.setChangedAt(OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC));
        priceHistoryRepository.save(history);

        Map<String, Object> newSnapshot = new HashMap<>();
        newSnapshot.put("avgCost", normalized);
        newSnapshot.put("changeType", changeType.name());
        if (sourceRef != null) {
            newSnapshot.put("sourceRef", sourceRef);
        }

        auditService.record(new AuditEvent(
                ACTION_COST_CHANGED,
                ENTITY_PRODUCT,
                productId.toString(),
                Map.of("avgCost", oldCost),
                newSnapshot,
                reason,
                null
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxRate> taxesFor(Long productId) {
        return jdbc.sql("""
                SELECT t.id, t.code, t.tax_type, t.rate
                FROM product_taxes pt
                JOIN taxes t ON t.id = pt.tax_id
                WHERE pt.product_id = :productId
                ORDER BY t.id ASC
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> new TaxRate(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("tax_type"),
                        rs.getBigDecimal("rate")
                ))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackInfo> packOf(Long productId) {
        return jdbc.sql("""
                SELECT product_id, base_product_id, factor
                FROM product_packs
                WHERE product_id = :productId
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> new PackInfo(
                        rs.getLong("product_id"),
                        rs.getLong("base_product_id"),
                        rs.getBigDecimal("factor").intValueExact()
                ))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> nonPackProductIds() {
        return productRepository.findNonPackProductIds();
    }

    @Override
    @Transactional
    public Long createFromImport(ImportProductCommand command) {
        UnitOfMeasure uom = command.saleType() == SaleType.WEIGHT ? UnitOfMeasure.KG : UnitOfMeasure.UNIT;
        List<Long> taxIds = null;
        boolean vatExempt = false;
        if (command.taxCodes() != null && !command.taxCodes().isEmpty()) {
            Map<String, Tax> byCode = new HashMap<>();
            for (Tax t : taxRepository.findAllByOrderByIdAsc()) {
                byCode.put(t.getCode(), t);
            }
            taxIds = command.taxCodes().stream()
                    .map(code -> {
                        Tax t = byCode.get(code);
                        if (t == null) {
                            throw new BusinessException(ErrorCode.NOT_FOUND, "Impuesto no encontrado: " + code);
                        }
                        return t.getId();
                    })
                    .toList();
            vatExempt = command.taxCodes().stream().noneMatch("IVA"::equals);
        }

        List<ProductService.BarcodeCommand> barcodes = command.barcodes() == null
                ? List.of()
                : command.barcodes().stream()
                        .map(code -> new ProductService.BarcodeCommand(code, inferBarcodeType(code), false))
                        .toList();

        ProductService.ProductCommand productCmd = new ProductService.ProductCommand(
                command.sku(),
                command.name(),
                null,
                command.brandId(),
                command.categoryId(),
                command.saleType(),
                uom,
                vatExempt,
                command.restrictedType(),
                command.lotControlled(),
                null,
                null,
                true,
                command.priceGross(),
                taxIds,
                barcodes,
                null,
                null
        );
        return productService.create(productCmd).product().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductExportRow> findForExport(ProductExportFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();
        if (filter != null) {
            if (filter.q() != null && !filter.q().isBlank()) {
                String trimmed = filter.q().trim();
                where.append("""
                         AND (f_unaccent(lower(p.name)) ILIKE f_unaccent(:qLike)
                              OR lower(p.sku) LIKE :qLike)
                        """);
                params.put("qLike", "%" + trimmed.toLowerCase() + "%");
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

        return jdbc.sql("""
                SELECT p.id,
                       p.sku,
                       p.name,
                       c.name AS category_name,
                       b.name AS brand_name,
                       p.sale_type,
                       p.unit_of_measure,
                       p.price_gross,
                       p.avg_cost,
                       p.last_cost,
                       p.vat_exempt,
                       p.restricted_type,
                       p.lot_controlled,
                       p.active,
                       (SELECT pb.code
                        FROM product_barcodes pb
                        WHERE pb.product_id = p.id
                        ORDER BY pb.is_primary DESC, pb.id ASC
                        LIMIT 1) AS primary_barcode
                FROM products p
                JOIN categories c ON c.id = p.category_id
                LEFT JOIN brands b ON b.id = p.brand_id
                """ + where + " ORDER BY p.name ASC, p.id ASC")
                .params(params)
                .query((rs, rowNum) -> new ProductExportRow(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("category_name"),
                        rs.getString("brand_name"),
                        SaleType.valueOf(rs.getString("sale_type")),
                        UnitOfMeasure.valueOf(rs.getString("unit_of_measure")),
                        rs.getBigDecimal("price_gross"),
                        rs.getBigDecimal("avg_cost"),
                        rs.getBigDecimal("last_cost"),
                        rs.getBoolean("vat_exempt"),
                        rs.getString("restricted_type") != null
                                ? RestrictedType.valueOf(rs.getString("restricted_type"))
                                : null,
                        rs.getBoolean("lot_controlled"),
                        rs.getBoolean("active"),
                        rs.getString("primary_barcode")
                ))
                .list();
    }

    private static BarcodeType inferBarcodeType(String code) {
        if (code != null && code.chars().allMatch(Character::isDigit)) {
            if (code.length() == 13) {
                return BarcodeType.EAN13;
            }
            if (code.length() == 8) {
                return BarcodeType.EAN8;
            }
            if (code.length() == 12) {
                return BarcodeType.UPC_A;
            }
        }
        return BarcodeType.INTERNAL;
    }
}
