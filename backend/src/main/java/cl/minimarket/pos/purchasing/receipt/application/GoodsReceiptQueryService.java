package cl.minimarket.pos.purchasing.receipt.application;

import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import cl.minimarket.pos.purchasing.receipt.domain.GoodsReceiptStatus;
import cl.minimarket.pos.purchasing.receipt.domain.SupplierDocumentType;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas de recepciones de mercadería ({@code goods_receipts}) y prellenado desde órdenes de compra.
 *
 * <p>Oculta los campos de costo ({@code unitCostNet}, {@code netTotal}, {@code oldAvgCost},
 * {@code newAvgCost}) cuando el usuario autenticado no posee el permiso {@code catalog:cost:read}.
 */
@Service
public class GoodsReceiptQueryService {

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public GoodsReceiptQueryService(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Filtro de búsqueda paginada de recepciones. */
    public record GoodsReceiptFilter(
            GoodsReceiptStatus status,
            Long supplierId,
            Long purchaseOrderId,
            LocalDate from,
            LocalDate to
    ) {
    }

    /** Proyección resumida para listados de recepciones. */
    public record GoodsReceiptSummaryView(
            Long id,
            String number,
            String supplierName,
            String purchaseOrderNumber,
            GoodsReceiptStatus status,
            Instant receivedAt,
            BigDecimal netTotal
    ) {
    }

    /** Datos del proveedor asociados a la recepción. */
    public record SupplierView(Long id, String rut, String legalName, String tradeName) {
    }

    /** Línea de detalle de una recepción. */
    public record GoodsReceiptLineView(
            Long id,
            Long purchaseOrderLineId,
            Long productId,
            String sku,
            String name,
            BigDecimal qtyExpected,
            BigDecimal qtyReceived,
            BigDecimal qtyRejected,
            String differenceReason,
            BigDecimal unitCostNet,
            String lotNumber,
            LocalDate expiryDate,
            Long lotId
    ) {
    }

    /** Cambio de costo promedio ponderado resultante al confirmar una recepción. */
    public record CostChangeView(Long productId, BigDecimal oldAvgCost, BigDecimal newAvgCost) {
    }

    /** Vista completa de una recepción de mercadería. */
    public record GoodsReceiptDetail(
            Long id,
            String number,
            Long purchaseOrderId,
            String purchaseOrderNumber,
            SupplierView supplier,
            GoodsReceiptStatus status,
            SupplierDocumentType supplierDocumentType,
            String supplierDocumentNumber,
            Instant receivedAt,
            Long receivedBy,
            BigDecimal netTotal,
            String notes,
            List<GoodsReceiptLineView> lines,
            List<CostChangeView> costChanges,
            Long version
    ) {
    }

    /** Plantilla de borrador prellenada desde una orden de compra pendiente. */
    public record DraftLineTemplate(
            Long purchaseOrderLineId,
            Long productId,
            BigDecimal qtyReceived,
            BigDecimal qtyRejected,
            BigDecimal unitCostNet
    ) {
    }

    /** Resultado de {@code GET /purchase-orders/{id}/receipt-draft}. */
    public record DraftTemplate(
            Long purchaseOrderId,
            Long supplierId,
            SupplierDocumentType supplierDocumentType,
            List<DraftLineTemplate> lines
    ) {
    }

    /** Lista paginada de recepciones del local especificado. */
    @Transactional(readOnly = true)
    public Page<GoodsReceiptSummaryView> list(Long storeId, GoodsReceiptFilter filter, Pageable pageable) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(pageable, "pageable");

        StringBuilder where = new StringBuilder(" WHERE gr.store_id = :storeId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId);

        if (filter != null) {
            if (filter.status() != null) {
                where.append(" AND gr.status = :status ");
                params.put("status", filter.status().name());
            }
            if (filter.supplierId() != null) {
                where.append(" AND gr.supplier_id = :supplierId ");
                params.put("supplierId", filter.supplierId());
            }
            if (filter.purchaseOrderId() != null) {
                where.append(" AND gr.purchase_order_id = :purchaseOrderId ");
                params.put("purchaseOrderId", filter.purchaseOrderId());
            }
            if (filter.from() != null) {
                Instant fromInstant = filter.from().atStartOfDay(clock.zone()).toInstant();
                where.append(" AND COALESCE(gr.received_at, gr.created_at) >= :fromInstant ");
                params.put("fromInstant", Timestamp.from(fromInstant));
            }
            if (filter.to() != null) {
                Instant toExclusive = filter.to().plusDays(1).atStartOfDay(clock.zone()).toInstant();
                where.append(" AND COALESCE(gr.received_at, gr.created_at) < :toExclusive ");
                params.put("toExclusive", Timestamp.from(toExclusive));
            }
        }

        String fromSql = """
                 FROM goods_receipts gr
                 JOIN suppliers s ON s.id = gr.supplier_id
                 LEFT JOIN purchase_orders po ON po.id = gr.purchase_order_id
                """;
        long total = queryCount("SELECT COUNT(*) " + fromSql + where, params);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        boolean showCosts = hasCostReadPermission();
        String orderBy = buildOrderBy(pageable.getSort());
        String dataSql = """
                SELECT gr.id, gr.number, s.legal_name AS supplier_name, po.number AS purchase_order_number,
                       gr.status, gr.received_at, gr.net_total
                """ + fromSql + where + orderBy + " LIMIT :limit OFFSET :offset";

        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        JdbcClient.StatementSpec spec = jdbc.sql(dataSql);
        for (Map.Entry<String, Object> entry : pageParams.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        List<GoodsReceiptSummaryView> rows = spec.query((rs, rowNum) -> {
            Timestamp receivedAt = rs.getTimestamp("received_at");
            return new GoodsReceiptSummaryView(
                    rs.getLong("id"),
                    rs.getString("number"),
                    rs.getString("supplier_name"),
                    rs.getString("purchase_order_number"),
                    GoodsReceiptStatus.valueOf(rs.getString("status")),
                    receivedAt != null ? receivedAt.toInstant() : null,
                    showCosts ? rs.getBigDecimal("net_total") : null
            );
        }).list();

        return new PageImpl<>(rows, pageable, total);
    }

    /** Obtiene el detalle de una recepción aplicando ocultamiento de costos según permisos. */
    @Transactional(readOnly = true)
    public GoodsReceiptDetail getById(Long id, Long storeId) {
        return loadDetail(id, storeId, List.of(), hasCostReadPermission());
    }

    /** Obtiene el detalle incluyendo cambios de costo promedio recién calculados al confirmar. */
    @Transactional(readOnly = true)
    public GoodsReceiptDetail getByIdWithCostChanges(Long id, Long storeId, List<CostChangeView> costChanges) {
        return loadDetail(id, storeId, costChanges, hasCostReadPermission());
    }

    /**
     * Genera la plantilla prellenada con las cantidades pendientes de recibir de una orden de compra
     * en estado {@code SENT} o {@code PARTIALLY_RECEIVED}.
     */
    @Transactional(readOnly = true)
    public DraftTemplate buildDraftForOrder(Long purchaseOrderId, Long storeId) {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        Objects.requireNonNull(storeId, "storeId");

        var poHeader = jdbc.sql("""
                SELECT id, supplier_id, status
                FROM purchase_orders
                WHERE id = :id AND store_id = :storeId
                """)
                .param("id", purchaseOrderId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new PoHeader(
                        rs.getLong("id"),
                        rs.getLong("supplier_id"),
                        PurchaseOrderStatus.valueOf(rs.getString("status"))))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Orden de compra no encontrada: " + purchaseOrderId));

        if (poHeader.status() != PurchaseOrderStatus.SENT
                && poHeader.status() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Solo se pueden recepcionar órdenes en estado SENT o PARTIALLY_RECEIVED.");
        }

        List<DraftLineTemplate> lines = jdbc.sql("""
                SELECT id, product_id, GREATEST(qty_ordered - qty_received, 0) AS pending_qty, unit_cost_net
                FROM purchase_order_lines
                WHERE purchase_order_id = :poId
                  AND qty_ordered > qty_received
                ORDER BY id ASC
                """)
                .param("poId", purchaseOrderId)
                .query((rs, rowNum) -> new DraftLineTemplate(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        Rounding.quantity(rs.getBigDecimal("pending_qty")),
                        BigDecimal.ZERO.setScale(3),
                        Rounding.unitCost(rs.getBigDecimal("unit_cost_net"))))
                .list();

        return new DraftTemplate(
                poHeader.id(),
                poHeader.supplierId(),
                SupplierDocumentType.NONE,
                lines);
    }

    /** Indica si el usuario autenticado tiene permiso {@code catalog:cost:read}. */
    public boolean hasCostReadPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }

    private GoodsReceiptDetail loadDetail(Long id, Long storeId, List<CostChangeView> costChanges, boolean showCosts) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storeId, "storeId");

        GoodsReceiptHeaderRow header = jdbc.sql("""
                SELECT gr.id, gr.number, gr.purchase_order_id, po.number AS po_number,
                       gr.status, gr.supplier_document_type, gr.supplier_document_number,
                       gr.received_at, gr.received_by, gr.net_total, gr.notes, gr.version,
                       s.id AS sup_id, s.rut AS sup_rut, s.legal_name AS sup_legal_name, s.trade_name AS sup_trade_name
                FROM goods_receipts gr
                JOIN suppliers s ON s.id = gr.supplier_id
                LEFT JOIN purchase_orders po ON po.id = gr.purchase_order_id
                WHERE gr.id = :id AND gr.store_id = :storeId
                """)
                .param("id", id)
                .param("storeId", storeId)
                .query((rs, rowNum) -> {
                    Timestamp receivedAt = rs.getTimestamp("received_at");
                    long poId = rs.getLong("purchase_order_id");
                    Long purchaseOrderId = rs.wasNull() ? null : poId;
                    long recBy = rs.getLong("received_by");
                    Long receivedBy = rs.wasNull() ? null : recBy;
                    return new GoodsReceiptHeaderRow(
                            rs.getLong("id"),
                            rs.getString("number"),
                            purchaseOrderId,
                            rs.getString("po_number"),
                            new SupplierView(
                                    rs.getLong("sup_id"),
                                    rs.getString("sup_rut"),
                                    rs.getString("sup_legal_name"),
                                    rs.getString("sup_trade_name")),
                            GoodsReceiptStatus.valueOf(rs.getString("status")),
                            SupplierDocumentType.valueOf(rs.getString("supplier_document_type")),
                            rs.getString("supplier_document_number"),
                            receivedAt != null ? receivedAt.toInstant() : null,
                            receivedBy,
                            showCosts ? rs.getBigDecimal("net_total") : null,
                            rs.getString("notes"),
                            rs.getLong("version"));
                })
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "Recepción de mercadería no encontrada: " + id));

        List<GoodsReceiptLineView> lines = jdbc.sql("""
                SELECT l.id, l.purchase_order_line_id, l.product_id, p.sku, p.name,
                       l.qty_expected, l.qty_received, l.qty_rejected, l.difference_reason,
                       l.unit_cost_net, l.lot_number, l.expiry_date, l.lot_id
                FROM goods_receipt_lines l
                JOIN products p ON p.id = l.product_id
                WHERE l.goods_receipt_id = :receiptId
                ORDER BY l.id ASC
                """)
                .param("receiptId", id)
                .query((rs, rowNum) -> {
                    long poLineId = rs.getLong("purchase_order_line_id");
                    Long purchaseOrderLineId = rs.wasNull() ? null : poLineId;
                    long lId = rs.getLong("lot_id");
                    Long lotId = rs.wasNull() ? null : lId;
                    Date expiryDate = rs.getDate("expiry_date");
                    return new GoodsReceiptLineView(
                            rs.getLong("id"),
                            purchaseOrderLineId,
                            rs.getLong("product_id"),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getBigDecimal("qty_expected"),
                            rs.getBigDecimal("qty_received"),
                            rs.getBigDecimal("qty_rejected"),
                            rs.getString("difference_reason"),
                            showCosts ? rs.getBigDecimal("unit_cost_net") : null,
                            rs.getString("lot_number"),
                            expiryDate != null ? expiryDate.toLocalDate() : null,
                            lotId);
                })
                .list();

        List<CostChangeView> maskedCostChanges = costChanges == null ? List.of() : costChanges.stream()
                .map(c -> new CostChangeView(
                        c.productId(),
                        showCosts ? c.oldAvgCost() : null,
                        showCosts ? c.newAvgCost() : null))
                .toList();

        return new GoodsReceiptDetail(
                header.id(),
                header.number(),
                header.purchaseOrderId(),
                header.purchaseOrderNumber(),
                header.supplier(),
                header.status(),
                header.supplierDocumentType(),
                header.supplierDocumentNumber(),
                header.receivedAt(),
                header.receivedBy(),
                header.netTotal(),
                header.notes(),
                lines,
                maskedCostChanges,
                header.version());
    }

    private long queryCount(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY gr.created_at DESC, gr.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "gr.id";
                case "number" -> "gr.number";
                case "supplierName" -> "s.legal_name";
                case "purchaseOrderNumber" -> "po.number";
                case "status" -> "gr.status";
                case "receivedAt" -> "gr.received_at";
                case "netTotal" -> "gr.net_total";
                case "createdAt" -> "gr.created_at";
                default -> throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Campo de ordenamiento no permitido: " + order.getProperty());
            };
            String dir = order.isAscending() ? "ASC" : "DESC";
            clauses.add(column + " " + dir + (order.getProperty().equals("receivedAt") ? " NULLS LAST" : ""));
        }
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }

    private record PoHeader(Long id, Long supplierId, PurchaseOrderStatus status) {
    }

    private record GoodsReceiptHeaderRow(
            Long id,
            String number,
            Long purchaseOrderId,
            String purchaseOrderNumber,
            SupplierView supplier,
            GoodsReceiptStatus status,
            SupplierDocumentType supplierDocumentType,
            String supplierDocumentNumber,
            Instant receivedAt,
            Long receivedBy,
            BigDecimal netTotal,
            String notes,
            Long version
    ) {
    }
}
