package cl.minimarket.pos.inventory.application.transfer;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio JDBC de soporte para lecturas paginadas, hidratación de DTOs de transferencias y
 * validaciones auxiliares sobre {@code lots}, {@code products} y {@code users}.
 */
@Repository
class TransferLookupRepository {

    private static final Map<String, String> SORT_COLUMNS =
            Map.of(
                    "id", "t.id",
                    "number", "t.number",
                    "status", "t.status",
                    "sentAt", "t.sent_at",
                    "receivedAt", "t.received_at",
                    "createdAt", "t.created_at");

    private final JdbcClient jdbc;

    TransferLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record LotLookupInfo(
            Long id,
            Long storeId,
            Long productId,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            String status) {}

    record UserLookupInfo(Long id, String username, String fullName) {}

    record TransferHeaderRow(
            Long id,
            String number,
            Long originStoreId,
            String originStoreCode,
            String originStoreName,
            Long destinationStoreId,
            String destinationStoreCode,
            String destinationStoreName,
            TransferStatus status,
            Instant sentAt,
            Long sentById,
            String sentByUsername,
            String sentByFullName,
            Instant receivedAt,
            Long receivedById,
            String receivedByUsername,
            String receivedByFullName,
            String notes,
            Long version) {}

    record TransferLineRow(
            Long id,
            Long transferId,
            Long productId,
            String sku,
            String productName,
            String lotNumber,
            LocalDate expiryDate,
            BigDecimal qtySent,
            BigDecimal qtyReceived,
            String differenceReason) {}

    record TransferFilter(
            TransferDirection direction, TransferStatus status, Instant from, Instant toExclusive) {}

    /**
     * Consulta los lotes del local indicado por sus identificadores.
     *
     * @param storeId identificador del local de origen.
     * @param lotIds identificadores de lote solicitados.
     * @return mapa {@code lotId -> LotLookupInfo} con los lotes encontrados en el local.
     */
    Map<Long, LotLookupInfo> findLotsInStore(Long storeId, Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Map.of();
        }
        List<Long> uniqueIds = lotIds.stream().distinct().toList();
        String sql =
                """
                SELECT id, store_id, product_id, lot_number, expiry_date, quantity, status
                  FROM lots
                 WHERE store_id = :storeId
                   AND id IN (:ids)
                """;
        Map<Long, LotLookupInfo> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("storeId", storeId)
                .param("ids", uniqueIds)
                .query(
                        (rs, rowNum) ->
                                new LotLookupInfo(
                                        rs.getLong("id"),
                                        rs.getLong("store_id"),
                                        rs.getLong("product_id"),
                                        rs.getString("lot_number"),
                                        rs.getObject("expiry_date", LocalDate.class),
                                        Rounding.quantity(rs.getBigDecimal("quantity")),
                                        rs.getString("status")))
                .list()
                .forEach(lot -> result.put(lot.id(), lot));
        return result;
    }

    /**
     * Lista transferencias visibles para el local activo (como origen o destino) aplicando filtros
     * y paginación.
     *
     * @param storeId local activo.
     * @param filter filtros opcionales de dirección, estado y rango de fechas.
     * @param pageable paginación y ordenamiento.
     * @return página de cabeceras hidratadas con nombres de local y usuario.
     */
    Page<TransferHeaderRow> findHeaders(
            Long storeId, TransferFilter filter, Pageable pageable) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = buildWhere(storeId, filter, params);

        long total =
                jdbc.sql(
                                """
                                SELECT COUNT(*)
                                  FROM stock_transfers t
                                """
                                        + where)
                        .params(params)
                        .query(Long.class)
                        .single();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String orderBy = buildOrderBy(pageable.getSort());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        List<TransferHeaderRow> rows =
                jdbc.sql(
                                headerSelectClause()
                                        + where
                                        + "\n"
                                        + orderBy
                                        + "\nLIMIT :limit OFFSET :offset")
                        .params(params)
                        .query((rs, rowNum) -> mapHeader(rs))
                        .list();

        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Obtiene la cabecera de una transferencia sin filtrar por local para distinguir {@code 404} de
     * otras reglas de acceso.
     *
     * @param id identificador de la transferencia.
     * @return cabecera hidratada si existe.
     */
    Optional<TransferHeaderRow> findHeaderById(Long id) {
        return jdbc.sql(headerSelectClause() + " WHERE t.id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapHeader(rs))
                .optional();
    }

    /**
     * Carga las líneas de un conjunto de transferencias ordenadas por identificador de línea.
     *
     * @param transferIds identificadores de transferencia.
     * @return mapa {@code transferId -> lista de líneas}.
     */
    Map<Long, List<TransferLineRow>> findLinesByTransferIds(Collection<Long> transferIds) {
        if (transferIds == null || transferIds.isEmpty()) {
            return Map.of();
        }
        String sql =
                """
                SELECT l.id,
                       l.transfer_id,
                       l.product_id,
                       p.sku,
                       p.name AS product_name,
                       l.lot_number,
                       l.expiry_date,
                       l.qty_sent,
                       l.qty_received,
                       l.difference_reason
                  FROM stock_transfer_lines l
                  JOIN products p ON p.id = l.product_id
                 WHERE l.transfer_id IN (:transferIds)
                 ORDER BY l.transfer_id ASC, l.id ASC
                """;
        Map<Long, List<TransferLineRow>> grouped = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("transferIds", transferIds)
                .query(
                        rs -> {
                            long transferId = rs.getLong("transfer_id");
                            TransferLineRow row =
                                    new TransferLineRow(
                                            rs.getLong("id"),
                                            transferId,
                                            rs.getLong("product_id"),
                                            rs.getString("sku"),
                                            rs.getString("product_name"),
                                            rs.getString("lot_number"),
                                            rs.getObject("expiry_date", LocalDate.class),
                                            Rounding.quantity(rs.getBigDecimal("qty_sent")),
                                            rs.getBigDecimal("qty_received") != null
                                                    ? Rounding.quantity(
                                                            rs.getBigDecimal("qty_received"))
                                                    : null,
                                            rs.getString("difference_reason"));
                            grouped.computeIfAbsent(transferId, ignored -> new ArrayList<>())
                                    .add(row);
                        });
        return grouped;
    }

    private String headerSelectClause() {
        return """
                SELECT t.id,
                       t.number,
                       t.origin_store_id,
                       so.code AS origin_store_code,
                       so.name AS origin_store_name,
                       t.destination_store_id,
                       sd.code AS destination_store_code,
                       sd.name AS destination_store_name,
                       t.status,
                       t.sent_at,
                       t.sent_by,
                       us.username AS sent_by_username,
                       us.full_name AS sent_by_full_name,
                       t.received_at,
                       t.received_by,
                       ur.username AS received_by_username,
                       ur.full_name AS received_by_full_name,
                       t.notes,
                       t.version
                  FROM stock_transfers t
                  JOIN stores so ON so.id = t.origin_store_id
                  JOIN stores sd ON sd.id = t.destination_store_id
                  LEFT JOIN users us ON us.id = t.sent_by
                  LEFT JOIN users ur ON ur.id = t.received_by
                """;
    }

    private String buildWhere(
            Long storeId, TransferFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE ");
        params.put("storeId", storeId);

        if (filter.direction() == TransferDirection.IN) {
            where.append("t.destination_store_id = :storeId");
        } else if (filter.direction() == TransferDirection.OUT) {
            where.append("t.origin_store_id = :storeId");
        } else {
            where.append("(t.origin_store_id = :storeId OR t.destination_store_id = :storeId)");
        }

        if (filter.status() != null) {
            where.append(" AND t.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.from() != null) {
            where.append(" AND COALESCE(t.sent_at, t.created_at) >= :from");
            params.put("from", OffsetDateTime.ofInstant(filter.from(), java.time.ZoneOffset.UTC));
        }
        if (filter.toExclusive() != null) {
            where.append(" AND COALESCE(t.sent_at, t.created_at) < :toExclusive");
            params.put(
                    "toExclusive",
                    OffsetDateTime.ofInstant(filter.toExclusive(), java.time.ZoneOffset.UTC));
        }
        return where.toString();
    }

    private String buildOrderBy(Sort sort) {
        List<String> clauses = new ArrayList<>();
        boolean includesId = false;
        for (Sort.Order order : sort) {
            String col = SORT_COLUMNS.get(order.getProperty());
            if (col != null) {
                clauses.add(col + (order.isAscending() ? " ASC" : " DESC"));
                if ("id".equals(order.getProperty())) {
                    includesId = true;
                }
            }
        }
        if (!includesId) {
            clauses.add("t.id DESC");
        }
        return "ORDER BY " + String.join(", ", clauses);
    }

    private TransferHeaderRow mapHeader(ResultSet rs) throws SQLException {
        OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
        OffsetDateTime receivedAt = rs.getObject("received_at", OffsetDateTime.class);
        Long sentById = rs.getObject("sent_by", Long.class);
        Long receivedById = rs.getObject("received_by", Long.class);
        return new TransferHeaderRow(
                rs.getLong("id"),
                rs.getString("number"),
                rs.getLong("origin_store_id"),
                rs.getString("origin_store_code"),
                rs.getString("origin_store_name"),
                rs.getLong("destination_store_id"),
                rs.getString("destination_store_code"),
                rs.getString("destination_store_name"),
                TransferStatus.valueOf(rs.getString("status")),
                sentAt != null ? sentAt.toInstant() : null,
                sentById,
                rs.getString("sent_by_username"),
                rs.getString("sent_by_full_name"),
                receivedAt != null ? receivedAt.toInstant() : null,
                receivedById,
                rs.getString("received_by_username"),
                rs.getString("received_by_full_name"),
                rs.getString("notes"),
                rs.getLong("version"));
    }
}
