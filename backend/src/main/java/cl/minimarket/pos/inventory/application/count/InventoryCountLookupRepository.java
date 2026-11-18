package cl.minimarket.pos.inventory.application.count;

import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryLookupView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CategoryRefView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountFilter;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.CountProgressView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.DifferenceSummaryView;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.LotStockInfo;
import cl.minimarket.pos.inventory.application.count.InventoryCountViews.UserRefView;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Consultas JDBC auxiliares y paginadas para el subdominio de tomas de inventario.
 *
 * <p>Consulta exclusivamente tablas del módulo {@code inventory}, además de {@code categories} y
 * {@code users} para referencias de solo lectura. Nunca consulta {@code products} directamente
 * (esa información se resuelve vía {@code ProductCatalogApi}).
 */
@Repository
class InventoryCountLookupRepository {

    private static final Map<String, String> COUNT_SORT_COLUMNS = Map.of(
            "id", "ic.id",
            "number", "ic.number",
            "countType", "ic.count_type",
            "status", "ic.status",
            "createdAt", "ic.created_at",
            "startedAt", "ic.created_at",
            "submittedAt", "ic.submitted_at",
            "approvedAt", "ic.approved_at"
    );

    private static final Map<String, String> LINE_SORT_COLUMNS = Map.of(
            "id", "icl.id",
            "productId", "icl.product_id",
            "theoreticalQty", "icl.theoretical_qty",
            "countedQty", "icl.counted_qty",
            "countedAt", "icl.counted_at"
    );

    private final JdbcClient jdbc;

    InventoryCountLookupRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    boolean existsOpenScope(Long storeId, Long categoryId) {
        long scopeKey = categoryId != null ? categoryId : 0L;
        Boolean exists = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM inventory_counts
                    WHERE store_id = :storeId
                      AND COALESCE(category_id, 0) = :scopeKey
                      AND status IN ('OPEN', 'SUBMITTED')
                )
                """)
                .param("storeId", storeId)
                .param("scopeKey", scopeKey)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    Optional<CategoryLookupView> findCategory(Long categoryId) {
        return jdbc.sql("""
                SELECT id, name, active
                FROM categories
                WHERE id = :categoryId
                """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new CategoryLookupView(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBoolean("active")))
                .optional();
    }

    Map<Long, UserRefView> findUserRefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRefView> rows = jdbc.sql("""
                SELECT id, full_name
                FROM users
                WHERE id IN (:userIds)
                """)
                .param("userIds", userIds)
                .query((rs, rowNum) -> new UserRefView(
                        rs.getLong("id"),
                        rs.getString("full_name")))
                .list();
        Map<Long, UserRefView> map = new HashMap<>(rows.size());
        for (UserRefView row : rows) {
            map.put(row.id(), row);
        }
        return map;
    }

    List<Long> findPositiveStockProductIds(Long storeId) {
        return jdbc.sql("""
                SELECT product_id
                FROM stock
                WHERE store_id = :storeId
                  AND quantity > 0
                ORDER BY product_id
                """)
                .param("storeId", storeId)
                .query(Long.class)
                .list();
    }

    Map<Long, BigDecimal> findStockQuantities(Long storeId, Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>(productIds.size());
        jdbc.sql("""
                SELECT product_id, quantity
                FROM stock
                WHERE store_id = :storeId
                  AND product_id IN (:productIds)
                """)
                .param("storeId", storeId)
                .param("productIds", productIds)
                .query((rs, rowNum) -> {
                    result.put(rs.getLong("product_id"), Rounding.quantity(rs.getBigDecimal("quantity")));
                    return null;
                })
                .list();
        return result;
    }

    Map<Long, List<LotStockInfo>> findActiveLotsByProduct(Long storeId, Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<LotStockInfo> lots = jdbc.sql("""
                SELECT id, product_id, lot_number, quantity, unit_cost
                FROM lots
                WHERE store_id = :storeId
                  AND product_id IN (:productIds)
                  AND status = 'ACTIVE'
                  AND quantity > 0
                ORDER BY product_id, expiry_date NULLS LAST, id
                """)
                .param("storeId", storeId)
                .param("productIds", productIds)
                .query((rs, rowNum) -> new LotStockInfo(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("lot_number"),
                        Rounding.quantity(rs.getBigDecimal("quantity")),
                        rs.getBigDecimal("unit_cost")))
                .list();
        Map<Long, List<LotStockInfo>> grouped = new HashMap<>();
        for (LotStockInfo lot : lots) {
            grouped.computeIfAbsent(lot.productId(), ignored -> new ArrayList<>()).add(lot);
        }
        return grouped;
    }

    Map<Long, BigDecimal> findLotQuantities(Long storeId, Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>(lotIds.size());
        jdbc.sql("""
                SELECT id, quantity
                FROM lots
                WHERE store_id = :storeId
                  AND id IN (:lotIds)
                """)
                .param("storeId", storeId)
                .param("lotIds", lotIds)
                .query((rs, rowNum) -> {
                    result.put(rs.getLong("id"), Rounding.quantity(rs.getBigDecimal("quantity")));
                    return null;
                })
                .list();
        return result;
    }

    Map<Long, String> findLotNumbers(Long storeId, Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(lotIds.size());
        jdbc.sql("""
                SELECT id, lot_number
                FROM lots
                WHERE store_id = :storeId
                  AND id IN (:lotIds)
                """)
                .param("storeId", storeId)
                .param("lotIds", lotIds)
                .query((rs, rowNum) -> {
                    result.put(rs.getLong("id"), rs.getString("lot_number"));
                    return null;
                })
                .list();
        return result;
    }

    List<Long> findDistinctProductIdsForCount(Long countId) {
        return jdbc.sql("""
                SELECT DISTINCT product_id
                FROM inventory_count_lines
                WHERE count_id = :countId
                """)
                .param("countId", countId)
                .query(Long.class)
                .list();
    }

    Page<CountHeaderRow> findCounts(Long storeId, CountFilter filter, Pageable pageable) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildCountsWhere(storeId, filter, params);

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM inventory_counts ic
                """ + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort(), COUNT_SORT_COLUMNS, "ic.created_at DESC, ic.id DESC");
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        List<CountHeaderRow> rows = jdbc.sql("""
                SELECT ic.id,
                       ic.number,
                       ic.store_id,
                       ic.count_type,
                       ic.category_id,
                       c.name AS category_name,
                       ic.status,
                       ic.blind,
                       ic.started_by,
                       su.full_name AS started_by_name,
                       ic.created_at AS started_at,
                       ic.submitted_at,
                       ic.approved_by,
                       au.full_name AS approved_by_name,
                       ic.approved_at,
                       ic.rejection_reason,
                       ic.notes,
                       ic.version
                FROM inventory_counts ic
                LEFT JOIN categories c ON c.id = ic.category_id
                JOIN users su ON su.id = ic.started_by
                LEFT JOIN users au ON au.id = ic.approved_by
                """ + where + "\n" + orderBy + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> mapCountHeader(rs))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    Map<Long, CountProgressView> findProgressByCountIds(Collection<Long> countIds) {
        if (countIds == null || countIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CountProgressView> map = new HashMap<>(countIds.size());
        jdbc.sql("""
                SELECT count_id,
                       COUNT(*) AS total_lines,
                       COUNT(counted_at) AS counted_lines
                FROM inventory_count_lines
                WHERE count_id IN (:countIds)
                GROUP BY count_id
                """)
                .param("countIds", countIds)
                .query((rs, rowNum) -> {
                    map.put(
                            rs.getLong("count_id"),
                            new CountProgressView(rs.getLong("total_lines"), rs.getLong("counted_lines")));
                    return null;
                })
                .list();
        return map;
    }

    Map<Long, DifferenceSummaryView> findDifferenceSummariesByCountIds(Collection<Long> countIds) {
        if (countIds == null || countIds.isEmpty()) {
            return Map.of();
        }
        List<DiffAccumulatorRow> rows = jdbc.sql("""
                SELECT count_id, theoretical_qty, counted_qty, unit_cost
                FROM inventory_count_lines
                WHERE count_id IN (:countIds)
                  AND counted_at IS NOT NULL
                  AND theoretical_qty IS NOT NULL
                  AND counted_qty IS NOT NULL
                  AND counted_qty <> theoretical_qty
                """)
                .param("countIds", countIds)
                .query((rs, rowNum) -> new DiffAccumulatorRow(
                        rs.getLong("count_id"),
                        rs.getBigDecimal("theoretical_qty"),
                        rs.getBigDecimal("counted_qty"),
                        rs.getBigDecimal("unit_cost")))
                .list();

        Map<Long, DiffAccumulator> accByCount = new HashMap<>(countIds.size());
        for (Long countId : countIds) {
            accByCount.put(countId, new DiffAccumulator());
        }
        for (DiffAccumulatorRow row : rows) {
            accByCount.computeIfAbsent(row.countId(), ignored -> new DiffAccumulator()).accept(row);
        }
        Map<Long, DifferenceSummaryView> result = new HashMap<>(accByCount.size());
        for (Map.Entry<Long, DiffAccumulator> entry : accByCount.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toView());
        }
        return result;
    }

    Page<CountLineRow> findCountLines(
            Long countId,
            Boolean counted,
            Collection<Long> productIdsFilter,
            Pageable pageable) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("countId", countId);
        StringBuilder where = new StringBuilder("WHERE icl.count_id = :countId");
        if (counted != null) {
            where.append(counted ? " AND icl.counted_at IS NOT NULL" : " AND icl.counted_at IS NULL");
        }
        if (productIdsFilter != null) {
            where.append(" AND icl.product_id IN (:productIds)");
            params.put("productIds", productIdsFilter);
        }

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM inventory_count_lines icl
                """ + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort(), LINE_SORT_COLUMNS, "icl.id ASC");
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        List<CountLineRow> rows = jdbc.sql("""
                SELECT icl.id,
                       icl.product_id,
                       icl.lot_id,
                       l.lot_number,
                       icl.theoretical_qty,
                       icl.counted_qty,
                       icl.unit_cost,
                       icl.counted_at,
                       icl.stock_movement_id
                FROM inventory_count_lines icl
                LEFT JOIN lots l ON l.id = icl.lot_id
                """ + where + "\n" + orderBy + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> mapCountLine(rs))
                .list();

        return new PageImpl<>(rows, pageable, total);
    }

    private static String buildCountsWhere(Long storeId, CountFilter filter, Map<String, Object> params) {
        params.put("storeId", storeId);
        StringBuilder sb = new StringBuilder("WHERE ic.store_id = :storeId");
        if (filter != null && filter.status() != null) {
            sb.append(" AND ic.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter != null && filter.countType() != null) {
            sb.append(" AND ic.count_type = :countType");
            params.put("countType", filter.countType().name());
        }
        return sb.toString();
    }

    private static String buildOrderBy(Sort sort, Map<String, String> columns, String defaultClause) {
        if (sort == null || sort.isUnsorted()) {
            return "ORDER BY " + defaultClause;
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = columns.get(order.getProperty());
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (clauses.isEmpty()) {
            return "ORDER BY " + defaultClause;
        }
        clauses.add(defaultClause.contains("icl.id") ? "icl.id ASC" : "ic.id DESC");
        return "ORDER BY " + String.join(", ", clauses);
    }

    private static CountHeaderRow mapCountHeader(ResultSet rs) throws SQLException {
        long categoryIdVal = rs.getLong("category_id");
        CategoryRefView category = rs.wasNull()
                ? null
                : new CategoryRefView(categoryIdVal, rs.getString("category_name"));

        UserRefView startedBy = new UserRefView(rs.getLong("started_by"), rs.getString("started_by_name"));

        long approvedByVal = rs.getLong("approved_by");
        UserRefView approvedBy = rs.wasNull()
                ? null
                : new UserRefView(approvedByVal, rs.getString("approved_by_name"));

        return new CountHeaderRow(
                rs.getLong("id"),
                rs.getString("number"),
                rs.getLong("store_id"),
                CountType.valueOf(rs.getString("count_type")),
                category,
                CountStatus.valueOf(rs.getString("status")),
                rs.getBoolean("blind"),
                startedBy,
                toInstant(rs, "started_at"),
                toInstant(rs, "submitted_at"),
                approvedBy,
                toInstant(rs, "approved_at"),
                rs.getString("rejection_reason"),
                rs.getString("notes"),
                rs.getLong("version"));
    }

    private static CountLineRow mapCountLine(ResultSet rs) throws SQLException {
        long lotIdVal = rs.getLong("lot_id");
        Long lotId = rs.wasNull() ? null : lotIdVal;
        long movementIdVal = rs.getLong("stock_movement_id");
        Long stockMovementId = rs.wasNull() ? null : movementIdVal;
        return new CountLineRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                lotId,
                rs.getString("lot_number"),
                rs.getBigDecimal("theoretical_qty"),
                rs.getBigDecimal("counted_qty"),
                rs.getBigDecimal("unit_cost"),
                toInstant(rs, "counted_at"),
                stockMovementId);
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt != null ? odt.toInstant() : null;
    }

    record CountHeaderRow(
            Long id,
            String number,
            Long storeId,
            CountType countType,
            CategoryRefView category,
            CountStatus status,
            boolean blind,
            UserRefView startedBy,
            Instant startedAt,
            Instant submittedAt,
            UserRefView approvedBy,
            Instant approvedAt,
            String rejectionReason,
            String notes,
            Long version) {
    }

    record CountLineRow(
            Long id,
            Long productId,
            Long lotId,
            String lotNumber,
            BigDecimal theoreticalQty,
            BigDecimal countedQty,
            BigDecimal unitCost,
            Instant countedAt,
            Long stockMovementId) {
    }

    private record DiffAccumulatorRow(
            Long countId,
            BigDecimal theoreticalQty,
            BigDecimal countedQty,
            BigDecimal unitCost) {
    }

    private static final class DiffAccumulator {
        private long lines;
        private BigDecimal valueIn = BigDecimal.ZERO;
        private BigDecimal valueOut = BigDecimal.ZERO;

        void accept(DiffAccumulatorRow row) {
            BigDecimal delta = row.countedQty().subtract(row.theoreticalQty());
            if (delta.signum() == 0) {
                return;
            }
            lines++;
            BigDecimal cost = row.unitCost() != null ? row.unitCost() : BigDecimal.ZERO;
            if (delta.signum() > 0) {
                valueIn = valueIn.add(delta.multiply(cost));
            } else {
                valueOut = valueOut.add(delta.abs().multiply(cost));
            }
        }

        DifferenceSummaryView toView() {
            return new DifferenceSummaryView(
                    lines,
                    Rounding.costTotal(valueIn),
                    Rounding.costTotal(valueOut));
        }
    }
}
