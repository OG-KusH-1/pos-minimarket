package cl.minimarket.pos.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * API pública del módulo {@code reporting} (PLAN A.4): demanda diaria neta por producto y local,
 * leída del agregado {@code daily_product_sales} (MODELO_DATOS B.11), que mantiene el job de las
 * 02:00 ({@code DailySalesAggregateJob}).
 *
 * <p>Consumidor principal: el algoritmo de reabastecimiento (PLAN C.5), que carga la historia de
 * {@link #dailyDemand} para censurar los días con quiebre y estimar la demanda (C.3). El módulo
 * {@code reporting} es el único autorizado a leer por SQL las tablas de otros módulos (ADR-11);
 * esta API es la vía por la que el resto del sistema consume esa lectura ya agregada, sin conocer
 * las tablas transaccionales.
 *
 * <p>Unidades: todas las cantidades están en la <b>unidad base</b> del producto (unidades o
 * kilogramos); las ventas de packs ya vienen consolidadas en su producto base, tal como las deja el
 * agregado.
 */
public interface SalesHistoryApi {

    /**
     * Devuelve la demanda diaria neta de los productos indicados en un local, entre dos fechas de
     * negocio (ambas inclusive), leída del agregado {@code daily_product_sales} y ordenada por
     * producto y fecha.
     *
     * <p>Contrato de la lectura:
     * <ul>
     *   <li>Solo existen filas para los días que el job nocturno (o un refresco a demanda) ha
     *       recalculado. Un día sin fila <b>no</b> es "venta cero": es un día sin información, y el
     *       consumidor decide cómo tratarlo. El reabastecimiento lo ignora: cuenta solo los días
     *       presentes y marca {@code SHORT_HISTORY} si faltan (C.3).</li>
     *   <li>Los días con quiebre llegan con {@link DailyDemand#hadStockout()} en {@code true} y
     *       deben censurarse de la demanda (C.3 paso 1).</li>
     *   <li>Si {@code productIds} viene vacío se devuelve una lista vacía sin consultar la base.</li>
     *   <li>Argumentos nulos o {@code from > to} son errores del llamador: se rechazan con
     *       {@code VALIDATION_ERROR} (o {@link NullPointerException} si el argumento es nulo),
     *       porque este método no expone un rango configurable al usuario final.</li>
     * </ul>
     *
     * @param storeId    local cuyas filas se consultan
     * @param productIds ids de productos en unidad base (para un pack se consulta su producto base)
     * @param from       primera fecha de negocio del rango (inclusive)
     * @param to         última fecha de negocio del rango (inclusive)
     * @return filas del agregado dentro del rango, ordenadas por producto y fecha
     */
    List<DailyDemand> dailyDemand(Long storeId, Collection<Long> productIds, LocalDate from, LocalDate to);

    /**
     * Refresca (recalcula) el agregado diario de ventas del rango indicado (ambos inclusive) antes
     * de leer la demanda: la corrida de reabastecimiento lo ejecuta previo a su lectura de
     * {@link #dailyDemand}, para que la historia refleje las ventas más recientes (API.md F.2,
     * {@code POST /replenishment/runs}).
     *
     * <p>Delega en el recalculo del agregado ({@code DailySalesAggregateService.refreshRange}):
     * misma semántica y mismos errores; días sin fila tras el refresco siguen siendo días sin
     * información, no venta cero (ver el contrato de {@link #dailyDemand}).
     *
     * @param from primer día del rango a refrescar (inclusive)
     * @param to   último día del rango a refrescar (inclusive)
     * @return filas insertadas o actualizadas del agregado
     */
    int refreshDailySales(LocalDate from, LocalDate to);

    /**
     * Observación diaria de demanda de un producto en un local: una fila del agregado
     * {@code daily_product_sales}.
     *
     * @param storeId     local de la observación
     * @param productId   producto en unidad base (las ventas de packs llegan consolidadas aquí)
     * @param saleDate    fecha de negocio del día agregado
     * @param quantity    venta neta del día en unidad base, ya descontadas las devoluciones
     *                    ({@code qty_sold} de B.11)
     * @param hadStockout {@code true} si el día tuvo quiebre según B.11 (apertura en cero o algún
     *                    movimiento del día con saldo cero) y por tanto debe censurarse
     */
    record DailyDemand(
            Long storeId,
            Long productId,
            LocalDate saleDate,
            BigDecimal quantity,
            boolean hadStockout
    ) {
    }
}
