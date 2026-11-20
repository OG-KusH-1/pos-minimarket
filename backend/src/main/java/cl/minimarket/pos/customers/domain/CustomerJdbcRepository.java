package cl.minimarket.pos.customers.domain;

import cl.minimarket.pos.cash.PaymentMethod;
import cl.minimarket.pos.shared.money.Rounding;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Consultas de lectura con {@link JdbcClient} para búsqueda trigram de clientes, cartola de cuenta
 * corriente enriquecida con datos de local/usuario/venta y aporte de abonos al flujo de caja.
 */
@Repository
public class CustomerJdbcRepository {

    private static final Map<String, String> CUSTOMER_SORT_COLUMNS = Map.of(
            "id", "c.id",
            "name", "c.name",
            "rut", "c.rut",
            "creditLimit", "c.credit_limit",
            "creditBalance", "c.credit_balance",
            "active", "c.active",
            "createdAt", "c.created_at"
    );

    private static final Map<String, String> MOVEMENT_SORT_COLUMNS = Map.of(
            "id", "m.id",
            "occurredAt", "m.occurred_at",
            "movementType", "m.movement_type",
            "amount", "m.amount",
            "balanceAfter", "m.balance_after"
    );

    private final JdbcClient jdbc;

    public CustomerJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Proyección de cliente para listados paginados. */
    public record CustomerRow(
            Long id,
            String rut,
            String name,
            String email,
            String phone,
            String address,
            boolean creditEnabled,
            BigDecimal creditLimit,
            BigDecimal creditBalance,
            BigDecimal availableCredit,
            boolean active,
            Long version
    ) {
    }

    /** Proyección enriquecida de movimiento de cuenta corriente. */
    public record AccountMovementRow(
            Long id,
            Instant occurredAt,
            CustomerMovementType movementType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String saleNumber,
            PaymentMethod paymentMethod,
            String storeName,
            String username,
            String notes
    ) {
    }

    /**
     * Busca clientes aplicando filtros opcionales de texto (nombre o RUT), deuda vigente,
     * habilitación de crédito y estado activo.
     */
    public Page<CustomerRow> searchCustomers(
            String q,
            Boolean withDebt,
            Boolean creditEnabled,
            Boolean active,
            Pageable pageable
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();

        if (q != null && !q.isBlank()) {
            String trimmed = q.trim();
            String rutClean = trimmed.replaceAll("[.\\s-]", "").toUpperCase(Locale.ROOT);
            where.append("""
                     AND (
                        f_unaccent(lower(c.name)) LIKE '%' || f_unaccent(lower(:qText)) || '%'
                        OR upper(replace(coalesce(c.rut, ''), '-', '')) LIKE '%' || :qRut || '%'
                     )
                    """);
            params.put("qText", trimmed);
            params.put("qRut", rutClean);
        }
        if (Boolean.TRUE.equals(withDebt)) {
            where.append(" AND c.credit_balance > 0 ");
        } else if (Boolean.FALSE.equals(withDebt)) {
            where.append(" AND c.credit_balance = 0 ");
        }
        if (creditEnabled != null) {
            where.append(" AND c.credit_enabled = :creditEnabled ");
            params.put("creditEnabled", creditEnabled);
        }
        if (active != null) {
            where.append(" AND c.active = :active ");
            params.put("active", active);
        }

        String countSql = "SELECT COUNT(*) FROM customers c " + where;
        long total = queryLong(countSql, params);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort(), CUSTOMER_SORT_COLUMNS, "c.name ASC, c.id ASC");
        String dataSql = """
                SELECT c.id, c.rut, c.name, c.email, c.phone, c.address,
                       c.credit_enabled, c.credit_limit, c.credit_balance, c.active, c.version
                FROM customers c
                """ + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset ";

        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        JdbcClient.StatementSpec spec = jdbc.sql(dataSql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        List<CustomerRow> rows = spec.query((rs, rowNum) -> {
            BigDecimal creditLimit = Rounding.clp(rs.getBigDecimal("credit_limit"));
            BigDecimal creditBalance = Rounding.clp(rs.getBigDecimal("credit_balance"));
            BigDecimal diff = creditLimit.subtract(creditBalance);
            BigDecimal availableCredit = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
            return new CustomerRow(
                    rs.getLong("id"),
                    rs.getString("rut"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("phone"),
                    rs.getString("address"),
                    rs.getBoolean("credit_enabled"),
                    creditLimit,
                    creditBalance,
                    availableCredit,
                    rs.getBoolean("active"),
                    rs.getLong("version")
            );
        }).list();

        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Lista la cartola paginada de movimientos de cuenta corriente de un cliente, filtrando
     * opcionalmente por rango de fechas {@code [from, to)}.
     */
    public Page<AccountMovementRow> findAccountMovements(
            Long customerId,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        StringBuilder where = new StringBuilder(" WHERE m.customer_id = :customerId ");
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);

        if (from != null) {
            where.append(" AND m.occurred_at >= :from ");
            params.put("from", Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND m.occurred_at < :to ");
            params.put("to", Timestamp.from(to));
        }

        String countSql = "SELECT COUNT(*) FROM customer_account_movements m " + where;
        long total = queryLong(countSql, params);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort(), MOVEMENT_SORT_COLUMNS, "m.occurred_at DESC, m.id DESC");
        String dataSql = """
                SELECT m.id, m.occurred_at, m.movement_type, m.amount, m.balance_after,
                       s.number AS sale_number, m.payment_method, st.name AS store_name,
                       u.username, m.notes
                FROM customer_account_movements m
                JOIN stores st ON st.id = m.store_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN sales s ON s.id = m.sale_id
                """ + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset ";

        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        JdbcClient.StatementSpec spec = jdbc.sql(dataSql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }

        List<AccountMovementRow> rows = spec.query((rs, rowNum) -> mapMovementRow(rs)).list();
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Obtiene un movimiento enriquecido por su identificador.
     */
    public Optional<AccountMovementRow> findMovementById(Long movementId) {
        return jdbc.sql("""
                SELECT m.id, m.occurred_at, m.movement_type, m.amount, m.balance_after,
                       s.number AS sale_number, m.payment_method, st.name AS store_name,
                       u.username, m.notes
                FROM customer_account_movements m
                JOIN stores st ON st.id = m.store_id
                JOIN users u ON u.id = m.user_id
                LEFT JOIN sales s ON s.id = m.sale_id
                WHERE m.id = :id
                """)
                .param("id", movementId)
                .query((rs, rowNum) -> mapMovementRow(rs))
                .optional();
    }

    /**
     * Suma los abonos en efectivo recibidos durante un turno de caja (en positivo, para sumar al
     * esperado de efectivo del turno).
     */
    public BigDecimal sumCashPaymentsBySession(Long cashSessionId) {
        BigDecimal total = jdbc.sql("""
                SELECT COALESCE(SUM(-amount), 0)
                FROM customer_account_movements
                WHERE cash_session_id = :cashSessionId
                  AND movement_type = 'PAYMENT'
                  AND payment_method = 'CASH'
                """)
                .param("cashSessionId", cashSessionId)
                .query(BigDecimal.class)
                .single();
        return Rounding.clp(total);
    }

    private AccountMovementRow mapMovementRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String pmRaw = rs.getString("payment_method");
        PaymentMethod pm = pmRaw != null ? PaymentMethod.valueOf(pmRaw) : null;
        return new AccountMovementRow(
                rs.getLong("id"),
                rs.getTimestamp("occurred_at").toInstant(),
                CustomerMovementType.valueOf(rs.getString("movement_type")),
                Rounding.clp(rs.getBigDecimal("amount")),
                Rounding.clp(rs.getBigDecimal("balance_after")),
                rs.getString("sale_number"),
                pm,
                rs.getString("store_name"),
                rs.getString("username"),
                rs.getString("notes")
        );
    }

    private long queryLong(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    private static String buildOrderBy(Sort sort, Map<String, String> whitelist, String fallback) {
        if (sort == null || sort.isUnsorted()) {
            return fallback;
        }
        List<String> clauses = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = whitelist.get(order.getProperty());
            if (column != null) {
                clauses.add(column + " " + (order.isAscending() ? "ASC" : "DESC"));
            }
        }
        return clauses.isEmpty() ? fallback : String.join(", ", clauses);
    }
}
