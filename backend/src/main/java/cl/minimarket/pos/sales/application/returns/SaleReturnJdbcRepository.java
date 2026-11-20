package cl.minimarket.pos.sales.application.returns;

import cl.minimarket.pos.catalog.SaleType;
import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnLineDetail;
import cl.minimarket.pos.sales.application.returns.SaleReturnModels.SaleReturnRefundDetail;
import cl.minimarket.pos.sales.application.sale.SaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio JDBC para operaciones de anulación, devolución y consulta de {@code sale_returns}.
 */
@Repository
public class SaleReturnJdbcRepository {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Santiago");

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "sr.id",
            "number", "sr.number",
            "createdAt", "sr.created_at",
            "returnType", "sr.return_type",
            "refundTotal", "sr.refund_total"
    );

    private final JdbcClient jdbc;

    public SaleReturnJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cabecera de venta bloqueada para anulación o devolución. */
    public record LockedSaleRow(
            Long id,
            String number,
            Long storeId,
            Long terminalId,
            Long cashSessionId,
            Long cashierUserId,
            Long customerId,
            SaleStatus status,
            LocalDate businessDate,
            BigDecimal totalGross,
            BigDecimal totalPayable
    ) {
    }

    /** Línea de venta bloqueada para devolución o anulación. */
    public record LockedSaleLineRow(
            Long id,
            Long saleId,
            int lineNumber,
            Long productId,
            String sku,
            String productName,
            SaleType saleType,
            BigDecimal quantity,
            Long stockProductId,
            BigDecimal stockQuantity,
            BigDecimal lineTotalGross,
            BigDecimal lineNet,
            BigDecimal lineTax,
            BigDecimal lineExempt,
            BigDecimal unitCost,
            BigDecimal returnedQuantity
    ) {
    }

    /** Pago de la venta original. */
    public record SalePaymentRow(
            Long id,
            PaymentMethod method,
            BigDecimal amount,
            String gatewayReference
    ) {
    }

    /** Asignación de lote de una línea de venta. */
    public record LotAllocationRow(
            Long saleLineId,
            Long lotId,
            BigDecimal quantity
    ) {
    }

    /** Acumulado ya devuelto por línea en {@code sale_return_lines}. */
    public record PriorLineReturnTotals(
            BigDecimal stockQuantity,
            BigDecimal refundAmount,
            BigDecimal refundNet
    ) {
    }

    /** Bloquea con {@code FOR UPDATE} la venta perteneciente al local indicado. */
    public Optional<LockedSaleRow> lockSale(Long saleId, Long storeId) {
        return jdbc.sql("""
                SELECT id, number, store_id, terminal_id, cash_session_id, cashier_user_id,
                       customer_id, status, business_date, total_gross, total_payable
                FROM sales
                WHERE id = :saleId AND store_id = :storeId
                FOR UPDATE
                """)
                .param("saleId", saleId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new LockedSaleRow(
                        rs.getLong("id"),
                        rs.getString("number"),
                        rs.getLong("store_id"),
                        rs.getLong("terminal_id"),
                        rs.getLong("cash_session_id"),
                        rs.getLong("cashier_user_id"),
                        rs.getObject("customer_id", Long.class),
                        SaleStatus.valueOf(rs.getString("status")),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getBigDecimal("total_gross"),
                        rs.getBigDecimal("total_payable")
                ))
                .optional();
    }

    /** Carga la venta sin bloqueo para consultas de solo lectura. */
    public Optional<LockedSaleRow> findSale(Long saleId, Long storeId) {
        return jdbc.sql("""
                SELECT id, number, store_id, terminal_id, cash_session_id, cashier_user_id,
                       customer_id, status, business_date, total_gross, total_payable
                FROM sales
                WHERE id = :saleId AND store_id = :storeId
                """)
                .param("saleId", saleId)
                .param("storeId", storeId)
                .query((rs, rowNum) -> new LockedSaleRow(
                        rs.getLong("id"),
                        rs.getString("number"),
                        rs.getLong("store_id"),
                        rs.getLong("terminal_id"),
                        rs.getLong("cash_session_id"),
                        rs.getLong("cashier_user_id"),
                        rs.getObject("customer_id", Long.class),
                        SaleStatus.valueOf(rs.getString("status")),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getBigDecimal("total_gross"),
                        rs.getBigDecimal("total_payable")
                ))
                .optional();
    }

    /** Bloquea y devuelve las líneas de la venta ordenadas por {@code line_number}. */
    public List<LockedSaleLineRow> lockSaleLines(Long saleId) {
        return querySaleLines(saleId, true);
    }

    /** Devuelve las líneas de la venta sin bloqueo para consultas de solo lectura. */
    public List<LockedSaleLineRow> findSaleLines(Long saleId) {
        return querySaleLines(saleId, false);
    }

    private List<LockedSaleLineRow> querySaleLines(Long saleId, boolean forUpdate) {
        String sql = """
                SELECT id, sale_id, line_number, product_id, sku, product_name, sale_type,
                       quantity, stock_product_id, stock_quantity, line_total_gross,
                       line_net, line_tax, line_exempt, unit_cost, returned_quantity
                FROM sale_lines
                WHERE sale_id = :saleId
                ORDER BY line_number ASC
                """ + (forUpdate ? " FOR UPDATE " : "");
        return jdbc.sql(sql)
                .param("saleId", saleId)
                .query((rs, rowNum) -> new LockedSaleLineRow(
                        rs.getLong("id"),
                        rs.getLong("sale_id"),
                        rs.getInt("line_number"),
                        rs.getLong("product_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        SaleType.valueOf(rs.getString("sale_type")),
                        rs.getBigDecimal("quantity"),
                        rs.getLong("stock_product_id"),
                        rs.getBigDecimal("stock_quantity"),
                        rs.getBigDecimal("line_total_gross"),
                        rs.getBigDecimal("line_net"),
                        rs.getBigDecimal("line_tax"),
                        rs.getBigDecimal("line_exempt"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("returned_quantity")
                ))
                .list();
    }

    /** Devuelve los pagos registrados en la venta original. */
    public List<SalePaymentRow> findSalePayments(Long saleId) {
        return jdbc.sql("""
                SELECT id, method, amount, gateway_reference
                FROM sale_payments
                WHERE sale_id = :saleId
                ORDER BY id ASC
                """)
                .param("saleId", saleId)
                .query((rs, rowNum) -> new SalePaymentRow(
                        rs.getLong("id"),
                        PaymentMethod.valueOf(rs.getString("method")),
                        rs.getBigDecimal("amount"),
                        rs.getString("gateway_reference")
                ))
                .list();
    }

    /** Devuelve las asignaciones de lote de las líneas de la venta, indexadas por {@code sale_line_id}. */
    public Map<Long, List<LotAllocationRow>> findLotAllocationsBySaleLineIds(Collection<Long> saleLineIds) {
        if (saleLineIds == null || saleLineIds.isEmpty()) {
            return Map.of();
        }
        List<LotAllocationRow> rows = jdbc.sql("""
                SELECT sale_line_id, lot_id, quantity
                FROM sale_lot_allocations
                WHERE sale_line_id IN (:ids)
                ORDER BY id ASC
                """)
                .param("ids", saleLineIds)
                .query((rs, rowNum) -> new LotAllocationRow(
                        rs.getLong("sale_line_id"),
                        rs.getLong("lot_id"),
                        rs.getBigDecimal("quantity")
                ))
                .list();

        Map<Long, List<LotAllocationRow>> result = new HashMap<>();
        for (LotAllocationRow row : rows) {
            result.computeIfAbsent(row.saleLineId(), k -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /** Obtiene lo ya devuelto en {@code sale_return_lines} por cada {@code sale_line_id}. */
    public Map<Long, PriorLineReturnTotals> findPriorReturnTotalsBySaleId(Long saleId) {
        Map<Long, PriorLineReturnTotals> result = new HashMap<>();
        jdbc.sql("""
                SELECT srl.sale_line_id,
                       COALESCE(SUM(srl.stock_quantity), 0) AS total_stock_qty,
                       COALESCE(SUM(srl.refund_amount), 0) AS total_refund_amount,
                       COALESCE(SUM(srl.refund_net), 0) AS total_refund_net
                FROM sale_return_lines srl
                JOIN sale_returns sr ON sr.id = srl.sale_return_id
                WHERE sr.sale_id = :saleId
                GROUP BY srl.sale_line_id
                """)
                .param("saleId", saleId)
                .query(rs -> {
                    result.put(
                            rs.getLong("sale_line_id"),
                            new PriorLineReturnTotals(
                                    rs.getBigDecimal("total_stock_qty"),
                                    rs.getBigDecimal("total_refund_amount"),
                                    rs.getBigDecimal("total_refund_net")
                            )
                    );
                });
        return result;
    }

    /** Indica si la venta ya tiene al menos una anulación o devolución registrada. */
    public boolean hasAnyReturnForSale(Long saleId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM sale_returns WHERE sale_id = :saleId")
                .param("saleId", saleId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** Inserta la cabecera de {@code sale_returns} y devuelve su {@code id} generado. */
    public Long insertSaleReturn(
            String number,
            Long saleId,
            SaleReturnType returnType,
            Long storeId,
            Long terminalId,
            Long cashSessionId,
            SaleReturnReasonCode reasonCode,
            String reasonText,
            BigDecimal refundTotal,
            Long authorizationId,
            UUID idempotencyKey,
            Long createdBy,
            Instant createdAt
    ) {
        OffsetDateTime createdAtUtc = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO sale_returns (
                    number, sale_id, return_type, store_id, terminal_id, cash_session_id,
                    reason_code, reason_text, refund_total, authorization_id,
                    idempotency_key, created_by, created_at
                ) VALUES (
                    :number, :saleId, :returnType, :storeId, :terminalId, :cashSessionId,
                    :reasonCode, :reasonText, :refundTotal, :authorizationId,
                    :idempotencyKey, :createdBy, :createdAt
                )
                RETURNING id
                """)
                .param("number", number)
                .param("saleId", saleId)
                .param("returnType", returnType.name())
                .param("storeId", storeId)
                .param("terminalId", terminalId)
                .param("cashSessionId", cashSessionId)
                .param("reasonCode", reasonCode.name())
                .param("reasonText", reasonText, Types.VARCHAR)
                .param("refundTotal", refundTotal)
                .param("authorizationId", authorizationId, Types.BIGINT)
                .param("idempotencyKey", idempotencyKey)
                .param("createdBy", createdBy)
                .param("createdAt", createdAtUtc)
                .query(Long.class)
                .single();
    }

    /** Inserta una línea de {@code sale_return_lines} y devuelve su {@code id} generado. */
    public Long insertSaleReturnLine(
            Long saleReturnId,
            Long saleLineId,
            BigDecimal quantity,
            BigDecimal stockQuantity,
            boolean restock,
            BigDecimal refundAmount,
            BigDecimal refundNet,
            BigDecimal refundTax,
            Instant createdAt
    ) {
        OffsetDateTime createdAtUtc = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO sale_return_lines (
                    sale_return_id, sale_line_id, quantity, stock_quantity, restock,
                    refund_amount, refund_net, refund_tax, created_at
                ) VALUES (
                    :saleReturnId, :saleLineId, :quantity, :stockQuantity, :restock,
                    :refundAmount, :refundNet, :refundTax, :createdAt
                )
                RETURNING id
                """)
                .param("saleReturnId", saleReturnId)
                .param("saleLineId", saleLineId)
                .param("quantity", quantity)
                .param("stockQuantity", stockQuantity)
                .param("restock", restock)
                .param("refundAmount", refundAmount)
                .param("refundNet", refundNet)
                .param("refundTax", refundTax)
                .param("createdAt", createdAtUtc)
                .query(Long.class)
                .single();
    }

    /** Inserta un registro de reembolso en {@code sale_return_refunds}. */
    public void insertSaleReturnRefund(
            Long saleReturnId,
            PaymentMethod method,
            BigDecimal amount,
            String gatewayReference,
            Instant createdAt
    ) {
        OffsetDateTime createdAtUtc = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO sale_return_refunds (
                    sale_return_id, method, amount, gateway_reference, created_at
                ) VALUES (
                    :saleReturnId, :method, :amount, :gatewayReference, :createdAt
                )
                """)
                .param("saleReturnId", saleReturnId)
                .param("method", method.name())
                .param("amount", amount)
                .param("gatewayReference", gatewayReference, Types.VARCHAR)
                .param("createdAt", createdAtUtc)
                .update();
    }

    /** Actualiza {@code returned_quantity} en {@code sale_lines}. */
    public void updateSaleLineReturnedQuantity(Long saleLineId, BigDecimal returnedQuantity) {
        jdbc.sql("""
                UPDATE sale_lines
                SET returned_quantity = :returnedQuantity
                WHERE id = :saleLineId
                """)
                .param("returnedQuantity", returnedQuantity)
                .param("saleLineId", saleLineId)
                .update();
    }

    /** Actualiza el estado de la venta y su versión optimista. */
    public void updateSaleStatus(Long saleId, SaleStatus status, Long updatedBy, Instant updatedAt) {
        OffsetDateTime updatedAtUtc = OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE sales
                SET status = :status,
                    updated_at = :updatedAt,
                    updated_by = :updatedBy,
                    version = version + 1
                WHERE id = :saleId
                """)
                .param("status", status.name())
                .param("updatedAt", updatedAtUtc)
                .param("updatedBy", updatedBy)
                .param("saleId", saleId)
                .update();
    }

    /** Marca todos los pagos de la venta como {@code REVERSED} al anularla. */
    public void markSalePaymentsReversed(Long saleId) {
        jdbc.sql("""
                UPDATE sale_payments
                SET status = 'REVERSED'
                WHERE sale_id = :saleId
                """)
                .param("saleId", saleId)
                .update();
    }

    /** Busca el identificador de una devolución ya creada por su clave de idempotencia. */
    public Optional<Long> findIdByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.sql("SELECT id FROM sale_returns WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Long.class)
                .optional();
    }

    /**
     * Obtiene el detalle completo de una anulación o devolución por su id, aplicando filtro de local
     * y opcionalmente alcance P por cajero.
     */
    public Optional<SaleReturnDetail> findDetailById(Long id, Long storeId, Long restrictToCashierId) {
        StringBuilder sql = new StringBuilder("""
                SELECT sr.id, sr.number, sr.sale_id, sr.return_type, sr.reason_code, sr.reason_text,
                       sr.refund_total, sr.created_at, sr.created_by, u.full_name AS created_by_name
                FROM sale_returns sr
                JOIN sales s ON s.id = sr.sale_id
                JOIN users u ON u.id = sr.created_by
                WHERE sr.id = :id AND sr.store_id = :storeId
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        params.put("storeId", storeId);

        if (restrictToCashierId != null) {
            sql.append(" AND s.cashier_user_id = :cashierId");
            params.put("cashierId", restrictToCashierId);
        }

        Optional<ReturnHeaderRow> headerOpt = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> mapHeader(rs))
                .optional();

        if (headerOpt.isEmpty()) {
            return Optional.empty();
        }
        ReturnHeaderRow header = headerOpt.get();
        List<SaleReturnLineDetail> lines = findLinesByReturnIds(List.of(header.id()))
                .getOrDefault(header.id(), List.of());
        List<SaleReturnRefundDetail> refunds = findRefundsByReturnIds(List.of(header.id()))
                .getOrDefault(header.id(), List.of());

        return Optional.of(toDetail(header, lines, refunds));
    }

    /** Lista paginada de anulaciones y devoluciones con filtros y alcance P opcional. */
    public Page<SaleReturnDetail> list(
            Long storeId,
            Long restrictToCashierId,
            SaleReturnFilter filter,
            Pageable pageable
    ) {
        StringBuilder where = new StringBuilder(" WHERE sr.store_id = :storeId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId);

        if (restrictToCashierId != null) {
            where.append(" AND s.cashier_user_id = :cashierId ");
            params.put("cashierId", restrictToCashierId);
        }
        if (filter != null) {
            if (filter.from() != null) {
                OffsetDateTime fromStart = filter.from().atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
                where.append(" AND sr.created_at >= :fromStart ");
                params.put("fromStart", fromStart);
            }
            if (filter.to() != null) {
                OffsetDateTime toExclusive = filter.to().plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
                where.append(" AND sr.created_at < :toExclusive ");
                params.put("toExclusive", toExclusive);
            }
            if (filter.returnType() != null) {
                where.append(" AND sr.return_type = :returnType ");
                params.put("returnType", filter.returnType().name());
            }
            if (filter.saleId() != null) {
                where.append(" AND sr.sale_id = :saleId ");
                params.put("saleId", filter.saleId());
            }
        }

        String baseFrom = """
                FROM sale_returns sr
                JOIN sales s ON s.id = sr.sale_id
                JOIN users u ON u.id = sr.created_by
                """;

        Long total = jdbc.sql("SELECT COUNT(*) " + baseFrom + where)
                .params(params)
                .query(Long.class)
                .single();
        long totalCount = total == null ? 0L : total;
        if (totalCount == 0L) {
            return new PageImpl<>(List.of(), pageable, 0L);
        }

        StringBuilder select = new StringBuilder("""
                SELECT sr.id, sr.number, sr.sale_id, sr.return_type, sr.reason_code, sr.reason_text,
                       sr.refund_total, sr.created_at, sr.created_by, u.full_name AS created_by_name
                """)
                .append(baseFrom)
                .append(where)
                .append(buildOrderBy(pageable.getSort()))
                .append(" LIMIT :limit OFFSET :offset");

        Map<String, Object> queryParams = new LinkedHashMap<>(params);
        queryParams.put("limit", pageable.getPageSize());
        queryParams.put("offset", pageable.getOffset());

        List<ReturnHeaderRow> headers = jdbc.sql(select.toString())
                .params(queryParams)
                .query((rs, rowNum) -> mapHeader(rs))
                .list();

        List<Long> returnIds = headers.stream().map(ReturnHeaderRow::id).toList();
        Map<Long, List<SaleReturnLineDetail>> linesByReturn = findLinesByReturnIds(returnIds);
        Map<Long, List<SaleReturnRefundDetail>> refundsByReturn = findRefundsByReturnIds(returnIds);

        List<SaleReturnDetail> content = new ArrayList<>(headers.size());
        for (ReturnHeaderRow h : headers) {
            content.add(toDetail(
                    h,
                    linesByReturn.getOrDefault(h.id(), List.of()),
                    refundsByReturn.getOrDefault(h.id(), List.of())
            ));
        }
        return new PageImpl<>(content, pageable, totalCount);
    }

    private Map<Long, List<SaleReturnLineDetail>> findLinesByReturnIds(List<Long> returnIds) {
        if (returnIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SaleReturnLineDetail>> map = new HashMap<>();
        jdbc.sql("""
                SELECT srl.sale_return_id, srl.sale_line_id, sl.product_name,
                       srl.quantity, srl.restock, srl.refund_amount
                FROM sale_return_lines srl
                JOIN sale_lines sl ON sl.id = srl.sale_line_id
                WHERE srl.sale_return_id IN (:ids)
                ORDER BY srl.id ASC
                """)
                .param("ids", returnIds)
                .query(rs -> {
                    long returnId = rs.getLong("sale_return_id");
                    map.computeIfAbsent(returnId, k -> new ArrayList<>())
                            .add(new SaleReturnLineDetail(
                                    rs.getLong("sale_line_id"),
                                    rs.getString("product_name"),
                                    rs.getBigDecimal("quantity"),
                                    rs.getBoolean("restock"),
                                    rs.getBigDecimal("refund_amount")
                            ));
                });
        return map;
    }

    private Map<Long, List<SaleReturnRefundDetail>> findRefundsByReturnIds(List<Long> returnIds) {
        if (returnIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SaleReturnRefundDetail>> map = new HashMap<>();
        jdbc.sql("""
                SELECT sale_return_id, method, amount, gateway_reference
                FROM sale_return_refunds
                WHERE sale_return_id IN (:ids)
                ORDER BY id ASC
                """)
                .param("ids", returnIds)
                .query(rs -> {
                    long returnId = rs.getLong("sale_return_id");
                    map.computeIfAbsent(returnId, k -> new ArrayList<>())
                            .add(new SaleReturnRefundDetail(
                                    PaymentMethod.valueOf(rs.getString("method")),
                                    rs.getBigDecimal("amount"),
                                    rs.getString("gateway_reference")
                            ));
                });
        return map;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY sr.created_at DESC, sr.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String col = SORT_COLUMNS.get(order.getProperty());
            if (col == null) {
                throw new IllegalArgumentException("Campo de orden no soportado: " + order.getProperty());
            }
            clauses.add(col + (order.isAscending() ? " ASC" : " DESC"));
        }
        clauses.add("sr.id DESC");
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }

    private static ReturnHeaderRow mapHeader(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        return new ReturnHeaderRow(
                rs.getLong("id"),
                rs.getString("number"),
                rs.getLong("sale_id"),
                SaleReturnType.valueOf(rs.getString("return_type")),
                SaleReturnReasonCode.valueOf(rs.getString("reason_code")),
                rs.getString("reason_text"),
                rs.getBigDecimal("refund_total"),
                createdTs != null ? createdTs.toInstant() : null,
                rs.getLong("created_by"),
                rs.getString("created_by_name")
        );
    }

    private static SaleReturnDetail toDetail(
            ReturnHeaderRow h,
            List<SaleReturnLineDetail> lines,
            List<SaleReturnRefundDetail> refunds
    ) {
        return new SaleReturnDetail(
                h.id(),
                h.number(),
                h.saleId(),
                h.returnType(),
                h.reasonCode(),
                h.reasonText(),
                h.refundTotal(),
                lines,
                refunds,
                h.createdAt(),
                h.createdById(),
                h.createdByName()
        );
    }

    private record ReturnHeaderRow(
            Long id,
            String number,
            Long saleId,
            SaleReturnType returnType,
            SaleReturnReasonCode reasonCode,
            String reasonText,
            BigDecimal refundTotal,
            Instant createdAt,
            Long createdById,
            String createdByName
    ) {
    }
}
