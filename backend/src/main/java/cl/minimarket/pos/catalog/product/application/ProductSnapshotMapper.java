package cl.minimarket.pos.catalog.product.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.RestrictedType;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Helper interno para proyectar productos a {@link ProductCatalogApi.ProductSnapshot} y calcular
 * precio neto y margen según {@code API.md} F.3.
 */
@Component
public class ProductSnapshotMapper {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JdbcClient jdbc;

    public ProductSnapshotMapper(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Carga proyecciones completas para un conjunto de identificadores de producto.
     */
    public List<ProductCatalogApi.ProductSnapshot> findSnapshotsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductCatalogApi.ProductSnapshot> byId = new LinkedHashMap<>();
        List<ProductCatalogApi.ProductSnapshot> rows = jdbc.sql("""
                SELECT p.id,
                       p.sku,
                       p.name,
                       p.category_id,
                       p.brand_id,
                       p.sale_type,
                       p.unit_of_measure,
                       p.price_gross,
                       p.avg_cost,
                       p.vat_exempt,
                       p.restricted_type,
                       p.lot_controlled,
                       p.active,
                       (SELECT pb.code
                        FROM product_barcodes pb
                        WHERE pb.product_id = p.id
                        ORDER BY pb.is_primary DESC, pb.id ASC
                        LIMIT 1) AS primary_barcode,
                       p.shortcut_code,
                       p.allow_manual_discount,
                       pp.factor AS pack_factor,
                       p.shelf_life_days
                FROM products p
                LEFT JOIN product_packs pp ON pp.product_id = p.id
                WHERE p.id IN (:ids)
                ORDER BY p.id ASC
                """)
                .param("ids", ids)
                .query((rs, rowNum) -> new ProductCatalogApi.ProductSnapshot(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getLong("category_id"),
                        rs.getObject("brand_id", Long.class),
                        SaleType.valueOf(rs.getString("sale_type")),
                        UnitOfMeasure.valueOf(rs.getString("unit_of_measure")),
                        rs.getBigDecimal("price_gross"),
                        rs.getBigDecimal("avg_cost"),
                        rs.getBoolean("vat_exempt"),
                        rs.getString("restricted_type") != null
                                ? RestrictedType.valueOf(rs.getString("restricted_type"))
                                : null,
                        rs.getBoolean("lot_controlled"),
                        rs.getBoolean("active"),
                        rs.getString("primary_barcode"),
                        rs.getString("shortcut_code"),
                        rs.getBoolean("allow_manual_discount"),
                        rs.getBigDecimal("pack_factor") != null
                                ? rs.getBigDecimal("pack_factor").intValueExact()
                                : null,
                        rs.getObject("shelf_life_days", Integer.class)
                ))
                .list();
        for (ProductCatalogApi.ProductSnapshot row : rows) {
            byId.put(row.id(), row);
        }
        return List.copyOf(byId.values());
    }

    /**
     * Calcula {@code netPrice = round(priceGross / (1 + R), 0)} con {@code R = Σ rate_i / 100}.
     */
    public static BigDecimal computeNetPrice(BigDecimal priceGross, List<BigDecimal> taxRatesPercent) {
        if (priceGross == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal combinedRate = taxRatesPercent.stream()
                .map(r -> r.divide(HUNDRED, Rounding.INTERMEDIATE))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return priceGross.divide(BigDecimal.ONE.add(combinedRate), Rounding.CLP_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Calcula {@code marginPct = round((netPrice - avgCost) / netPrice * 100, 2)} (API.md F.3).
     * Devuelve {@code null} si {@code netPrice <= 0}.
     */
    public static BigDecimal computeMarginPct(BigDecimal netPrice, BigDecimal avgCost) {
        if (netPrice == null || netPrice.signum() <= 0 || avgCost == null) {
            return null;
        }
        return netPrice.subtract(avgCost)
                .divide(netPrice, Rounding.INTERMEDIATE)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
