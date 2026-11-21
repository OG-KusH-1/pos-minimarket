package cl.minimarket.pos.reporting.sales.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Proyecciones de aplicación para los reportes de venta (T84, {@code API.md} F.3). Se mantienen
 * separadas de los DTOs HTTP ({@code web}) para respetar el aislamiento de capas.
 */
public final class SalesReportsViews {

    private SalesReportsViews() {
    }

    /** Fila agregada del reporte de ventas ({@code GET /reports/sales}). */
    public record SalesReportView(
            String key,
            String label,
            long transactions,
            BigDecimal grossTotal,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            BigDecimal returnsTotal,
            BigDecimal avgTicket
    ) {
    }

    /** Fila del reporte de ventas por cajero ({@code GET /reports/sales-by-cashier}). */
    public record CashierSalesView(
            Long userId,
            String name,
            long transactions,
            BigDecimal total,
            BigDecimal avgTicket,
            long voids,
            long returns,
            BigDecimal discountTotal,
            long overridesCount
    ) {
    }

    /** Fila del reporte por medio de pago ({@code GET /reports/sales-by-payment-method}). */
    public record PaymentMethodView(
            String method,
            long transactions,
            BigDecimal total,
            BigDecimal pct
    ) {
    }

    /** Fila del ranking de productos ({@code GET /reports/product-ranking}). */
    public record ProductRankingView(
            Long productId,
            String sku,
            String name,
            String category,
            BigDecimal qty,
            BigDecimal netSales,
            long transactions,
            int rank
    ) {
    }

    /** Fila del reporte de productos sin rotación ({@code GET /reports/no-rotation}). */
    public record NoRotationView(
            Long productId,
            String sku,
            String name,
            BigDecimal stock,
            BigDecimal valuation,
            Instant lastSaleAt,
            Long daysWithoutSale
    ) {
    }

    /** Fila del análisis ABC ({@code GET /reports/abc}). */
    public record AbcView(
            Long productId,
            String sku,
            String name,
            BigDecimal metricValue,
            BigDecimal pct,
            BigDecimal cumulativePct,
            String abcClass
    ) {
    }
}
