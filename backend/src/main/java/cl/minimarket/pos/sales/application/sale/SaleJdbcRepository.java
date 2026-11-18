package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.cash.CashSessionMetricsContributor;
import cl.minimarket.pos.sales.application.pricing.PaymentMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consultas SQL directas de soporte para ventas (búsquedas paginadas, referencias maestras,
 * conteo de reimpresiones, flujo neto de caja y alcance P).
 */
@Repository
public class SaleJdbcRepository {

    private final JdbcClient jdbc;

    public SaleJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Datos resumidos de usuario para respuestas de venta y ticket. */
    public record UserInfo(Long id, String username, String fullName) {
    }

    /** Datos resumidos de terminal para respuestas de venta y ticket. */
    public record TerminalInfo(Long id, String code, String name) {
    }

    /** Datos resumidos de cliente para validaciones y respuestas de venta. */
    public record CustomerInfo(Long id, String rut, String name, boolean active) {
    }

    /** Datos de la empresa singleton para el encabezado del ticket. */
    public record CompanyInfo(String tradeName, String rut, String address, String businessActivity) {
    }

    /** Resumen de devolución/anulación vinculada a la venta. */
    public record ReturnInfo(Long id, String number, String returnType, BigDecimal refundTotal) {
    }

    /** Candidato de autorización pendiente para emparejar múltiples tokens en {@code X-Override-Token}. */
    public record OverrideCandidate(String tokenHash, String action, String contextHash) {
    }

    /** Fila paginada para {@code GET /sales}. */
    public record SaleSummaryRow(
            Long id,
            String number,
            Instant soldAt,
            String cashierName,
            String customerName,
            BigDecimal totalPayable,
            SaleStatus status,
            List<PaymentMethod> paymentMethods
    ) {
    }

    /**
     * Obtiene el identificador del cajero que emitió la venta, o vacío si no existe.
     */
    public Optional<Long> findCashierIdBySaleId(Long saleId) {
        return jdbc.sql("SELECT cashier_user_id FROM sales WHERE id = :saleId")
                .param("saleId", saleId)
                .query(Long.class)
                .optional();
    }

    /**
     * Obtiene datos básicos de un cliente por id.
     */
    public Optional<CustomerInfo> findCustomerById(Long customerId) {
        return jdbc.sql("SELECT id, rut, name, active FROM customers WHERE id = :id")
                .param("id", customerId)
                .query((rs, rowNum) -> new CustomerInfo(
                        rs.getLong("id"),
                        rs.getString("rut"),
                        rs.getString("name"),
                        rs.getBoolean("active")
                ))
                .optional();
    }

    /**
     * Obtiene datos básicos de usuarios indexados por id.
     */
    public Map<Long, UserInfo> findUsersByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<UserInfo> rows = jdbc.sql("""
                SELECT id, username, full_name
                FROM users
                WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .query((rs, rowNum) -> new UserInfo(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("full_name")
                ))
                .list();

        Map<Long, UserInfo> result = new HashMap<>(rows.size());
        for (UserInfo row : rows) {
            result.put(row.id(), row);
        }
        return result;
    }

    /**
     * Obtiene código y nombre de terminales indexados por id.
     */
    public Map<Long, TerminalInfo> findTerminalsByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<TerminalInfo> rows = jdbc.sql("""
                SELECT id, code, name
                FROM terminals
                WHERE id IN (:ids)
                """)
                .param("ids", ids)
                .query((rs, rowNum) -> new TerminalInfo(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name")
                ))
                .list();

        Map<Long, TerminalInfo> result = new HashMap<>(rows.size());
        for (TerminalInfo row : rows) {
            result.put(row.id(), row);
        }
        return result;
    }

    /**
     * Obtiene nombres de promociones por sus ids.
     */
    public Map<Long, String> findPromotionNamesByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(ids.size());
        jdbc.sql("SELECT id, name FROM promotions WHERE id IN (:ids)")
                .param("ids", ids)
                .query(rs -> {
                    result.put(rs.getLong("id"), rs.getString("name"));
                });
        return result;
    }

    /**
     * Obtiene los datos de la empresa singleton para construir el ticket.
     */
    public Optional<CompanyInfo> findCompanyInfo() {
        return jdbc.sql("""
                SELECT trade_name, legal_name, rut, address, business_activity
                FROM company
                ORDER BY id ASC
                LIMIT 1
                """)
                .query((rs, rowNum) -> {
                    String tradeName = rs.getString("trade_name");
                    if (tradeName == null || tradeName.isBlank()) {
                        tradeName = rs.getString("legal_name");
                    }
                    return new CompanyInfo(
                            tradeName,
                            rs.getString("rut"),
                            rs.getString("address"),
                            rs.getString("business_activity")
                    );
                })
                .optional();
    }

    /**
     * Obtiene devoluciones o anulaciones registradas para una venta.
     */
    public List<ReturnInfo> findReturnsBySaleId(Long saleId) {
        return jdbc.sql("""
                SELECT id, number, return_type, refund_total
                FROM sale_returns
                WHERE sale_id = :saleId
                ORDER BY id ASC
                """)
                .param("saleId", saleId)
                .query((rs, rowNum) -> new ReturnInfo(
                        rs.getLong("id"),
                        rs.getString("number"),
                        rs.getString("return_type"),
                        rs.getBigDecimal("refund_total")
                ))
                .list();
    }

    /**
     * Cuenta las reimpresiones auditadas previas del ticket de una venta.
     */
    public int countReprints(Long saleId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                FROM audit_log
                WHERE action = 'RECEIPT_REPRINTED'
                  AND entity_type = 'sale'
                  AND entity_id = :saleId
                """)
                .param("saleId", String.valueOf(saleId))
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    /**
     * Busca metadatos de autorizaciones vigentes por hash de token para emparejar múltiples
     * overrides enviados en {@code X-Override-Token} sin disparar rollbacks espurios al probar orden.
     */
    public Map<String, OverrideCandidate> findOverrideCandidatesByTokenHashes(Collection<String> tokenHashes) {
        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return Map.of();
        }
        List<OverrideCandidate> rows = jdbc.sql("""
                SELECT token_hash, action, context_hash
                FROM supervisor_authorizations
                WHERE token_hash IN (:hashes)
                  AND status = 'GRANTED'
                  AND consumed_at IS NULL
                """)
                .param("hashes", tokenHashes)
                .query((rs, rowNum) -> new OverrideCandidate(
                        rs.getString("token_hash"),
                        rs.getString("action"),
                        rs.getString("context_hash")
                ))
                .list();

        Map<String, OverrideCandidate> map = new HashMap<>(rows.size());
        for (OverrideCandidate row : rows) {
            map.put(row.tokenHash(), row);
        }
        return map;
    }

    /**
     * Consulta paginada de ventas del local activo aplicando filtros y alcance P cuando corresponde.
     */
    public Page<SaleSummaryRow> findPage(
            SaleFilter filter,
            Long scopeCashierUserId,
            Long storeId,
            Pageable pageable
    ) {
        StringBuilder where = new StringBuilder(" WHERE s.store_id = :storeId ");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("storeId", storeId);

        if (scopeCashierUserId != null) {
            where.append(" AND s.cashier_user_id = :scopeCashierId ");
            params.put("scopeCashierId", scopeCashierUserId);
        }
        if (filter != null) {
            if (filter.from() != null) {
                where.append(" AND s.business_date >= :fromDate ");
                params.put("fromDate", filter.from());
            }
            if (filter.to() != null) {
                where.append(" AND s.business_date <= :toDate ");
                params.put("toDate", filter.to());
            }
            if (filter.cashierId() != null) {
                where.append(" AND s.cashier_user_id = :cashierId ");
                params.put("cashierId", filter.cashierId());
            }
            if (filter.terminalId() != null) {
                where.append(" AND s.terminal_id = :terminalId ");
                params.put("terminalId", filter.terminalId());
            }
            if (filter.status() != null) {
                where.append(" AND s.status = :status ");
                params.put("status", filter.status().name());
            }
            if (filter.paymentMethod() != null) {
                where.append("""
                         AND EXISTS (
                             SELECT 1 FROM sale_payments sp_f
                             WHERE sp_f.sale_id = s.id AND sp_f.method = :paymentMethod
                         )
                        """);
                params.put("paymentMethod", filter.paymentMethod().name());
            }
            if (filter.customerId() != null) {
                where.append(" AND s.customer_id = :customerId ");
                params.put("customerId", filter.customerId());
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                where.append(" AND LOWER(s.number) LIKE LOWER(:qPattern) ESCAPE '\\' ");
                params.put("qPattern", "%" + escapeLike(filter.q().trim()) + "%");
            }
        }

        String countSql = "SELECT COUNT(*) FROM sales s " + where;
        Long totalObj = bindParams(jdbc.sql(countSql), params).query(Long.class).single();
        long total = totalObj != null ? totalObj : 0L;
        if (total == 0L) {
            return Page.empty(pageable);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        String dataSql = """
                SELECT s.id,
                       s.number,
                       s.sold_at,
                       COALESCE(NULLIF(BTRIM(u.full_name), ''), u.username) AS cashier_name,
                       c.name AS customer_name,
                       s.total_payable,
                       s.status
                FROM sales s
                JOIN users u ON u.id = s.cashier_user_id
                LEFT JOIN customers c ON c.id = s.customer_id
                """ + where + orderBy + " LIMIT :limit OFFSET :offset ";

        Map<String, Object> dataParams = new LinkedHashMap<>(params);
        dataParams.put("limit", pageable.getPageSize());
        dataParams.put("offset", pageable.getOffset());

        List<SaleHeaderRow> headers = bindParams(jdbc.sql(dataSql), dataParams)
                .query((rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("sold_at");
                    return new SaleHeaderRow(
                            rs.getLong("id"),
                            rs.getString("number"),
                            ts != null ? ts.toInstant() : Instant.now(),
                            rs.getString("cashier_name"),
                            rs.getString("customer_name"),
                            rs.getBigDecimal("total_payable"),
                            SaleStatus.valueOf(rs.getString("status"))
                    );
                })
                .list();

        List<Long> saleIds = headers.stream().map(SaleHeaderRow::id).toList();
        Map<Long, List<PaymentMethod>> methodsBySale = findPaymentMethodsBySaleIds(saleIds);

        List<SaleSummaryRow> content = headers.stream()
                .map(h -> new SaleSummaryRow(
                        h.id(),
                        h.number(),
                        h.soldAt(),
                        h.cashierName(),
                        h.customerName(),
                        h.totalPayable(),
                        h.status(),
                        methodsBySale.getOrDefault(h.id(), List.of())
                ))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Calcula los montos netos de ventas y reembolsos por medio de pago para un turno de caja.
     */
    public Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> netCashFlowBySession(Long cashSessionId) {
        if (cashSessionId == null) {
            return Map.of();
        }
        Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> totals =
                new EnumMap<>(cl.minimarket.pos.cash.PaymentMethod.class);
        findApprovedPaymentsBySession(cashSessionId)
                .forEach((method, total) -> totals.merge(method, total, BigDecimal::add));
        findRefundsBySession(cashSessionId)
                .forEach((method, total) -> totals.merge(method, total.negate(), BigDecimal::add));
        return totals;
    }

    /**
     * Obtiene los conteos y desgloses de ventas y devoluciones por medio de pago vinculados a un
     * turno de caja ({@link CashSessionMetricsContributor}).
     */
    public CashSessionMetricsContributor.SessionMetricsSnapshot sessionMetricsBySession(Long cashSessionId) {
        if (cashSessionId == null) {
            return new CashSessionMetricsContributor.SessionMetricsSnapshot(
                    0, 0, 0, Map.of(), Map.of(), Map.of()
            );
        }

        int salesCount = jdbc.sql("SELECT COUNT(*) FROM sales WHERE cash_session_id = :id")
                .param("id", cashSessionId)
                .query(Integer.class)
                .single();

        int voidCount = jdbc.sql("SELECT COUNT(*) FROM sale_returns WHERE cash_session_id = :id AND return_type = 'VOID'")
                .param("id", cashSessionId)
                .query(Integer.class)
                .single();

        int returnCount = jdbc.sql("SELECT COUNT(*) FROM sale_returns WHERE cash_session_id = :id AND return_type = 'RETURN'")
                .param("id", cashSessionId)
                .query(Integer.class)
                .single();

        EnumMap<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> salesByMethod = emptyCashMethodMap();
        salesByMethod.putAll(findApprovedPaymentsBySession(cashSessionId));

        EnumMap<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> refundsByMethod = emptyCashMethodMap();
        refundsByMethod.putAll(findRefundsBySession(cashSessionId));

        return new CashSessionMetricsContributor.SessionMetricsSnapshot(
                salesCount,
                voidCount,
                returnCount,
                salesByMethod,
                refundsByMethod,
                Map.of()
        );
    }

    private Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> findApprovedPaymentsBySession(Long cashSessionId) {
        Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> totals =
                new EnumMap<>(cl.minimarket.pos.cash.PaymentMethod.class);
        jdbc.sql("""
                SELECT sp.method, COALESCE(SUM(sp.amount), 0) AS total
                FROM sale_payments sp
                JOIN sales s ON s.id = sp.sale_id
                WHERE s.cash_session_id = :cashSessionId
                  AND sp.status = 'APPROVED'
                GROUP BY sp.method
                """)
                .param("cashSessionId", cashSessionId)
                .query(rs -> {
                    cl.minimarket.pos.cash.PaymentMethod method =
                            cl.minimarket.pos.cash.PaymentMethod.valueOf(rs.getString("method"));
                    totals.put(method, rs.getBigDecimal("total"));
                });
        return totals;
    }

    private Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> findRefundsBySession(Long cashSessionId) {
        Map<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> totals =
                new EnumMap<>(cl.minimarket.pos.cash.PaymentMethod.class);
        jdbc.sql("""
                SELECT r.method, COALESCE(SUM(r.amount), 0) AS total
                FROM sale_return_refunds r
                JOIN sale_returns sr ON sr.id = r.sale_return_id
                WHERE sr.cash_session_id = :cashSessionId
                GROUP BY r.method
                """)
                .param("cashSessionId", cashSessionId)
                .query(rs -> {
                    cl.minimarket.pos.cash.PaymentMethod method =
                            cl.minimarket.pos.cash.PaymentMethod.valueOf(rs.getString("method"));
                    totals.put(method, rs.getBigDecimal("total"));
                });
        return totals;
    }

    private static EnumMap<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> emptyCashMethodMap() {
        EnumMap<cl.minimarket.pos.cash.PaymentMethod, BigDecimal> map =
                new EnumMap<>(cl.minimarket.pos.cash.PaymentMethod.class);
        for (cl.minimarket.pos.cash.PaymentMethod method : cl.minimarket.pos.cash.PaymentMethod.values()) {
            map.put(method, BigDecimal.ZERO);
        }
        return map;
    }

    private Map<Long, List<PaymentMethod>> findPaymentMethodsBySaleIds(List<Long> saleIds) {
        if (saleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PaymentMethod>> result = new HashMap<>(saleIds.size());
        jdbc.sql("""
                SELECT sale_id, method
                FROM sale_payments
                WHERE sale_id IN (:saleIds)
                ORDER BY id ASC
                """)
                .param("saleIds", saleIds)
                .query(rs -> {
                    Long saleId = rs.getLong("sale_id");
                    PaymentMethod method = PaymentMethod.valueOf(rs.getString("method"));
                    List<PaymentMethod> list = result.computeIfAbsent(saleId, k -> new ArrayList<>());
                    if (!list.contains(method)) {
                        list.add(method);
                    }
                });
        return result;
    }

    private static JdbcClient.StatementSpec bindParams(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        JdbcClient.StatementSpec current = spec;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            current = current.param(entry.getKey(), entry.getValue());
        }
        return current;
    }

    private static String buildOrderBy(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return " ORDER BY s.sold_at DESC, s.id DESC ";
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "id" -> "s.id";
                case "number" -> "s.number";
                case "soldAt" -> "s.sold_at";
                case "businessDate" -> "s.business_date";
                case "totalPayable" -> "s.total_payable";
                case "status" -> "s.status";
                default -> throw new IllegalArgumentException("Campo de orden no soportado: " + order.getProperty());
            };
            clauses.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        clauses.add("s.id DESC");
        return " ORDER BY " + String.join(", ", clauses) + " ";
    }

    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record SaleHeaderRow(
            Long id,
            String number,
            Instant soldAt,
            String cashierName,
            String customerName,
            BigDecimal totalPayable,
            SaleStatus status
    ) {
    }
}
