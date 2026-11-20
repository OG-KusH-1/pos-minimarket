package cl.minimarket.pos.reporting.aggregate;

import cl.minimarket.pos.reporting.SalesHistoryApi;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.money.Rounding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Implementación de la API pública {@link SalesHistoryApi}: lectura de solo lectura (SQL) sobre el
 * agregado {@code daily_product_sales} que mantiene {@link DailySalesAggregateService}.
 *
 * <p>No consulta las tablas transaccionales de ventas: el agregado existe precisamente para que
 * el reabastecimiento lea la historia ya consolidada (ADR-11), censurada con
 * {@code had_stockout}.
 */
@Service
public class SalesHistoryApiImpl implements SalesHistoryApi {

    /**
     * Los ids de producto se parten en bloques de este tamaño para que la lista {@code IN (...)}
     * no crezca sin tope cuando el reabastecimiento pide todos los productos activos del local.
     */
    private static final int PRODUCT_ID_CHUNK = 1_000;

    private final JdbcClient jdbc;
    private final DailySalesAggregateService aggregateService;

    public SalesHistoryApiImpl(JdbcClient jdbc, DailySalesAggregateService aggregateService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.aggregateService = Objects.requireNonNull(aggregateService, "aggregateService");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Devuelve solo las filas que el agregado tiene para el rango; los días sin fila son días
     * sin información (ver el contrato de {@link SalesHistoryApi#dailyDemand}). La cantidad se
     * normaliza a tres decimales ({@link Rounding#quantity}).
     */
    @Override
    @Transactional(readOnly = true)
    public List<DailyDemand> dailyDemand(Long storeId, Collection<Long> productIds, LocalDate from, LocalDate to) {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(productIds, "productIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "La fecha inicial de la historia de demanda no puede ser posterior a la final.");
        }
        if (productIds.isEmpty()) {
            return List.of();
        }

        List<Long> ids = List.copyOf(productIds);
        List<DailyDemand> result = new ArrayList<>(ids.size());
        for (int start = 0; start < ids.size(); start += PRODUCT_ID_CHUNK) {
            List<Long> chunk = ids.subList(start, Math.min(start + PRODUCT_ID_CHUNK, ids.size()));
            result.addAll(jdbc.sql("""
                            SELECT store_id, product_id, sale_date, qty_sold, had_stockout
                            FROM daily_product_sales
                            WHERE store_id = :storeId
                              AND sale_date BETWEEN :from AND :to
                              AND product_id IN (:productIds)
                            ORDER BY product_id, sale_date
                            """)
                    .param("storeId", storeId)
                    .param("from", from)
                    .param("to", to)
                    .param("productIds", chunk)
                    .query((rs, rowNum) -> new DailyDemand(
                            rs.getLong("store_id"),
                            rs.getLong("product_id"),
                            rs.getObject("sale_date", LocalDate.class),
                            Rounding.quantity(rs.getBigDecimal("qty_sold")),
                            rs.getBoolean("had_stockout")))
                    .list());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegación directa en {@link DailySalesAggregateService#refreshRange}: misma validación
     * (rango ≤ 366 días, {@code from ≤ to}) y mismos {@link ErrorCode}; no agrega lógica propia.
     */
    @Override
    @Transactional
    public int refreshDailySales(LocalDate from, LocalDate to) {
        return aggregateService.refreshRange(from, to);
    }
}
