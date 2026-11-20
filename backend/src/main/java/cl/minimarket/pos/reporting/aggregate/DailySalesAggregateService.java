package cl.minimarket.pos.reporting.aggregate;

import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Servicio que mantiene el agregado diario de ventas por local y producto
 * ({@code daily_product_sales}, MODELO_DATOS B.11 y ADR-11). Es la única pieza del sistema que
 * <b>escribe</b> esa tabla, y la escribe recalculando cada día completo desde las tablas
 * transaccionales de otros módulos ({@code sale_lines}, {@code sale_return_lines},
 * {@code stock_movements}), que este módulo está autorizado a leer por SQL (ADR-11).
 *
 * <h2>Semántica de las columnas (B.11)</h2>
 * <ul>
 *   <li><b>Atribución de días:</b> cada fila pertenece a la fecha de negocio de la <b>venta</b>
 *       ({@code sale_lines.business_date}, desnormalizada). Una devolución se descuenta de la fila
 *       del día en que se vendió la línea devuelta (se une {@code sale_return_lines} con su
 *       {@code sale_line}), no del día en que se hizo la devolución: así el día de venta refleja la
 *       venta neta real y {@code qty_sold} nunca es negativo. El monto bruto, el neto y el costo se
 *       netean igual ({@code refund_amount}, {@code refund_net} y
 *       {@code round(unit_cost × stock_quantity, 2)} respectivamente).</li>
 *   <li><b>{@code qty_sold}</b> en unidad base consolidando packs: se agrupa por
 *       {@code stock_product_id} (el producto base de la línea), sumando {@code stock_quantity}.</li>
 *   <li><b>{@code transactions}</b>: ventas distintas del día que incluyeron el producto
 *       ({@code COUNT(DISTINCT sale_id)}), sin netear: la fila es la demanda neta del día, pero el
 *       conteo de canastas informa cuántas ventas lo tocaron, incluida una totalmente anulada.</li>
 *   <li><b>{@code opening_qty}/{@code closing_qty}</b>: saldo del kardex ({@code stock_movements})
 *       al inicio y al cierre del día de negocio: el {@code balance_after} del último movimiento
 *       anterior al día y del último hasta su fin (si no hay movimientos, apertura 0 y cierre =
 *       apertura). El día se delimita con la zona de negocio del {@link BusinessClock}.</li>
 *   <li><b>{@code had_stockout}</b> según B.11 literal: apertura en cero (el día empezó sin stock)
 *       o algún movimiento del día dejó {@code balance_after = 0}.</li>
 *   <li><b>{@code refreshed_at}</b>: instante del recalculo, igual para todas las filas de una
 *       misma corrida.</li>
 * </ul>
 *
 * <h2>Qué días recalcula cada corrida</h2>
 * <ul>
 *   <li>{@link #refreshRecentDays(LocalDate)} (job de las 02:00, B.11): los <b>últimos 3 días
 *       completos</b> {@code [runDate−3, runDate−1]} — nunca el día en curso, que aún no cierra —
 *       más los <b>días de venta tocados por devoluciones</b> creadas dentro de esos mismos 3 días
 *       de negocio. Ese barrido extra es necesario por la atribución al día de venta: una
 *       devolución de hoy puede netear una venta antigua, y sin él esa fila quedaría desactualizada
 *       para siempre, porque las ventas y el kardex de un día ya cerrado son inmutables (APP). Con
 *       el barrido, todo evento queda cubierto por la corrida de la 02:00 siguiente.</li>
 *   <li>{@link #refreshRange(LocalDate, LocalDate)}: recalculo explícito de un rango, para el
 *       refresco a demanda que hace la corrida de reabastecimiento antes de leer la historia
 *       (API.md F.2, {@code POST /replenishment/runs} "ejecuta antes el agregado diario del
 *       rango") o para reparaciones manuales.</li>
 * </ul>
 *
 * <h2>Universo de filas</h2>
 * <p>El recalculo es <b>denso</b>: escribe una fila por cada (local activo × producto activo no
 * pack) y día, desde la fecha de creación del producto (días anteriores no existen y el
 * reabastecimiento los ignora, C.3). La densidad es la que da sentido a la regla
 * {@code had_stockout = opening_qty = 0}: un producto sin carga o un día tras el quiebre quedan
 * censurados aunque no haya vendido nada. Los packs no tienen fila propia porque su demanda ya está
 * consolidada en el producto base. Las filas fuera de los días recalculados no se tocan ni se
 * borran: los días ya cerrados son inmutables salvo por el barrido de devoluciones.
 */
@Service
public class DailySalesAggregateService {

    /** Días completos que recalcula el job nocturno (B.11: "el job recalcula los últimos 3 días"). */
    public static final int NIGHTLY_RECALC_DAYS = 3;

    /** Tope defensivo del refresco a demanda ({@link #refreshRange}). */
    private static final int MAX_REFRESH_DAYS = 366;

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    public DailySalesAggregateService(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Recalcula el agregado como lo hace el job de las 02:00 para una fecha de corrida dada:
     * los días {@code [runDate−3, runDate−1]} más los días de venta afectados por devoluciones
     * creadas en esos mismos 3 días de negocio.
     *
     * <p>El día en curso ({@code runDate}) queda fuera a propósito: a las 02:00 aún no tiene
     * ventas y su fila la escribe la corrida de la madrugada siguiente.
     *
     * @param runDate fecha de negocio en que "corre el job" (la del reloj de negocio)
     * @return filas insertadas o actualizadas
     */
    @Transactional
    public int refreshRecentDays(LocalDate runDate) {
        Objects.requireNonNull(runDate, "runDate");
        Instant refreshedAt = clock.now();

        // Días base: los NIGHTLY_RECALC_DAYS días completos anteriores a la corrida.
        Set<LocalDate> days = new LinkedHashSet<>();
        for (int back = NIGHTLY_RECALC_DAYS; back >= 1; back--) {
            days.add(runDate.minusDays(back));
        }

        // Barrido de devoluciones: días de venta neteados por devoluciones creadas en esos
        // mismos días de negocio [startOfDay(runDate−3), startOfDay(runDate)).
        OffsetDateTime windowStart = utc(clock.startOfDay(runDate.minusDays(NIGHTLY_RECALC_DAYS)));
        OffsetDateTime windowEnd = utc(clock.startOfDay(runDate));
        List<LocalDate> returnDays = jdbc.sql("""
                        SELECT DISTINCT sl.business_date
                        FROM sale_returns sr
                        JOIN sale_return_lines srl ON srl.sale_return_id = sr.id
                        JOIN sale_lines sl ON sl.id = srl.sale_line_id
                        WHERE sr.created_at >= :windowStart
                          AND sr.created_at < :windowEnd
                        """)
                .param("windowStart", windowStart)
                .param("windowEnd", windowEnd)
                .query(LocalDate.class)
                .list();
        days.addAll(returnDays);

        int rows = 0;
        for (LocalDate day : days) {
            rows += refreshDay(day, refreshedAt);
        }
        return rows;
    }

    /**
     * Recalcula el agregado de un rango explícito de días de negocio (ambos inclusive), día por
     * día, con la misma semántica que el job nocturno. Lo usa la corrida de reabastecimiento antes
     * de leer la historia (F.2) y queda disponible para reparaciones puntuales.
     *
     * @param from primer día a recalcular
     * @param to   último día a recalcular
     * @return filas insertadas o actualizadas
     * @throws BusinessException 400 {@code VALIDATION_ERROR} si el rango es inválido o supera
     *                            {@value #MAX_REFRESH_DAYS} días
     */
    @Transactional
    public int refreshRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El primer día del recalculo no puede ser posterior al último.");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_REFRESH_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "El recalculo del agregado admite a lo más " + MAX_REFRESH_DAYS + " días.");
        }
        Instant refreshedAt = clock.now();
        int rows = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            rows += refreshDay(day, refreshedAt);
        }
        return rows;
    }

    /**
     * Recalcula un día completo: {@code UPSERT} set-based de una fila por cada (local activo ×
     * producto activo no pack) creado a más tardar ese día.
     *
     * <p>Las ventas se leen por {@code sale_lines} (columnas desnormalizadas {@code store_id} y
     * {@code business_date}, pensadas para reportes sin join) agrupadas por
     * {@code stock_product_id}; las devoluciones se unen por su línea de venta original; el saldo
     * inicial y final viene del kardex. Los días límite se calculan con la zona de negocio, no con
     * la de la JVM.
     *
     * @param day         día de negocio a recalcular
     * @param refreshedAt instante común de la corrida (columna {@code refreshed_at})
     * @return filas insertadas o actualizadas
     */
    private int refreshDay(LocalDate day, Instant refreshedAt) {
        OffsetDateTime dayStart = utc(clock.startOfDay(day));
        OffsetDateTime nextDayStart = utc(clock.startOfDay(day.plusDays(1)));
        return jdbc.sql("""
                        WITH universe AS (
                            SELECT st.id AS store_id, pr.id AS product_id
                            FROM stores st
                            CROSS JOIN products pr
                            WHERE st.active
                              AND pr.active
                              AND pr.sale_type <> 'PACK'
                              AND pr.created_at < :nextDayStart
                        ),
                        opening AS (
                            SELECT DISTINCT ON (sm.store_id, sm.product_id)
                                   sm.store_id, sm.product_id, sm.balance_after AS opening_qty
                            FROM stock_movements sm
                            WHERE sm.occurred_at < :dayStart
                            ORDER BY sm.store_id, sm.product_id, sm.occurred_at DESC, sm.id DESC
                        ),
                        closing AS (
                            SELECT DISTINCT ON (sm.store_id, sm.product_id)
                                   sm.store_id, sm.product_id, sm.balance_after AS closing_qty
                            FROM stock_movements sm
                            WHERE sm.occurred_at < :nextDayStart
                            ORDER BY sm.store_id, sm.product_id, sm.occurred_at DESC, sm.id DESC
                        ),
                        day_stockouts AS (
                            SELECT DISTINCT sm.store_id, sm.product_id
                            FROM stock_movements sm
                            WHERE sm.occurred_at >= :dayStart
                              AND sm.occurred_at < :nextDayStart
                              AND sm.balance_after = 0
                        ),
                        sold AS (
                            SELECT sl.store_id,
                                   sl.stock_product_id AS product_id,
                                   SUM(sl.stock_quantity) AS qty_sold,
                                   SUM(sl.line_total_gross) AS gross_amount,
                                   SUM(sl.line_net) AS net_amount,
                                   SUM(sl.line_cost) AS cost_amount,
                                   COUNT(DISTINCT sl.sale_id) AS transactions
                            FROM sale_lines sl
                            WHERE sl.business_date = :day
                            GROUP BY sl.store_id, sl.stock_product_id
                        ),
                        refunded AS (
                            SELECT sl.store_id,
                                   sl.stock_product_id AS product_id,
                                   SUM(srl.stock_quantity) AS qty_returned,
                                   SUM(srl.refund_amount) AS gross_refund,
                                   SUM(srl.refund_net) AS net_refund,
                                   SUM(ROUND(sl.unit_cost * srl.stock_quantity, 2)) AS cost_refund
                            FROM sale_return_lines srl
                            JOIN sale_lines sl ON sl.id = srl.sale_line_id
                            WHERE sl.business_date = :day
                            GROUP BY sl.store_id, sl.stock_product_id
                        )
                        INSERT INTO daily_product_sales (
                            store_id, product_id, sale_date, qty_sold,
                            gross_amount, net_amount, cost_amount, transactions,
                            opening_qty, closing_qty, had_stockout, refreshed_at
                        )
                        SELECT u.store_id,
                               u.product_id,
                               :day,
                               COALESCE(so.qty_sold, 0) - COALESCE(re.qty_returned, 0),
                               COALESCE(so.gross_amount, 0) - COALESCE(re.gross_refund, 0),
                               COALESCE(so.net_amount, 0) - COALESCE(re.net_refund, 0),
                               COALESCE(so.cost_amount, 0) - COALESCE(re.cost_refund, 0),
                               COALESCE(so.transactions, 0),
                               COALESCE(op.opening_qty, 0),
                               COALESCE(cl.closing_qty, COALESCE(op.opening_qty, 0)),
                               COALESCE(op.opening_qty, 0) = 0
                                   OR EXISTS (
                                       SELECT 1 FROM day_stockouts z
                                       WHERE z.store_id = u.store_id AND z.product_id = u.product_id
                                   ),
                               :refreshedAt
                        FROM universe u
                        LEFT JOIN opening op ON op.store_id = u.store_id AND op.product_id = u.product_id
                        LEFT JOIN closing cl ON cl.store_id = u.store_id AND cl.product_id = u.product_id
                        LEFT JOIN sold so ON so.store_id = u.store_id AND so.product_id = u.product_id
                        LEFT JOIN refunded re ON re.store_id = u.store_id AND re.product_id = u.product_id
                        ON CONFLICT (store_id, product_id, sale_date) DO UPDATE SET
                            qty_sold = EXCLUDED.qty_sold,
                            gross_amount = EXCLUDED.gross_amount,
                            net_amount = EXCLUDED.net_amount,
                            cost_amount = EXCLUDED.cost_amount,
                            transactions = EXCLUDED.transactions,
                            opening_qty = EXCLUDED.opening_qty,
                            closing_qty = EXCLUDED.closing_qty,
                            had_stockout = EXCLUDED.had_stockout,
                            refreshed_at = EXCLUDED.refreshed_at
                        """)
                .param("day", day)
                .param("dayStart", dayStart)
                .param("nextDayStart", nextDayStart)
                .param("refreshedAt", utc(refreshedAt))
                .update();
    }

    /**
     * Convierte un instante a {@link OffsetDateTime} UTC para enlazarlo como parámetro
     * {@code timestamptz} (convención del resto de los repositorios JDBC del proyecto).
     */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
