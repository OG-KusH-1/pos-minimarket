package cl.minimarket.pos.purchasing.productsupplier.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.purchasing.ProductSupplierApi;
import cl.minimarket.pos.purchasing.productsupplier.domain.ProductSupplier;
import cl.minimarket.pos.purchasing.supplier.domain.SupplierRepository;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.security.Permissions;
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
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas de asociaciones producto-proveedor y candidatos de compra.
 */
@Service
public class ProductSupplierQueryService {

    private final JdbcClient jdbc;
    private final SupplierRepository supplierRepository;
    private final ProductCatalogApi productCatalogApi;

    public ProductSupplierQueryService(JdbcClient jdbc,
                                       SupplierRepository supplierRepository,
                                       ProductCatalogApi productCatalogApi) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "supplierRepository");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
    }

    /** Proyección de lectura de una asociación producto-proveedor. */
    public record ProductSupplierView(
            Long id,
            Long productId,
            String productSku,
            String productName,
            Long supplierId,
            String supplierName,
            String supplierSku,
            String purchaseUnitName,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal costPerPurchaseUnit,
            BigDecimal unitCostNet,
            BigDecimal minOrderQty,
            Integer leadTimeDaysOverride,
            boolean isPreferred,
            Instant lastPurchaseAt,
            boolean active,
            Long version
    ) {
    }

    /**
     * Lista paginada de productos asociados a un proveedor; oculta campos de costo si el usuario no
     * posee {@code catalog:cost:read}.
     */
    @Transactional(readOnly = true)
    public Page<ProductSupplierView> listBySupplier(Long supplierId,
                                                    String q,
                                                    Boolean active,
                                                    Pageable pageable) {
        Objects.requireNonNull(pageable, "pageable");
        if (!supplierRepository.existsById(supplierId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Proveedor no encontrado.");
        }

        StringBuilder where = new StringBuilder(" WHERE ps.supplier_id = :supplierId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("supplierId", supplierId);

        if (active != null) {
            where.append(" AND ps.active = :active ");
            params.put("active", active);
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            where.append("""
                      AND (f_unaccent(lower(p.name)) ILIKE f_unaccent(:pattern)
                           OR lower(p.sku) LIKE :pattern
                           OR lower(COALESCE(ps.supplier_sku, '')) LIKE :pattern)
                    """);
            params.put("pattern", pattern);
        }

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM product_suppliers ps
                JOIN products p ON p.id = ps.product_id
                """ + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        boolean includeCosts = canReadCosts();
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        String sql = """
                SELECT ps.id,
                       ps.product_id,
                       p.sku AS product_sku,
                       p.name AS product_name,
                       ps.supplier_id,
                       s.legal_name AS supplier_name,
                       ps.supplier_sku,
                       ps.purchase_unit_name,
                       ps.units_per_purchase_unit,
                       ps.cost_per_purchase_unit,
                       ps.min_order_qty,
                       ps.lead_time_days_override,
                       ps.is_preferred,
                       ps.last_purchase_at,
                       ps.active,
                       ps.version
                FROM product_suppliers ps
                JOIN products p ON p.id = ps.product_id
                JOIN suppliers s ON s.id = ps.supplier_id
                """ + where + buildOrderBy(pageable.getSort()) + " LIMIT :limit OFFSET :offset ";

        List<ProductSupplierView> rows = jdbc.sql(sql)
                .params(pageParams)
                .query((rs, rowNum) -> mapView(rs, includeCosts))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Lista los proveedores asociados a un producto; oculta campos de costo si el usuario no posee
     * {@code catalog:cost:read}.
     */
    @Transactional(readOnly = true)
    public List<ProductSupplierView> listByProduct(Long productId) {
        productCatalogApi.getForSale(productId);
        boolean includeCosts = canReadCosts();
        return jdbc.sql("""
                SELECT ps.id,
                       ps.product_id,
                       p.sku AS product_sku,
                       p.name AS product_name,
                       ps.supplier_id,
                       s.legal_name AS supplier_name,
                       ps.supplier_sku,
                       ps.purchase_unit_name,
                       ps.units_per_purchase_unit,
                       ps.cost_per_purchase_unit,
                       ps.min_order_qty,
                       ps.lead_time_days_override,
                       ps.is_preferred,
                       ps.last_purchase_at,
                       ps.active,
                       ps.version
                FROM product_suppliers ps
                JOIN products p ON p.id = ps.product_id
                JOIN suppliers s ON s.id = ps.supplier_id
                WHERE ps.product_id = :productId
                ORDER BY ps.is_preferred DESC, ps.active DESC, ps.id ASC
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> mapView(rs, includeCosts))
                .list();
    }

    /**
     * Lista los candidatos activos de un producto para planificación/compras ({@code PLAN.md} C.4).
     */
    @Transactional(readOnly = true)
    public List<ProductSupplierApi.ProductSupplierCandidate> findCandidates(Long productId) {
        return jdbc.sql("""
                SELECT ps.id,
                       ps.product_id,
                       ps.supplier_id,
                       s.legal_name AS supplier_name,
                       ps.purchase_unit_name,
                       ps.units_per_purchase_unit,
                       ps.cost_per_purchase_unit,
                       ps.min_order_qty,
                       COALESCE(ps.lead_time_days_override, s.lead_time_days) AS effective_lead_time_days,
                       s.delivery_weekdays,
                       ps.is_preferred
                FROM product_suppliers ps
                JOIN suppliers s ON s.id = ps.supplier_id
                WHERE ps.product_id = :productId
                  AND ps.active = true
                  AND s.active = true
                ORDER BY ps.is_preferred DESC,
                         (ps.cost_per_purchase_unit / ps.units_per_purchase_unit) ASC,
                         COALESCE(ps.lead_time_days_override, s.lead_time_days) ASC,
                         ps.id ASC
                """)
                .param("productId", productId)
                .query((rs, rowNum) -> {
                    BigDecimal units = rs.getBigDecimal("units_per_purchase_unit");
                    BigDecimal cost = rs.getBigDecimal("cost_per_purchase_unit");
                    return new ProductSupplierApi.ProductSupplierCandidate(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getLong("supplier_id"),
                            rs.getString("supplier_name"),
                            rs.getString("purchase_unit_name"),
                            units,
                            cost,
                            ProductSupplier.computeUnitCostNet(cost, units),
                            rs.getBigDecimal("min_order_qty"),
                            rs.getInt("effective_lead_time_days"),
                            readSmallintArray(rs.getArray("delivery_weekdays")),
                            rs.getBoolean("is_preferred"));
                })
                .list();
    }

    /** Indica si el usuario autenticado posee permiso {@code catalog:cost:read}. */
    public static boolean canReadCosts() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }

    private static ProductSupplierView mapView(ResultSet rs, boolean includeCosts) throws SQLException {
        BigDecimal units = rs.getBigDecimal("units_per_purchase_unit");
        BigDecimal rawCost = rs.getBigDecimal("cost_per_purchase_unit");
        BigDecimal cost = includeCosts ? rawCost : null;
        BigDecimal unitCostNet = includeCosts ? ProductSupplier.computeUnitCostNet(rawCost, units) : null;
        Timestamp lastPurchaseTs = rs.getTimestamp("last_purchase_at");

        return new ProductSupplierView(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("product_sku"),
                rs.getString("product_name"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getString("supplier_sku"),
                rs.getString("purchase_unit_name"),
                units,
                cost,
                unitCostNet,
                rs.getBigDecimal("min_order_qty"),
                rs.getObject("lead_time_days_override", Integer.class),
                rs.getBoolean("is_preferred"),
                lastPurchaseTs == null ? null : lastPurchaseTs.toInstant(),
                rs.getBoolean("active"),
                rs.getLong("version")
        );
    }

    private static List<Integer> readSmallintArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof Short[] shorts) {
            List<Integer> list = new ArrayList<>(shorts.length);
            for (Short s : shorts) {
                if (s != null) {
                    list.add(s.intValue());
                }
            }
            return List.copyOf(list);
        }
        if (raw instanceof Number[] numbers) {
            List<Integer> list = new ArrayList<>(numbers.length);
            for (Number n : numbers) {
                if (n != null) {
                    list.add(n.intValue());
                }
            }
            return List.copyOf(list);
        }
        return List.of();
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY ps.is_preferred DESC, p.name ASC, ps.id ASC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "ps.id";
                case "productId" -> "ps.product_id";
                case "productSku" -> "p.sku";
                case "productName" -> "p.name";
                case "supplierSku" -> "ps.supplier_sku";
                case "purchaseUnitName" -> "ps.purchase_unit_name";
                case "costPerPurchaseUnit" -> "ps.cost_per_purchase_unit";
                case "minOrderQty" -> "ps.min_order_qty";
                case "isPreferred" -> "ps.is_preferred";
                case "active" -> "ps.active";
                case "createdAt" -> "ps.created_at";
                default -> null;
            };
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY ps.is_preferred DESC, p.name ASC, ps.id ASC ";
        }
        return " ORDER BY " + String.join(", ", clauses) + ", ps.id ASC ";
    }
}
