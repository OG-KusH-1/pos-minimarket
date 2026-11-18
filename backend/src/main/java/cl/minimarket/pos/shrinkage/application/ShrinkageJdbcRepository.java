package cl.minimarket.pos.shrinkage.application;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shrinkage.ShrinkageStatus;
import cl.minimarket.pos.shrinkage.ShrinkageType;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.LotSummaryView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.ProductSummaryView;
import cl.minimarket.pos.shrinkage.application.ShrinkageView.UserSummaryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repositorio JDBC para la persistencia y consulta de {@code shrinkages}
 * ({@code MODELO_DATOS.md} B.6).
 *
 * <p>Toda variación real de existencias se delega a {@link cl.minimarket.pos.inventory.StockApi}
 * desde {@link ShrinkageService}.
 */
@Repository
class ShrinkageJdbcRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "s.id",
            "number", "s.number",
            "status", "s.status",
            "shrinkageType", "s.shrinkage_type",
            "quantity", "s.quantity",
            "totalCost", "s.total_cost",
            "occurredAt", "s.occurred_at",
            "approvedAt", "s.approved_at",
            "createdAt", "s.created_at");

    private final JdbcClient jdbc;
    private final ProductCatalogApi productCatalogApi;

    ShrinkageJdbcRepository(JdbcClient jdbc, ProductCatalogApi productCatalogApi) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
    }

    /** Información mínima de lote para validar líneas con control de lotes. */
    record LotRow(Long id, Long productId, String lotNumber, LocalDate expiryDate) {
    }

    /** Fila bloqueada con {@code FOR UPDATE} para aprobación o rechazo. */
    record LockedShrinkage(
            Long id,
            String number,
            Long storeId,
            Long productId,
            Long lotId,
            ShrinkageType shrinkageType,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            String reason,
            Long reportedBy,
            ShrinkageStatus status,
            boolean affectsStock) {
    }

    Optional<ProductSnapshot> findProductById(Long productId) {
        List<ProductSnapshot> found = productCatalogApi.findByIds(List.of(productId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    boolean hasStockRow(Long storeId, Long productId) {
        return jdbc.sql("""
                SELECT 1
                FROM stock
                WHERE store_id = :storeId
                  AND product_id = :productId
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    Optional<LotRow> findLot(Long storeId, Long lotId) {
        return jdbc.sql("""
                SELECT id, product_id, lot_number, expiry_date
                FROM lots
                WHERE id = :lotId
                  AND store_id = :storeId
                """)
                .param("lotId", lotId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new LotRow(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("lot_number"),
                        rs.getObject("expiry_date", LocalDate.class)))
                .optional();
    }

    boolean userExists(Long userId) {
        return jdbc.sql("SELECT 1 FROM users WHERE id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    Long insertShrinkage(
            String number,
            Long storeId,
            Long productId,
            Long lotId,
            ShrinkageType shrinkageType,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal totalCost,
            String reason,
            Long evidenceFileId,
            Long responsibleUserId,
            Long reportedBy,
            ShrinkageStatus status,
            Long approvedBy,
            Instant approvedAt,
            Long transferLineId,
            Long saleReturnLineId,
            boolean autoApproved,
            boolean affectsStock,
            Long stockMovementId,
            Instant occurredAt,
            Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime utcOccurredAt = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
        OffsetDateTime utcApprovedAt = approvedAt == null
                ? null
                : OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC);

        return jdbc.sql("""
                INSERT INTO shrinkages (
                    number, store_id, product_id, lot_id, shrinkage_type,
                    quantity, unit_cost, total_cost, reason, evidence_file_id,
                    responsible_user_id, reported_by, status, approved_by, approved_at,
                    transfer_line_id, sale_return_line_id, auto_approved, affects_stock,
                    stock_movement_id, occurred_at,
                    created_at, created_by, updated_at, updated_by
                )
                VALUES (
                    :number, :storeId, :productId, :lotId, :shrinkageType,
                    :quantity, :unitCost, :totalCost, :reason, :evidenceFileId,
                    :responsibleUserId, :reportedBy, :status, :approvedBy, :approvedAt,
                    :transferLineId, :saleReturnLineId, :autoApproved, :affectsStock,
                    :stockMovementId, :occurredAt,
                    :now, :reportedBy, :now, :reportedBy
                )
                RETURNING id
                """)
                .param("number", number)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotId", lotId, Types.BIGINT)
                .param("shrinkageType", shrinkageType.name())
                .param("quantity", quantity)
                .param("unitCost", unitCost)
                .param("totalCost", totalCost)
                .param("reason", reason, Types.VARCHAR)
                .param("evidenceFileId", evidenceFileId, Types.BIGINT)
                .param("responsibleUserId", responsibleUserId)
                .param("reportedBy", reportedBy)
                .param("status", status.name())
                .param("approvedBy", approvedBy, Types.BIGINT)
                .param("approvedAt", utcApprovedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("transferLineId", transferLineId, Types.BIGINT)
                .param("saleReturnLineId", saleReturnLineId, Types.BIGINT)
                .param("autoApproved", autoApproved)
                .param("affectsStock", affectsStock)
                .param("stockMovementId", stockMovementId, Types.BIGINT)
                .param("occurredAt", utcOccurredAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    void updateStockMovement(Long shrinkageId, Long stockMovementId, Long updatedBy, Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE shrinkages
                SET stock_movement_id = :stockMovementId,
                    updated_at = :now,
                    updated_by = :updatedBy,
                    version = version + 1
                WHERE id = :id
                """)
                .param("stockMovementId", stockMovementId)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedBy", updatedBy)
                .param("id", shrinkageId)
                .update();
    }

    Optional<LockedShrinkage> lockForUpdate(Long storeId, Long shrinkageId) {
        return jdbc.sql("""
                SELECT id, number, store_id, product_id, lot_id, shrinkage_type,
                       quantity, unit_cost, total_cost, reason, reported_by,
                       status, affects_stock
                FROM shrinkages
                WHERE id = :id
                  AND store_id = :storeId
                FOR UPDATE
                """)
                .param("id", shrinkageId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new LockedShrinkage(
                        rs.getLong("id"),
                        rs.getString("number"),
                        rs.getLong("store_id"),
                        rs.getLong("product_id"),
                        rs.getObject("lot_id", Long.class),
                        ShrinkageType.valueOf(rs.getString("shrinkage_type")),
                        Rounding.quantity(rs.getBigDecimal("quantity")),
                        Rounding.unitCost(rs.getBigDecimal("unit_cost")),
                        Rounding.costTotal(rs.getBigDecimal("total_cost")),
                        rs.getString("reason"),
                        rs.getLong("reported_by"),
                        ShrinkageStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("affects_stock")))
                .optional();
    }

    void markApproved(Long shrinkageId, Long approverId, Instant approvedAt, Long stockMovementId) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(approvedAt, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE shrinkages
                SET status = :status,
                    approved_by = :approverId,
                    approved_at = :approvedAt,
                    stock_movement_id = :stockMovementId,
                    updated_at = :approvedAt,
                    updated_by = :approverId,
                    version = version + 1
                WHERE id = :id
                """)
                .param("status", ShrinkageStatus.APPROVED.name())
                .param("approverId", approverId)
                .param("approvedAt", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("stockMovementId", stockMovementId, Types.BIGINT)
                .param("id", shrinkageId)
                .update();
    }

    void markRejected(Long shrinkageId, Long approverId, Instant rejectedAt, String rejectionReason) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(rejectedAt, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE shrinkages
                SET status = :status,
                    approved_by = :approverId,
                    approved_at = :rejectedAt,
                    rejection_reason = :rejectionReason,
                    updated_at = :rejectedAt,
                    updated_by = :approverId,
                    version = version + 1
                WHERE id = :id
                """)
                .param("status", ShrinkageStatus.REJECTED.name())
                .param("approverId", approverId)
                .param("rejectedAt", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("rejectionReason", rejectionReason)
                .param("id", shrinkageId)
                .update();
    }

    Optional<ShrinkageView> findById(Long storeId, Long id, boolean includeCost) {
        List<ShrinkageView> rows = jdbc.sql(SELECT_BASE + """
                WHERE s.store_id = :storeId
                  AND s.id = :id
                """)
                .param("storeId", storeId)
                .param("id", id)
                .query((rs, rowNum) -> mapView(rs, includeCost))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    Page<ShrinkageView> findPage(
            Long storeId,
            ShrinkageFilter filter,
            Instant fromInstant,
            Instant toExclusiveInstant,
            Pageable pageable,
            boolean includeCost) {
        StringBuilder where = new StringBuilder(" WHERE s.store_id = :storeId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId);

        if (filter != null && filter.status() != null) {
            where.append(" AND s.status = :status ");
            params.put("status", filter.status().name());
        }
        if (filter != null && filter.shrinkageType() != null) {
            where.append(" AND s.shrinkage_type = :shrinkageType ");
            params.put("shrinkageType", filter.shrinkageType().name());
        }
        if (filter != null && filter.productId() != null) {
            where.append(" AND s.product_id = :productId ");
            params.put("productId", filter.productId());
        }
        if (fromInstant != null) {
            where.append(" AND s.occurred_at >= :fromInstant ");
            params.put("fromInstant", OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC));
        }
        if (toExclusiveInstant != null) {
            where.append(" AND s.occurred_at < :toExclusiveInstant ");
            params.put("toExclusiveInstant", OffsetDateTime.ofInstant(toExclusiveInstant, ZoneOffset.UTC));
        }

        String countSql = "SELECT COUNT(*) FROM shrinkages s " + where;
        Long total = applyParams(jdbc.sql(countSql), params).query(Long.class).single();
        if (total == 0L) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        String pageSql = SELECT_BASE + where + orderBy + " LIMIT :limit OFFSET :offset ";
        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        List<ShrinkageView> content = applyParams(jdbc.sql(pageSql), pageParams)
                .query((rs, rowNum) -> mapView(rs, includeCost))
                .list();
        return new PageImpl<>(content, pageable, total);
    }

    private static final String SELECT_BASE = """
            SELECT s.id,
                   s.number,
                   s.product_id,
                   p.sku AS product_sku,
                   p.name AS product_name,
                   s.lot_id,
                   l.lot_number,
                   l.expiry_date,
                   s.shrinkage_type,
                   s.quantity,
                   s.unit_cost,
                   s.total_cost,
                   s.reason,
                   s.evidence_file_id,
                   s.responsible_user_id,
                   u_resp.username AS resp_username,
                   u_resp.full_name AS resp_full_name,
                   s.reported_by,
                   u_rep.username AS rep_username,
                   u_rep.full_name AS rep_full_name,
                   s.status,
                   s.auto_approved,
                   s.affects_stock,
                   s.approved_by,
                   u_app.username AS app_username,
                   u_app.full_name AS app_full_name,
                   s.approved_at,
                   s.rejection_reason,
                   s.occurred_at,
                   s.transfer_line_id,
                   s.sale_return_line_id
            FROM shrinkages s
            JOIN products p ON p.id = s.product_id
            LEFT JOIN lots l ON l.id = s.lot_id
            JOIN users u_resp ON u_resp.id = s.responsible_user_id
            JOIN users u_rep ON u_rep.id = s.reported_by
            LEFT JOIN users u_app ON u_app.id = s.approved_by
            """;

    private static ShrinkageView mapView(ResultSet rs, boolean includeCost) throws SQLException {
        Long lotId = rs.getObject("lot_id", Long.class);
        LotSummaryView lot = lotId == null
                ? null
                : new LotSummaryView(
                        lotId,
                        rs.getString("lot_number"),
                        rs.getObject("expiry_date", LocalDate.class));

        Long approvedById = rs.getObject("approved_by", Long.class);
        UserSummaryView approvedBy = approvedById == null
                ? null
                : new UserSummaryView(
                        approvedById,
                        rs.getString("app_username"),
                        rs.getString("app_full_name"));

        BigDecimal unitCost = includeCost ? Rounding.unitCost(rs.getBigDecimal("unit_cost")) : null;
        BigDecimal totalCost = includeCost ? Rounding.costTotal(rs.getBigDecimal("total_cost")) : null;

        return new ShrinkageView(
                rs.getLong("id"),
                rs.getString("number"),
                new ProductSummaryView(
                        rs.getLong("product_id"),
                        rs.getString("product_sku"),
                        rs.getString("product_name")),
                lot,
                ShrinkageType.valueOf(rs.getString("shrinkage_type")),
                Rounding.quantity(rs.getBigDecimal("quantity")),
                unitCost,
                totalCost,
                rs.getString("reason"),
                rs.getObject("evidence_file_id", Long.class),
                new UserSummaryView(
                        rs.getLong("responsible_user_id"),
                        rs.getString("resp_username"),
                        rs.getString("resp_full_name")),
                new UserSummaryView(
                        rs.getLong("reported_by"),
                        rs.getString("rep_username"),
                        rs.getString("rep_full_name")),
                ShrinkageStatus.valueOf(rs.getString("status")),
                rs.getBoolean("auto_approved"),
                rs.getBoolean("affects_stock"),
                approvedBy,
                toUtcOffset(rs.getObject("approved_at", OffsetDateTime.class)),
                rs.getString("rejection_reason"),
                toUtcOffset(rs.getObject("occurred_at", OffsetDateTime.class)),
                rs.getObject("transfer_line_id", Long.class),
                rs.getObject("sale_return_line_id", Long.class));
    }

    private static OffsetDateTime toUtcOffset(OffsetDateTime odt) {
        return odt == null ? null : odt.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static JdbcClient.StatementSpec applyParams(
            JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof OffsetDateTime odt) {
                spec = spec.param(entry.getKey(), odt, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                spec = spec.param(entry.getKey(), entry.getValue());
            }
        }
        return spec;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY s.occurred_at DESC, s.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                String dir = order.isAscending() ? "ASC" : "DESC";
                clauses.add(column + " " + dir);
            }
        }
        if (clauses.isEmpty()) {
            return " ORDER BY s.occurred_at DESC, s.id DESC ";
        }
        clauses.add("s.id DESC");
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }
}
