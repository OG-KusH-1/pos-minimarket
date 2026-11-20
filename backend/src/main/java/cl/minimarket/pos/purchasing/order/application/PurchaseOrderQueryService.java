package cl.minimarket.pos.purchasing.order.application;

import cl.minimarket.pos.purchasing.PurchaseOrderOrigin;
import cl.minimarket.pos.purchasing.order.domain.PurchaseOrderStatus;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.export.PdfRenderer;
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
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Consultas y generación de PDF de órdenes de compra ({@code purchase_orders}).
 *
 * <p>Oculta los campos de costo ({@code unitCostNet}, {@code lineNetTotal}, {@code netTotal},
 * {@code taxTotal}, {@code total}) cuando el usuario autenticado no posee el permiso
 * {@code catalog:cost:read}.
 */
@Service
public class PurchaseOrderQueryService {

    private static final Locale ES_CL = Locale.of("es", "CL");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final PdfRenderer pdfRenderer;

    public PurchaseOrderQueryService(JdbcClient jdbc,
                                     BusinessClock clock,
                                     PdfRenderer pdfRenderer) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pdfRenderer = Objects.requireNonNull(pdfRenderer, "pdfRenderer");
    }

    /** Filtro de búsqueda paginada de órdenes de compra. */
    public record PurchaseOrderFilter(
            PurchaseOrderStatus status,
            Long supplierId,
            PurchaseOrderOrigin origin,
            LocalDate from,
            LocalDate to,
            String q
    ) {
    }

    /** Proyección resumida para listados de órdenes de compra. */
    public record PurchaseOrderSummaryView(
            Long id,
            String number,
            String supplierName,
            PurchaseOrderStatus status,
            PurchaseOrderOrigin origin,
            BigDecimal total,
            Instant createdAt,
            LocalDate expectedDate
    ) {
    }

    /** Datos del local asociados a la orden. */
    public record StoreView(Long id, String code, String name, String address, String commune, String city) {
    }

    /** Datos del proveedor asociados a la orden. */
    public record SupplierView(Long id, String rut, String legalName, String tradeName, String email) {
    }

    /** Línea de detalle de una orden de compra. */
    public record PurchaseOrderLineView(
            Long id,
            Long productId,
            String sku,
            String name,
            String purchaseUnitName,
            BigDecimal qtyPurchaseUnits,
            BigDecimal unitsPerPurchaseUnit,
            BigDecimal qtyOrdered,
            BigDecimal qtyReceived,
            BigDecimal unitCostNet,
            BigDecimal lineNetTotal
    ) {
    }

    /** Vista completa de una orden de compra. */
    public record PurchaseOrderDetail(
            Long id,
            String number,
            StoreView store,
            SupplierView supplier,
            PurchaseOrderStatus status,
            PurchaseOrderOrigin origin,
            Long replenishmentRunId,
            LocalDate expectedDate,
            Instant sentAt,
            Instant cancelledAt,
            String cancelReason,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            String notes,
            List<PurchaseOrderLineView> lines,
            Long version
    ) {
    }

    /** Lista paginada de órdenes de compra del local especificado. */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderSummaryView> list(Long storeId, PurchaseOrderFilter filter, Pageable pageable) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(pageable, "pageable");

        StringBuilder where = new StringBuilder(" WHERE po.store_id = :storeId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId);

        if (filter != null) {
            if (filter.status() != null) {
                where.append(" AND po.status = :status ");
                params.put("status", filter.status().name());
            }
            if (filter.supplierId() != null) {
                where.append(" AND po.supplier_id = :supplierId ");
                params.put("supplierId", filter.supplierId());
            }
            if (filter.origin() != null) {
                where.append(" AND po.origin = :origin ");
                params.put("origin", filter.origin().name());
            }
            if (filter.from() != null) {
                Instant fromInstant = filter.from().atStartOfDay(clock.zone()).toInstant();
                where.append(" AND po.created_at >= :fromInstant ");
                params.put("fromInstant", Timestamp.from(fromInstant));
            }
            if (filter.to() != null) {
                Instant toExclusive = filter.to().plusDays(1).atStartOfDay(clock.zone()).toInstant();
                where.append(" AND po.created_at < :toExclusive ");
                params.put("toExclusive", Timestamp.from(toExclusive));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String pattern = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
                where.append("""
                          AND (lower(po.number) LIKE :qPattern
                               OR f_unaccent(lower(s.legal_name)) ILIKE f_unaccent(:qPattern)
                               OR f_unaccent(lower(COALESCE(s.trade_name, ''))) ILIKE f_unaccent(:qPattern))
                        """);
                params.put("qPattern", pattern);
            }
        }

        String fromSql = " FROM purchase_orders po JOIN suppliers s ON s.id = po.supplier_id ";
        long total = queryCount("SELECT COUNT(*) " + fromSql + where, params);

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        boolean showCosts = hasCostReadPermission();
        String orderBy = buildOrderBy(pageable.getSort());
        String dataSql = """
                SELECT po.id, po.number, s.legal_name AS supplier_name, po.status, po.origin,
                       po.total, po.created_at, po.expected_date
                """ + fromSql + where + orderBy + " LIMIT :limit OFFSET :offset";

        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageable.getPageSize());
        pageParams.put("offset", pageable.getOffset());

        JdbcClient.StatementSpec spec = jdbc.sql(dataSql);
        for (Map.Entry<String, Object> entry : pageParams.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        List<PurchaseOrderSummaryView> rows = spec.query((rs, rowNum) -> {
            Date expDate = rs.getDate("expected_date");
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new PurchaseOrderSummaryView(
                    rs.getLong("id"),
                    rs.getString("number"),
                    rs.getString("supplier_name"),
                    PurchaseOrderStatus.valueOf(rs.getString("status")),
                    PurchaseOrderOrigin.valueOf(rs.getString("origin")),
                    showCosts ? rs.getBigDecimal("total") : null,
                    createdAt != null ? createdAt.toInstant() : null,
                    expDate != null ? expDate.toLocalDate() : null
            );
        }).list();

        return new PageImpl<>(rows, pageable, total);
    }

    /** Obtiene el detalle de una orden de compra aplicando ocultamiento de costos según permisos. */
    @Transactional(readOnly = true)
    public PurchaseOrderDetail getById(Long id, Long storeId) {
        return loadDetail(id, storeId, hasCostReadPermission());
    }

    /** Genera el PDF de la orden de compra respetando el permiso {@code catalog:cost:read}. */
    @Transactional(readOnly = true)
    public byte[] renderPdf(Long id, Long storeId) {
        return buildPdf(id, storeId, hasCostReadPermission());
    }

    /** Genera el PDF completo (incluyendo costos) para adjuntar en el correo al proveedor. */
    @Transactional(readOnly = true)
    public byte[] renderPdfForEmail(Long id, Long storeId) {
        return buildPdf(id, storeId, true);
    }

    private byte[] buildPdf(Long id, Long storeId, boolean showCosts) {
        PurchaseOrderDetail detail = loadDetail(id, storeId, showCosts);
        CompanyInfo company = loadCompanyInfo();

        NumberFormat clpFmt = NumberFormat.getNumberInstance(ES_CL);
        clpFmt.setMaximumFractionDigits(0);
        clpFmt.setGroupingUsed(true);

        NumberFormat qtyFmt = NumberFormat.getNumberInstance(ES_CL);
        qtyFmt.setMinimumFractionDigits(0);
        qtyFmt.setMaximumFractionDigits(3);
        qtyFmt.setGroupingUsed(true);

        ZoneId zone = clock.zone();
        List<Map<String, Object>> lineModels = detail.lines().stream()
                .map(line -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sku", line.sku());
                    m.put("name", line.name());
                    m.put("purchaseUnitName", line.purchaseUnitName());
                    m.put("qtyPurchaseUnits", qtyFmt.format(line.qtyPurchaseUnits()));
                    m.put("unitsPerPurchaseUnit", qtyFmt.format(line.unitsPerPurchaseUnit()));
                    m.put("qtyOrdered", qtyFmt.format(line.qtyOrdered()));
                    m.put("unitCostNet", showCosts && line.unitCostNet() != null
                            ? "$ " + clpFmt.format(line.unitCostNet())
                            : "-");
                    m.put("lineNetTotal", showCosts && line.lineNetTotal() != null
                            ? "$ " + clpFmt.format(line.lineNetTotal())
                            : "-");
                    return m;
                })
                .toList();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("company", company.legalName());
        model.put("companyRut", company.rut());
        model.put("title", "Orden de Compra " + detail.number());
        model.put("orderNumber", detail.number());
        model.put("statusLabel", translateStatus(detail.status()));
        model.put("storeName", detail.store().name());
        model.put("storeAddress", formatAddress(detail.store()));
        model.put("supplierName", detail.supplier().legalName());
        model.put("supplierRut", detail.supplier().rut());
        model.put("supplierEmail", detail.supplier().email() != null ? detail.supplier().email() : "-");
        model.put("expectedDate", detail.expectedDate() != null ? DATE_FMT.format(detail.expectedDate()) : "No definida");
        model.put("sentAt", detail.sentAt() != null ? DATETIME_FMT.format(detail.sentAt().atZone(zone)) : "-");
        model.put("notes", detail.notes());
        model.put("showCosts", showCosts);
        model.put("netTotal", showCosts && detail.netTotal() != null ? "$ " + clpFmt.format(detail.netTotal()) : "-");
        model.put("taxTotal", showCosts && detail.taxTotal() != null ? "$ " + clpFmt.format(detail.taxTotal()) : "-");
        model.put("total", showCosts && detail.total() != null ? "$ " + clpFmt.format(detail.total()) : "-");
        model.put("lines", lineModels);

        return pdfRenderer.render("purchase-order", model);
    }

    private PurchaseOrderDetail loadDetail(Long id, Long storeId, boolean showCosts) {
        var orderRow = jdbc.sql("""
                SELECT po.id, po.number, po.status, po.origin, po.replenishment_run_id,
                       po.expected_date, po.sent_at, po.cancelled_at, po.cancel_reason,
                       po.net_total, po.tax_total, po.total, po.notes, po.version,
                       st.id AS store_id, st.code AS store_code, st.name AS store_name,
                       st.address AS store_address, st.commune AS store_commune, st.city AS store_city,
                       sup.id AS supplier_id, sup.rut AS supplier_rut, sup.legal_name AS supplier_legal_name,
                       sup.trade_name AS supplier_trade_name, sup.email AS supplier_email
                FROM purchase_orders po
                JOIN stores st ON st.id = po.store_id
                JOIN suppliers sup ON sup.id = po.supplier_id
                WHERE po.id = :id AND po.store_id = :storeId
                """)
                .param("id", id)
                .param("storeId", storeId)
                .query((rs, rowNum) -> {
                    Date expDate = rs.getDate("expected_date");
                    Timestamp sentAt = rs.getTimestamp("sent_at");
                    Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
                    Long runId = rs.getObject("replenishment_run_id", Long.class);
                    return new OrderHeaderRow(
                            rs.getLong("id"),
                            rs.getString("number"),
                            new StoreView(
                                    rs.getLong("store_id"),
                                    rs.getString("store_code"),
                                    rs.getString("store_name"),
                                    rs.getString("store_address"),
                                    rs.getString("store_commune"),
                                    rs.getString("store_city")),
                            new SupplierView(
                                    rs.getLong("supplier_id"),
                                    rs.getString("supplier_rut"),
                                    rs.getString("supplier_legal_name"),
                                    rs.getString("supplier_trade_name"),
                                    rs.getString("supplier_email")),
                            PurchaseOrderStatus.valueOf(rs.getString("status")),
                            PurchaseOrderOrigin.valueOf(rs.getString("origin")),
                            runId,
                            expDate != null ? expDate.toLocalDate() : null,
                            sentAt != null ? sentAt.toInstant() : null,
                            cancelledAt != null ? cancelledAt.toInstant() : null,
                            rs.getString("cancel_reason"),
                            showCosts ? rs.getBigDecimal("net_total") : null,
                            showCosts ? rs.getBigDecimal("tax_total") : null,
                            showCosts ? rs.getBigDecimal("total") : null,
                            rs.getString("notes"),
                            rs.getLong("version"));
                })
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Orden de compra no encontrada."));

        List<PurchaseOrderLineView> lines = jdbc.sql("""
                SELECT l.id, l.product_id, p.sku, p.name,
                       COALESCE(ps.purchase_unit_name, 'Unidad') AS purchase_unit_name,
                       l.qty_purchase_units, l.units_per_purchase_unit, l.qty_ordered,
                       l.qty_received, l.unit_cost_net, l.line_net_total
                FROM purchase_order_lines l
                JOIN products p ON p.id = l.product_id
                LEFT JOIN product_suppliers ps ON ps.id = l.product_supplier_id
                WHERE l.purchase_order_id = :orderId
                ORDER BY l.id ASC
                """)
                .param("orderId", id)
                .query((rs, rowNum) -> new PurchaseOrderLineView(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("purchase_unit_name"),
                        rs.getBigDecimal("qty_purchase_units"),
                        rs.getBigDecimal("units_per_purchase_unit"),
                        rs.getBigDecimal("qty_ordered"),
                        rs.getBigDecimal("qty_received"),
                        showCosts ? rs.getBigDecimal("unit_cost_net") : null,
                        showCosts ? rs.getBigDecimal("line_net_total") : null))
                .list();

        return new PurchaseOrderDetail(
                orderRow.id(),
                orderRow.number(),
                orderRow.store(),
                orderRow.supplier(),
                orderRow.status(),
                orderRow.origin(),
                orderRow.replenishmentRunId(),
                orderRow.expectedDate(),
                orderRow.sentAt(),
                orderRow.cancelledAt(),
                orderRow.cancelReason(),
                orderRow.netTotal(),
                orderRow.taxTotal(),
                orderRow.total(),
                orderRow.notes(),
                lines,
                orderRow.version());
    }

    private CompanyInfo loadCompanyInfo() {
        return jdbc.sql("SELECT legal_name, rut FROM company ORDER BY id ASC LIMIT 1")
                .query((rs, rowNum) -> new CompanyInfo(rs.getString("legal_name"), rs.getString("rut")))
                .optional()
                .orElse(new CompanyInfo("Minimarket", "-"));
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
            return " ORDER BY po.created_at DESC, po.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "po.id";
                case "number" -> "po.number";
                case "supplierName" -> "s.legal_name";
                case "status" -> "po.status";
                case "origin" -> "po.origin";
                case "total" -> "po.total";
                case "expectedDate" -> "po.expected_date";
                default -> "po.created_at";
            };
            clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }

    private static boolean hasCostReadPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.CATALOG_COST_READ.equals(a.getAuthority()));
    }

    private static String translateStatus(PurchaseOrderStatus status) {
        return switch (status) {
            case DRAFT -> "Borrador";
            case SENT -> "Enviada";
            case PARTIALLY_RECEIVED -> "Recepción parcial";
            case RECEIVED -> "Recibida";
            case CANCELLED -> "Cancelada";
        };
    }

    private static String formatAddress(StoreView store) {
        List<String> parts = new ArrayList<>();
        if (store.address() != null && !store.address().isBlank()) {
            parts.add(store.address());
        }
        if (store.commune() != null && !store.commune().isBlank()) {
            parts.add(store.commune());
        }
        if (store.city() != null && !store.city().isBlank()) {
            parts.add(store.city());
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private record CompanyInfo(String legalName, String rut) {
    }

    private record OrderHeaderRow(
            Long id,
            String number,
            StoreView store,
            SupplierView supplier,
            PurchaseOrderStatus status,
            PurchaseOrderOrigin origin,
            Long replenishmentRunId,
            LocalDate expectedDate,
            Instant sentAt,
            Instant cancelledAt,
            String cancelReason,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal total,
            String notes,
            Long version
    ) {
    }
}
