package cl.minimarket.pos.inventory.application.adjustment;

import cl.minimarket.pos.catalog.ProductCatalogApi;
import cl.minimarket.pos.catalog.ProductCatalogApi.ProductSnapshot;
import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.catalog.UnitOfMeasure;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentView.LineView;
import cl.minimarket.pos.inventory.application.adjustment.StockAdjustmentView.UserSummaryView;
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
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Repositorio JDBC para la persistencia y consulta de {@code stock_adjustments} y
 * {@code stock_adjustment_lines} ({@code MODELO_DATOS.md} B.5).
 *
 * <p>Toda modificación sobre {@code stock} y {@code stock_movements} se delega a
 * {@link cl.minimarket.pos.inventory.StockApi} desde {@link StockAdjustmentService}; este
 * repositorio únicamente gestiona los documentos de ajuste y los metadatos de lectura necesarios.
 */
@Repository
class StockAdjustmentJdbcRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "sa.id",
            "number", "sa.number",
            "status", "sa.status",
            "reasonCode", "sa.reason_code",
            "totalCostImpact", "sa.total_cost_impact",
            "createdAt", "sa.created_at",
            "approvedAt", "sa.approved_at"
    );

    private final JdbcClient jdbc;
    private final ProductCatalogApi productCatalogApi;

    StockAdjustmentJdbcRepository(JdbcClient jdbc, ProductCatalogApi productCatalogApi) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.productCatalogApi = Objects.requireNonNull(productCatalogApi, "productCatalogApi");
    }

    /** Información mínima de producto requerida para validar y valorizar un ajuste. */
    record ProductRow(
            Long id,
            String sku,
            String name,
            SaleType saleType,
            UnitOfMeasure unitOfMeasure,
            BigDecimal avgCost,
            boolean lotControlled,
            Integer shelfLifeDays
    ) {
    }

    /** Información mínima de lote para validar líneas con control de lotes. */
    record LotRow(
            Long id,
            Long productId,
            String lotNumber,
            LocalDate expiryDate
    ) {
    }

    /** Cabecera bloqueada con {@code FOR UPDATE} durante aprobación o rechazo. */
    record LockedAdjustment(
            Long id,
            String number,
            Long storeId,
            AdjustmentReason reasonCode,
            String notes,
            AdjustmentStatus status,
            BigDecimal totalCostImpact,
            Long requestedBy
    ) {
    }

    /** Línea de ajuste con metadatos de lote para ejecutar movimientos al aprobar. */
    record AdjustmentLineForExecution(
            Long id,
            Long productId,
            Long lotId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantityDelta,
            BigDecimal unitCost
    ) {
    }

    /** Carga los productos indicados en lote a través de {@link ProductCatalogApi}. */
    Map<Long, ProductRow> findProductsByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = List.copyOf(new HashSet<>(productIds));
        List<ProductSnapshot> snapshots = productCatalogApi.findByIds(distinctIds);
        Map<Long, ProductRow> byId = new HashMap<>(snapshots.size());
        for (ProductSnapshot snapshot : snapshots) {
            BigDecimal avgCost = snapshot.avgCost() != null
                    ? Rounding.unitCost(snapshot.avgCost())
                    : Rounding.unitCost(BigDecimal.ZERO);
            byId.put(snapshot.id(), new ProductRow(
                    snapshot.id(),
                    snapshot.sku(),
                    snapshot.name(),
                    snapshot.saleType(),
                    snapshot.unitOfMeasure(),
                    avgCost,
                    snapshot.lotControlled(),
                    snapshot.shelfLifeDays()));
        }
        return byId;
    }

    /** Devuelve los identificadores de productos que tienen fila de stock en el local activo. */
    Set<Long> findStockProductIds(Long storeId, Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Set.of();
        }
        List<Long> distinctIds = List.copyOf(new HashSet<>(productIds));
        return new HashSet<>(jdbc.sql("""
                SELECT product_id
                FROM stock
                WHERE store_id = :storeId
                  AND product_id IN (:ids)
                """)
                .param("storeId", storeId)
                .param("ids", distinctIds)
                .query(Long.class)
                .list());
    }

    /** Carga los lotes existentes en el local activo por sus identificadores. */
    Map<Long, LotRow> findLotsByIds(Long storeId, Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = List.copyOf(new HashSet<>(lotIds));
        List<LotRow> rows = jdbc.sql("""
                SELECT id, product_id, lot_number, expiry_date
                FROM lots
                WHERE store_id = :storeId
                  AND id IN (:ids)
                """)
                .param("storeId", storeId)
                .param("ids", distinctIds)
                .query((rs, rowNum) -> new LotRow(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("lot_number"),
                        rs.getObject("expiry_date", LocalDate.class)))
                .list();

        Map<Long, LotRow> byId = new HashMap<>(rows.size());
        for (LotRow row : rows) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    /**
     * Resuelve el lote existente para {@code (storeId, productId, lotNumber, expiryDate)} o crea
     * un lote marcador con {@code quantity = 0} y {@code status = 'DEPLETED'} para conservar el
     * número de lote y fecha de vencimiento sin alterar el stock mientras el ajuste está pendiente.
     */
    Long findOrCreatePlaceholderLot(
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal unitCost,
            Long userId,
            Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

        Optional<Long> insertedId = jdbc.sql("""
                INSERT INTO lots (
                    store_id, product_id, lot_number, expiry_date,
                    quantity, unit_cost, received_at, status,
                    created_at, created_by, updated_at, updated_by
                )
                VALUES (
                    :storeId, :productId, :lotNumber, :expiryDate,
                    0.000, :unitCost, :now, 'DEPLETED',
                    :now, :userId, :now, :userId
                )
                ON CONFLICT (store_id, product_id, lot_number, COALESCE(expiry_date, 'infinity'::date))
                DO NOTHING
                RETURNING id
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotNumber)
                .param("expiryDate", expiryDate, Types.DATE)
                .param("unitCost", unitCost)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("userId", userId)
                .query(Long.class)
                .optional();

        if (insertedId.isPresent()) {
            return insertedId.get();
        }

        return jdbc.sql("""
                SELECT id
                FROM lots
                WHERE store_id = :storeId
                  AND product_id = :productId
                  AND lot_number = :lotNumber
                  AND COALESCE(expiry_date, 'infinity'::date) = COALESCE(CAST(:expiryDate AS date), 'infinity'::date)
                """)
                .param("storeId", storeId)
                .param("productId", productId)
                .param("lotNumber", lotNumber)
                .param("expiryDate", expiryDate, Types.DATE)
                .query(Long.class)
                .single();
    }

    /** Inserta la cabecera de un ajuste en {@code stock_adjustments} y retorna su identificador. */
    Long insertAdjustment(
            String number,
            Long storeId,
            AdjustmentReason reasonCode,
            String notes,
            AdjustmentStatus status,
            BigDecimal totalCostImpact,
            Long requestedBy,
            Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO stock_adjustments (
                    number, store_id, reason_code, notes, status,
                    total_cost_impact, requested_by,
                    created_at, created_by, updated_at, updated_by
                )
                VALUES (
                    :number, :storeId, :reasonCode, :notes, :status,
                    :totalCostImpact, :requestedBy,
                    :now, :requestedBy, :now, :requestedBy
                )
                RETURNING id
                """)
                .param("number", number)
                .param("storeId", storeId)
                .param("reasonCode", reasonCode.name())
                .param("notes", notes, Types.VARCHAR)
                .param("status", status.name())
                .param("totalCostImpact", totalCostImpact)
                .param("requestedBy", requestedBy)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    /** Inserta una línea del ajuste en {@code stock_adjustment_lines}. */
    Long insertLine(
            Long adjustmentId,
            Long productId,
            Long lotId,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO stock_adjustment_lines (
                    adjustment_id, product_id, lot_id, quantity_delta, unit_cost, created_at
                )
                VALUES (
                    :adjustmentId, :productId, :lotId, :quantityDelta, :unitCost, :now
                )
                RETURNING id
                """)
                .param("adjustmentId", adjustmentId)
                .param("productId", productId)
                .param("lotId", lotId, Types.BIGINT)
                .param("quantityDelta", quantityDelta)
                .param("unitCost", unitCost)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();
    }

    /** Bloquea la cabecera del ajuste dentro del local activo para evitar transiciones concurrentes. */
    Optional<LockedAdjustment> lockHeaderForUpdate(Long storeId, Long adjustmentId) {
        return jdbc.sql("""
                SELECT id, number, store_id, reason_code, notes, status, total_cost_impact, requested_by
                FROM stock_adjustments
                WHERE id = :id
                  AND store_id = :storeId
                FOR UPDATE
                """)
                .param("id", adjustmentId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new LockedAdjustment(
                        rs.getLong("id"),
                        rs.getString("number"),
                        rs.getLong("store_id"),
                        AdjustmentReason.valueOf(rs.getString("reason_code")),
                        rs.getString("notes"),
                        AdjustmentStatus.valueOf(rs.getString("status")),
                        Rounding.costTotal(rs.getBigDecimal("total_cost_impact")),
                        rs.getLong("requested_by")))
                .optional();
    }

    /** Obtiene las líneas del ajuste ordenadas por {@code id} para ejecutar su movimiento al aprobar. */
    List<AdjustmentLineForExecution> findLinesForExecution(Long adjustmentId) {
        return jdbc.sql("""
                SELECT sal.id,
                       sal.product_id,
                       sal.lot_id,
                       l.lot_number,
                       l.expiry_date,
                       sal.quantity_delta,
                       sal.unit_cost
                FROM stock_adjustment_lines sal
                LEFT JOIN lots l ON l.id = sal.lot_id
                WHERE sal.adjustment_id = :adjustmentId
                ORDER BY sal.id
                """)
                .param("adjustmentId", adjustmentId)
                .query((rs, rowNum) -> new AdjustmentLineForExecution(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getObject("lot_id", Long.class),
                        rs.getString("lot_number"),
                        rs.getObject("expiry_date", LocalDate.class),
                        Rounding.quantity(rs.getBigDecimal("quantity_delta")),
                        Rounding.unitCost(rs.getBigDecimal("unit_cost"))))
                .list();
    }

    /** Enlaza una línea de ajuste con el movimiento de kardex generado al aprobar. */
    void updateLineMovementId(Long lineId, Long movementId) {
        jdbc.sql("""
                UPDATE stock_adjustment_lines
                SET stock_movement_id = :movementId
                WHERE id = :lineId
                """)
                .param("movementId", movementId)
                .param("lineId", lineId)
                .update();
    }

    /** Marca la cabecera del ajuste como aprobada. */
    void markApproved(Long adjustmentId, Long approverId, Instant now) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE stock_adjustments
                SET status = 'APPROVED',
                    approved_by = :approverId,
                    approved_at = :now,
                    updated_by = :approverId,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :id
                """)
                .param("approverId", approverId)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", adjustmentId)
                .update();
    }

    /** Marca la cabecera del ajuste como rechazada con su motivo obligatorio. */
    void markRejected(Long adjustmentId, Long approverId, Instant now, String rejectionReason) {
        OffsetDateTime utcNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE stock_adjustments
                SET status = 'REJECTED',
                    approved_by = :approverId,
                    approved_at = :now,
                    rejection_reason = :rejectionReason,
                    updated_by = :approverId,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :id
                """)
                .param("approverId", approverId)
                .param("now", utcNow, Types.TIMESTAMP_WITH_TIMEZONE)
                .param("rejectionReason", rejectionReason)
                .param("id", adjustmentId)
                .update();
    }

    /** Busca un ajuste por identificador dentro del local activo. */
    Optional<StockAdjustmentView> findById(Long storeId, Long adjustmentId, boolean includeCost) {
        Optional<HeaderRow> header = jdbc.sql(HEADER_SELECT + """
                WHERE sa.store_id = :storeId
                  AND sa.id = :id
                """)
                .param("storeId", storeId)
                .param("id", adjustmentId)
                .query((rs, rowNum) -> mapHeader(rs, includeCost))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<LineView> lines = findLinesByAdjustmentIds(List.of(adjustmentId), includeCost)
                .getOrDefault(adjustmentId, List.of());
        return Optional.of(toView(header.get(), lines));
    }

    /** Lista de forma paginada los ajustes del local activo aplicando los filtros opcionales. */
    Page<StockAdjustmentView> findPage(
            Long storeId,
            StockAdjustmentFilter filter,
            Instant fromInstant,
            Instant toExclusiveInstant,
            Pageable pageable,
            boolean includeCost) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(storeId, filter, fromInstant, toExclusiveInstant, params);

        long total = jdbc.sql("""
                SELECT COUNT(*)
                FROM stock_adjustments sa
                """ + where)
                .params(params)
                .query(Long.class)
                .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        List<HeaderRow> headers = jdbc.sql(HEADER_SELECT + "\n" + where + "\n" + orderBy + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> mapHeader(rs, includeCost))
                .list();

        List<Long> ids = headers.stream().map(HeaderRow::id).toList();
        Map<Long, List<LineView>> linesByAdjustment = findLinesByAdjustmentIds(ids, includeCost);

        List<StockAdjustmentView> views = headers.stream()
                .map(h -> toView(h, linesByAdjustment.getOrDefault(h.id(), List.of())))
                .toList();
        return new PageImpl<>(views, pageable, total);
    }

    private static final String HEADER_SELECT = """
            SELECT sa.id,
                   sa.number,
                   sa.status,
                   sa.reason_code,
                   sa.notes,
                   sa.total_cost_impact,
                   sa.requested_by,
                   req.username AS requested_by_username,
                   req.full_name AS requested_by_full_name,
                   sa.approved_by,
                   app.username AS approved_by_username,
                   app.full_name AS approved_by_full_name,
                   sa.approved_at,
                   sa.rejection_reason,
                   sa.created_at
            FROM stock_adjustments sa
            JOIN users req ON req.id = sa.requested_by
            LEFT JOIN users app ON app.id = sa.approved_by
            """;

    private record HeaderRow(
            Long id,
            String number,
            AdjustmentStatus status,
            AdjustmentReason reasonCode,
            String notes,
            BigDecimal totalCostImpact,
            UserSummaryView requestedBy,
            UserSummaryView approvedBy,
            OffsetDateTime approvedAt,
            String rejectionReason,
            OffsetDateTime createdAt
    ) {
    }

    private Map<Long, List<LineView>> findLinesByAdjustmentIds(List<Long> adjustmentIds, boolean includeCost) {
        if (adjustmentIds.isEmpty()) {
            return Map.of();
        }
        record RawAdjustmentLineRow(
                Long id,
                Long adjustmentId,
                Long productId,
                Long lotId,
                String lotNumber,
                BigDecimal quantityDelta,
                BigDecimal unitCost,
                Long stockMovementId
        ) {
        }
        List<RawAdjustmentLineRow> rows = jdbc.sql("""
                SELECT sal.id,
                       sal.adjustment_id,
                       sal.product_id,
                       sal.lot_id,
                       l.lot_number,
                       sal.quantity_delta,
                       sal.unit_cost,
                       sal.stock_movement_id
                FROM stock_adjustment_lines sal
                LEFT JOIN lots l ON l.id = sal.lot_id
                WHERE sal.adjustment_id IN (:ids)
                ORDER BY sal.adjustment_id, sal.id
                """)
                .param("ids", adjustmentIds)
                .query((rs, rowNum) -> new RawAdjustmentLineRow(
                        rs.getLong("id"),
                        rs.getLong("adjustment_id"),
                        rs.getLong("product_id"),
                        rs.getObject("lot_id", Long.class),
                        rs.getString("lot_number"),
                        Rounding.quantity(rs.getBigDecimal("quantity_delta")),
                        includeCost ? Rounding.unitCost(rs.getBigDecimal("unit_cost")) : null,
                        rs.getObject("stock_movement_id", Long.class)))
                .list();

        if (rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> productIds = new HashSet<>(rows.size());
        for (RawAdjustmentLineRow row : rows) {
            productIds.add(row.productId());
        }
        Map<Long, ProductSnapshot> productsById = new HashMap<>(productIds.size());
        for (ProductSnapshot snapshot : productCatalogApi.findByIds(productIds)) {
            productsById.put(snapshot.id(), snapshot);
        }

        Map<Long, List<LineView>> grouped = new LinkedHashMap<>();
        for (RawAdjustmentLineRow row : rows) {
            ProductSnapshot product = productsById.get(row.productId());
            LineView lineView = new LineView(
                    row.id(),
                    row.productId(),
                    product != null ? product.sku() : null,
                    product != null ? product.name() : null,
                    row.lotId(),
                    row.lotNumber(),
                    row.quantityDelta(),
                    row.unitCost(),
                    row.stockMovementId());
            grouped.computeIfAbsent(row.adjustmentId(), ignored -> new ArrayList<>()).add(lineView);
        }
        return grouped;
    }

    private static HeaderRow mapHeader(ResultSet rs, boolean includeCost) throws SQLException {
        UserSummaryView requestedBy = new UserSummaryView(
                rs.getLong("requested_by"),
                rs.getString("requested_by_username"),
                rs.getString("requested_by_full_name"));

        Long approvedById = rs.getObject("approved_by", Long.class);
        UserSummaryView approvedBy = approvedById == null ? null : new UserSummaryView(
                approvedById,
                rs.getString("approved_by_username"),
                rs.getString("approved_by_full_name"));

        BigDecimal totalCostImpact = includeCost
                ? Rounding.costTotal(rs.getBigDecimal("total_cost_impact"))
                : null;

        return new HeaderRow(
                rs.getLong("id"),
                rs.getString("number"),
                AdjustmentStatus.valueOf(rs.getString("status")),
                AdjustmentReason.valueOf(rs.getString("reason_code")),
                rs.getString("notes"),
                totalCostImpact,
                requestedBy,
                approvedBy,
                rs.getObject("approved_at", OffsetDateTime.class),
                rs.getString("rejection_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static StockAdjustmentView toView(HeaderRow header, List<LineView> lines) {
        return new StockAdjustmentView(
                header.id(),
                header.number(),
                header.status(),
                header.reasonCode(),
                header.notes(),
                header.totalCostImpact(),
                header.requestedBy(),
                header.approvedBy(),
                header.approvedAt(),
                header.rejectionReason(),
                header.createdAt(),
                List.copyOf(lines));
    }

    private static String buildWhere(
            Long storeId,
            StockAdjustmentFilter filter,
            Instant fromInstant,
            Instant toExclusiveInstant,
            Map<String, Object> params) {
        StringBuilder where = new StringBuilder("WHERE sa.store_id = :storeId");
        params.put("storeId", storeId);

        if (filter != null && filter.status() != null) {
            where.append(" AND sa.status = :status");
            params.put("status", filter.status().name());
        }
        if (fromInstant != null) {
            where.append(" AND sa.created_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(fromInstant, ZoneOffset.UTC));
        }
        if (toExclusiveInstant != null) {
            where.append(" AND sa.created_at < :toExclusive");
            params.put("toExclusive", OffsetDateTime.ofInstant(toExclusiveInstant, ZoneOffset.UTC));
        }
        return where.toString();
    }

    private static String buildOrderBy(Sort sort) {
        List<String> clauses = new ArrayList<>();
        boolean includesId = false;
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
                if ("id".equals(order.getProperty())) {
                    includesId = true;
                }
            }
        }
        if (clauses.isEmpty()) {
            return "ORDER BY sa.created_at DESC, sa.id DESC";
        }
        if (!includesId) {
            clauses.add("sa.id DESC");
        }
        return "ORDER BY " + String.join(", ", clauses);
    }
}
