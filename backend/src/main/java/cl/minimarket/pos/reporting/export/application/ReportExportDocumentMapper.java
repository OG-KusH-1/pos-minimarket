package cl.minimarket.pos.reporting.export.application;

import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.AlertsView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.HourlySalesView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.PaymentMixView;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView.TopProductView;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument.ExportSection;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument.LabelValue;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument.PdfColumn;
import cl.minimarket.pos.reporting.export.domain.ReportExportType;
import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import cl.minimarket.pos.reporting.inventory.InventoryValuationRow;
import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import cl.minimarket.pos.reporting.margin.MarginRow;
import cl.minimarket.pos.reporting.purchasing.PurchasesBySupplierRow;
import cl.minimarket.pos.reporting.sales.application.AbcMetric;
import cl.minimarket.pos.reporting.sales.application.RankingMetric;
import cl.minimarket.pos.reporting.sales.application.RankingOrder;
import cl.minimarket.pos.reporting.sales.application.SalesGroupBy;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.AbcView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.CashierSalesView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.NoRotationView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.PaymentMethodView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.ProductRankingView;
import cl.minimarket.pos.reporting.sales.application.SalesReportsViews.SalesReportView;
import cl.minimarket.pos.reporting.shared.ReportFilter;
import cl.minimarket.pos.reporting.shared.ReportResponse;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageGroupBy;
import cl.minimarket.pos.reporting.shrinkage.ShrinkageReportRow;
import cl.minimarket.pos.shared.export.XlsxWriter;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatClp;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatDate;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatDecimal;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatInstant;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatInteger;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatPercent;
import static cl.minimarket.pos.reporting.export.application.ReportExportFormatter.formatText;

/**
 * Mapea las respuestas de los 13 reportes del módulo {@code reporting} hacia
 * {@link ReportExportDocument}, manteniendo alineadas las columnas y tipos entre XLSX y PDF.
 */
final class ReportExportDocumentMapper {

    private ReportExportDocumentMapper() {
    }

    /** Definición unificada de columna para mantener paridad exacta entre PDF y XLSX. */
    private record ColSpec(String header, XlsxWriter.ColumnType xlsxType, boolean pdfNumeric) {

        static ColSpec text(String header) {
            return new ColSpec(header, XlsxWriter.ColumnType.TEXT, false);
        }

        static ColSpec clp(String header) {
            return new ColSpec(header, XlsxWriter.ColumnType.CLP, true);
        }

        static ColSpec qty(String header) {
            return new ColSpec(header, XlsxWriter.ColumnType.QUANTITY, true);
        }

        static ColSpec date(String header) {
            return new ColSpec(header, XlsxWriter.ColumnType.DATE, false);
        }

        static ColSpec dateTime(String header) {
            return new ColSpec(header, XlsxWriter.ColumnType.DATETIME, false);
        }
    }

    static ReportExportDocument toDashboardDocument(DashboardView view, String storeLabels) {
        List<LabelValue> filters = List.of(
                new LabelValue("Fecha de negocio", formatDate(view.date())),
                new LabelValue("Locales", storeLabels));

        String vsPrevText = view.vsPreviousDayPct() == null
                ? "Sin referencia"
                : formatPercent(view.vsPreviousDayPct());
        List<LabelValue> kpis = List.of(
                new LabelValue("Ventas netas del día", formatClp(view.salesTotal())),
                new LabelValue("Transacciones", formatInteger(view.transactions())),
                new LabelValue("Ticket promedio", formatClp(view.avgTicket())),
                new LabelValue("Variación vs día anterior", vsPrevText));

        AlertsView alerts = view.alerts();
        List<List<String>> alertPdfRows = List.of(
                List.of("Stock bajo mínimo", formatInteger(alerts.belowMin())),
                List.of("Sin stock", formatInteger(alerts.zeroStock())),
                List.of("Lotes por vencer o vencidos", formatInteger(alerts.expiringLots())),
                List.of("Mermas pendientes de aprobación", formatInteger(alerts.pendingShrinkages())),
                List.of("Ajustes de stock pendientes", formatInteger(alerts.pendingAdjustments())),
                List.of("Tomas de inventario pendientes", formatInteger(alerts.pendingCounts())),
                List.of("Diferencias de caja pendientes", formatInteger(alerts.pendingCashDifferences())));

        ExportSection alertsSection = new ExportSection(
                "Alertas operativas",
                List.of(PdfColumn.text("Alerta"), PdfColumn.num("Cantidad")),
                alertPdfRows,
                null,
                "Sin alertas.");

        List<List<String>> topPdfRows = view.topProducts().stream()
                .map(p -> List.of(
                        formatInteger(p.productId()),
                        formatText(p.name()),
                        formatDecimal(p.qty(), 3),
                        formatClp(p.total())))
                .toList();
        ExportSection topSection = new ExportSection(
                "Top 10 productos del día",
                List.of(PdfColumn.num("ID"), PdfColumn.text("Producto"),
                        PdfColumn.num("Cantidad"), PdfColumn.num("Total CLP")),
                topPdfRows,
                null,
                "Sin ventas registradas en el día.");

        List<List<String>> mixPdfRows = view.paymentMix().stream()
                .map(m -> List.of(formatText(m.method()), formatClp(m.total())))
                .toList();
        ExportSection mixSection = new ExportSection(
                "Mix por medio de pago",
                List.of(PdfColumn.text("Medio de pago"), PdfColumn.num("Total CLP")),
                mixPdfRows,
                null,
                "Sin pagos registrados en el día.");

        List<List<String>> hourPdfRows = view.salesByHour().stream()
                .map(h -> List.of(
                        String.format("%02d:00", h.hour()),
                        formatInteger(h.transactions()),
                        formatClp(h.total())))
                .toList();
        ExportSection hourSection = new ExportSection(
                "Ventas por hora",
                List.of(PdfColumn.text("Hora"), PdfColumn.num("Transacciones"), PdfColumn.num("Total CLP")),
                hourPdfRows,
                null,
                "Sin ventas registradas.");

        List<XlsxWriter.Column> xlsxColumns = List.of(
                XlsxWriter.Column.text("Sección"),
                XlsxWriter.Column.text("Concepto"),
                XlsxWriter.Column.quantity("Cantidad"),
                XlsxWriter.Column.clp("Monto CLP"),
                XlsxWriter.Column.text("Detalle"));

        List<List<Object>> xlsxRows = new ArrayList<>();
        xlsxRows.add(Arrays.asList("Resumen", "Ventas netas del día",
                BigDecimal.valueOf(view.transactions()), view.salesTotal(), vsPrevText));
        xlsxRows.add(Arrays.asList("Resumen", "Ticket promedio", null, view.avgTicket(), null));
        addAlertXlsxRow(xlsxRows, "Stock bajo mínimo", alerts.belowMin());
        addAlertXlsxRow(xlsxRows, "Sin stock", alerts.zeroStock());
        addAlertXlsxRow(xlsxRows, "Lotes por vencer o vencidos", alerts.expiringLots());
        addAlertXlsxRow(xlsxRows, "Mermas pendientes de aprobación", alerts.pendingShrinkages());
        addAlertXlsxRow(xlsxRows, "Ajustes de stock pendientes", alerts.pendingAdjustments());
        addAlertXlsxRow(xlsxRows, "Tomas de inventario pendientes", alerts.pendingCounts());
        addAlertXlsxRow(xlsxRows, "Diferencias de caja pendientes", alerts.pendingCashDifferences());
        for (PaymentMixView m : view.paymentMix()) {
            xlsxRows.add(Arrays.asList("Medios de pago", m.method(), null, m.total(), null));
        }
        for (TopProductView p : view.topProducts()) {
            xlsxRows.add(Arrays.asList("Top 10 productos", p.name(), p.qty(), p.total(),
                    "ID " + p.productId()));
        }
        for (HourlySalesView h : view.salesByHour()) {
            xlsxRows.add(Arrays.asList("Ventas por hora", String.format("%02d:00", h.hour()),
                    BigDecimal.valueOf(h.transactions()), h.total(), null));
        }

        return new ReportExportDocument(
                ReportExportType.DASHBOARD,
                ReportExportType.DASHBOARD.defaultTitle(),
                null,
                filters,
                kpis,
                List.of(alertsSection, topSection, mixSection, hourSection),
                xlsxColumns,
                xlsxRows,
                xlsxRows.size());
    }

    static ReportExportDocument toSalesDocument(
            ReportResponse<SalesReportView> response,
            SalesGroupBy groupBy,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        filters.add(new LabelValue("Agrupación", salesGroupLabel(groupBy)));

        List<ColSpec> cols = List.of(
                ColSpec.text("Clave"),
                ColSpec.text("Grupo"),
                ColSpec.clp("Transacciones"),
                ColSpec.clp("Total Bruto"),
                ColSpec.clp("Total Neto"),
                ColSpec.clp("Impuestos"),
                ColSpec.clp("Descuentos"),
                ColSpec.clp("Devoluciones"),
                ColSpec.clp("Ticket Promedio"));

        return buildTable(
                ReportExportType.SALES,
                "Agrupado por " + salesGroupLabel(groupBy).toLowerCase(),
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.key(), r.label(), r.transactions(), r.grossTotal(), r.netTotal(),
                        r.taxTotal(), r.discountTotal(), r.returnsTotal(), r.avgTicket()),
                r -> List.of(
                        formatText(r.key()),
                        formatText(r.label()),
                        formatInteger(r.transactions()),
                        formatClp(r.grossTotal()),
                        formatClp(r.netTotal()),
                        formatClp(r.taxTotal()),
                        formatClp(r.discountTotal()),
                        formatClp(r.returnsTotal()),
                        formatClp(r.avgTicket())));
    }

    static ReportExportDocument toSalesByCashierDocument(
            ReportResponse<CashierSalesView> response,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        List<ColSpec> cols = List.of(
                ColSpec.clp("ID"),
                ColSpec.text("Cajero"),
                ColSpec.clp("Transacciones"),
                ColSpec.clp("Total CLP"),
                ColSpec.clp("Ticket Promedio"),
                ColSpec.clp("Anulaciones"),
                ColSpec.clp("Devoluciones"),
                ColSpec.clp("Descuentos"),
                ColSpec.clp("Overrides"));

        return buildTable(
                ReportExportType.SALES_BY_CASHIER,
                null,
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.userId(), r.name(), r.transactions(), r.total(), r.avgTicket(),
                        r.voids(), r.returns(), r.discountTotal(), r.overridesCount()),
                r -> List.of(
                        formatInteger(r.userId()),
                        formatText(r.name()),
                        formatInteger(r.transactions()),
                        formatClp(r.total()),
                        formatClp(r.avgTicket()),
                        formatInteger(r.voids()),
                        formatInteger(r.returns()),
                        formatClp(r.discountTotal()),
                        formatInteger(r.overridesCount())));
    }

    static ReportExportDocument toSalesByPaymentMethodDocument(
            ReportResponse<PaymentMethodView> response,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        List<ColSpec> cols = List.of(
                ColSpec.text("Medio de pago"),
                ColSpec.clp("Transacciones"),
                ColSpec.clp("Total CLP"),
                ColSpec.qty("Participación %"));

        return buildTable(
                ReportExportType.SALES_BY_PAYMENT_METHOD,
                null,
                filters,
                cols,
                response,
                r -> Arrays.asList(r.method(), r.transactions(), r.total(), r.pct()),
                r -> List.of(
                        formatText(r.method()),
                        formatInteger(r.transactions()),
                        formatClp(r.total()),
                        formatPercent(r.pct())));
    }

    static ReportExportDocument toProductRankingDocument(
            ReportResponse<ProductRankingView> response,
            RankingOrder order,
            RankingMetric metric,
            int limit,
            String categoryName,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        filters.add(new LabelValue("Orden", order == RankingOrder.MOST ? "Más vendidos" : "Menos vendidos"));
        filters.add(new LabelValue("Métrica", metric == RankingMetric.QTY ? "Cantidad" : "Venta neta"));
        filters.add(new LabelValue("Límite", formatInteger(limit)));
        if (categoryName != null) {
            filters.add(new LabelValue("Categoría", categoryName));
        }

        List<ColSpec> cols = List.of(
                ColSpec.clp("Posición"),
                ColSpec.text("SKU"),
                ColSpec.text("Producto"),
                ColSpec.text("Categoría"),
                ColSpec.qty("Cantidad"),
                ColSpec.clp("Venta Neta CLP"),
                ColSpec.clp("Transacciones"));

        return buildTable(
                ReportExportType.PRODUCT_RANKING,
                null,
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.rank(), r.sku(), r.name(), r.category(),
                        r.qty(), r.netSales(), r.transactions()),
                r -> List.of(
                        formatInteger(r.rank()),
                        formatText(r.sku()),
                        formatText(r.name()),
                        formatText(r.category()),
                        formatDecimal(r.qty(), 3),
                        formatClp(r.netSales()),
                        formatInteger(r.transactions())));
    }

    static ReportExportDocument toNoRotationDocument(
            ReportResponse<NoRotationView> response,
            int days,
            boolean includeCost,
            String storeLabels,
            ZoneId businessZone) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        filters.add(new LabelValue("Días sin venta", formatInteger(days)));

        List<ColSpec> cols = new ArrayList<>();
        cols.add(ColSpec.text("SKU"));
        cols.add(ColSpec.text("Producto"));
        cols.add(ColSpec.qty("Stock"));
        if (includeCost) {
            cols.add(ColSpec.qty("Valorización"));
        }
        cols.add(ColSpec.dateTime("Última venta"));
        cols.add(ColSpec.clp("Días sin venta"));

        return buildTable(
                ReportExportType.NO_ROTATION,
                "Sin ventas en los últimos " + days + " días",
                filters,
                cols,
                response,
                r -> {
                    List<Object> row = new ArrayList<>();
                    row.add(r.sku());
                    row.add(r.name());
                    row.add(r.stock());
                    if (includeCost) {
                        row.add(r.valuation());
                    }
                    row.add(r.lastSaleAt());
                    row.add(r.daysWithoutSale());
                    return row;
                },
                r -> {
                    List<String> row = new ArrayList<>();
                    row.add(formatText(r.sku()));
                    row.add(formatText(r.name()));
                    row.add(formatDecimal(r.stock(), 3));
                    if (includeCost) {
                        row.add(formatDecimal(r.valuation(), 2));
                    }
                    row.add(formatInstant(r.lastSaleAt(), businessZone));
                    row.add(formatInteger(r.daysWithoutSale()));
                    return row;
                });
    }

    static ReportExportDocument toAbcDocument(
            ReportResponse<AbcView> response,
            AbcMetric metric,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        String metricLabel = switch (metric) {
            case AMOUNT -> "Venta neta CLP";
            case QTY -> "Cantidad";
            case MARGIN -> "Margen bruto";
        };
        filters.add(new LabelValue("Métrica", metricLabel));

        ColSpec metricCol = metric == AbcMetric.AMOUNT
                ? ColSpec.clp(metricLabel)
                : ColSpec.qty(metricLabel);

        List<ColSpec> cols = List.of(
                ColSpec.text("SKU"),
                ColSpec.text("Producto"),
                metricCol,
                ColSpec.qty("Participación %"),
                ColSpec.qty("% Acumulado"),
                ColSpec.text("Clase ABC"));

        return buildTable(
                ReportExportType.ABC,
                "Clasificación por " + metricLabel.toLowerCase(),
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.sku(), r.name(), r.metricValue(), r.pct(), r.cumulativePct(), r.abcClass()),
                r -> List.of(
                        formatText(r.sku()),
                        formatText(r.name()),
                        formatAbcMetricValue(metric, r.metricValue()),
                        formatPercent(r.pct()),
                        formatPercent(r.cumulativePct()),
                        formatText(r.abcClass())));
    }

    static ReportExportDocument toMarginDocument(
            ReportResponse<MarginRow> response,
            MarginGroupBy groupBy,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        String groupLabel = marginGroupLabel(groupBy);
        filters.add(new LabelValue("Agrupación", groupLabel));

        List<ColSpec> cols = List.of(
                ColSpec.text("Clave"),
                ColSpec.text("Grupo"),
                ColSpec.qty("Cantidad"),
                ColSpec.clp("Venta Neta CLP"),
                ColSpec.qty("Costo"),
                ColSpec.qty("Margen Bruto"),
                ColSpec.qty("Margen %"));

        return buildTable(
                ReportExportType.MARGIN,
                "Agrupado por " + groupLabel.toLowerCase(),
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.key(), r.label(), r.qty(), r.netSales(), r.cost(), r.grossMargin(), r.marginPct()),
                r -> List.of(
                        formatText(r.key()),
                        formatText(r.label()),
                        formatDecimal(r.qty(), 3),
                        formatClp(r.netSales()),
                        formatDecimal(r.cost(), 2),
                        formatDecimal(r.grossMargin(), 2),
                        formatPercent(r.marginPct())));
    }

    static ReportExportDocument toInventoryValuationDocument(
            ReportResponse<InventoryValuationRow> response,
            InventoryValuationGroupBy groupBy,
            String storeLabels) {
        List<LabelValue> filters = optionalRangeFilters(response.filters(), storeLabels);
        String groupLabel = inventoryGroupLabel(groupBy);
        filters.add(new LabelValue("Agrupación", groupLabel));

        List<ColSpec> cols = List.of(
                ColSpec.text("Clave"),
                ColSpec.text("Grupo"),
                ColSpec.qty("Cantidad"),
                ColSpec.qty("Valorización"),
                ColSpec.qty("Participación %"));

        return buildTable(
                ReportExportType.INVENTORY_VALUATION,
                "Agrupado por " + groupLabel.toLowerCase(),
                filters,
                cols,
                response,
                r -> Arrays.asList(r.key(), r.label(), r.qty(), r.valuation(), r.pct()),
                r -> List.of(
                        formatText(r.key()),
                        formatText(r.label()),
                        formatDecimal(r.qty(), 3),
                        formatDecimal(r.valuation(), 2),
                        formatPercent(r.pct())));
    }

    static ReportExportDocument toPurchasesBySupplierDocument(
            ReportResponse<PurchasesBySupplierRow> response,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);

        List<ColSpec> cols = List.of(
                ColSpec.clp("ID"),
                ColSpec.text("Proveedor"),
                ColSpec.clp("Órdenes"),
                ColSpec.clp("Recepciones"),
                ColSpec.clp("Total Neto CLP"),
                ColSpec.qty("Lead Time Prom. (días)"),
                ColSpec.qty("Cumplimiento %"));

        return buildTable(
                ReportExportType.PURCHASES_BY_SUPPLIER,
                null,
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.supplierId(), r.name(), r.orders(), r.receipts(), r.netTotal(),
                        r.avgLeadTimeDays(), r.fillRatePct()),
                r -> List.of(
                        formatInteger(r.supplierId()),
                        formatText(r.name()),
                        formatInteger(r.orders()),
                        formatInteger(r.receipts()),
                        formatClp(r.netTotal()),
                        formatDecimal(r.avgLeadTimeDays(), 2),
                        formatPercent(r.fillRatePct())));
    }

    static ReportExportDocument toShrinkageDocument(
            ReportResponse<ShrinkageReportRow> response,
            ShrinkageGroupBy groupBy,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        String groupLabel = shrinkageGroupLabel(groupBy);
        filters.add(new LabelValue("Agrupación", groupLabel));

        return buildShrinkageTable(
                ReportExportType.SHRINKAGE,
                "Agrupado por " + groupLabel.toLowerCase(),
                "Grupo",
                filters,
                response);
    }

    static ReportExportDocument toShrinkageTopProductsDocument(
            ReportResponse<ShrinkageReportRow> response,
            Integer limit,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        if (limit != null) {
            filters.add(new LabelValue("Límite", formatInteger(limit)));
        }
        return buildShrinkageTable(
                ReportExportType.SHRINKAGE_TOP_PRODUCTS,
                null,
                "Producto",
                filters,
                response);
    }

    static ReportExportDocument toCashDifferencesDocument(
            ReportResponse<CashDifferenceRow> response,
            Long cashierId,
            String cashierName,
            Boolean withDifference,
            String storeLabels) {
        List<LabelValue> filters = baseRangeFilters(response.filters(), storeLabels);
        if (cashierId != null) {
            filters.add(new LabelValue("Cajero", cashierName != null ? cashierName : "ID " + cashierId));
        }
        if (withDifference != null) {
            filters.add(new LabelValue("Solo con diferencia", Boolean.TRUE.equals(withDifference) ? "Sí" : "No"));
        }

        List<ColSpec> cols = List.of(
                ColSpec.text("Turno"),
                ColSpec.date("Fecha"),
                ColSpec.text("Cajero"),
                ColSpec.text("Terminal"),
                ColSpec.clp("Esperado CLP"),
                ColSpec.clp("Contado CLP"),
                ColSpec.clp("Diferencia CLP"),
                ColSpec.text("Aprobado por"));

        return buildTable(
                ReportExportType.CASH_DIFFERENCES,
                null,
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.number(), r.businessDate(), r.cashier(), r.terminal(),
                        r.expected(), r.counted(), r.difference(), r.approvedBy()),
                r -> List.of(
                        formatText(r.number()),
                        formatDate(r.businessDate()),
                        formatText(r.cashier()),
                        formatText(r.terminal()),
                        formatClp(r.expected()),
                        formatClp(r.counted()),
                        formatClp(r.difference()),
                        formatText(r.approvedBy())));
    }

    private static ReportExportDocument buildShrinkageTable(
            ReportExportType type,
            String subtitle,
            String labelHeader,
            List<LabelValue> filters,
            ReportResponse<ShrinkageReportRow> response) {
        List<ColSpec> cols = List.of(
                ColSpec.text("Clave"),
                ColSpec.text(labelHeader),
                ColSpec.clp("Registros"),
                ColSpec.qty("Cantidad"),
                ColSpec.qty("Costo"),
                ColSpec.qty("% sobre Ventas"));

        return buildTable(
                type,
                subtitle,
                filters,
                cols,
                response,
                r -> Arrays.asList(
                        r.key(), r.label(), r.count(), r.qty(), r.cost(), r.pctOfSales()),
                r -> List.of(
                        formatText(r.key()),
                        formatText(r.label()),
                        formatInteger(r.count()),
                        formatDecimal(r.qty(), 3),
                        formatDecimal(r.cost(), 2),
                        formatPercent(r.pctOfSales())));
    }

    private static <T> ReportExportDocument buildTable(
            ReportExportType type,
            String subtitle,
            List<LabelValue> filters,
            List<ColSpec> cols,
            ReportResponse<T> response,
            Function<T, List<Object>> xlsxMapper,
            Function<T, List<String>> pdfMapper) {
        List<PdfColumn> pdfColumns = cols.stream()
                .map(c -> new PdfColumn(c.header(), c.pdfNumeric()))
                .toList();
        List<XlsxWriter.Column> xlsxColumns = cols.stream()
                .map(c -> new XlsxWriter.Column(c.header(), c.xlsxType(), 0))
                .toList();

        List<List<String>> pdfRows = new ArrayList<>(response.rows().size());
        List<List<Object>> xlsxRows = new ArrayList<>(response.rows().size() + 1);
        for (T row : response.rows()) {
            pdfRows.add(pdfMapper.apply(row));
            xlsxRows.add(xlsxMapper.apply(row));
        }

        List<String> pdfTotals = null;
        if (response.totals() != null) {
            pdfTotals = pdfMapper.apply(response.totals());
            xlsxRows.add(xlsxMapper.apply(response.totals()));
        }

        return ReportExportDocument.singleTable(
                type,
                subtitle,
                filters,
                pdfColumns,
                pdfRows,
                pdfTotals,
                xlsxColumns,
                xlsxRows,
                response.rows().size());
    }

    private static List<LabelValue> baseRangeFilters(ReportFilter filter, String storeLabels) {
        List<LabelValue> list = new ArrayList<>();
        list.add(new LabelValue("Período", formatDate(filter.from()) + " al " + formatDate(filter.to())));
        list.add(new LabelValue("Locales", storeLabels));
        return list;
    }

    private static List<LabelValue> optionalRangeFilters(ReportFilter filter, String storeLabels) {
        List<LabelValue> list = new ArrayList<>();
        if (filter.hasRange()) {
            list.add(new LabelValue("Período", formatDate(filter.from()) + " al " + formatDate(filter.to())));
        } else {
            list.add(new LabelValue("Corte", "Stock actual"));
        }
        list.add(new LabelValue("Locales", storeLabels));
        return list;
    }

    private static void addAlertXlsxRow(List<List<Object>> rows, String label, long count) {
        rows.add(Arrays.asList("Alertas", label, BigDecimal.valueOf(count), null, null));
    }

    private static String formatAbcMetricValue(AbcMetric metric, BigDecimal value) {
        return switch (metric) {
            case AMOUNT -> formatClp(value);
            case QTY -> formatDecimal(value, 3);
            case MARGIN -> formatDecimal(value, 2);
        };
    }

    private static String salesGroupLabel(SalesGroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> "Día";
            case HOUR -> "Hora";
            case WEEKDAY -> "Día de semana";
            case CATEGORY -> "Categoría";
            case STORE -> "Local";
        };
    }

    private static String marginGroupLabel(MarginGroupBy groupBy) {
        return switch (groupBy) {
            case PRODUCT -> "Producto";
            case CATEGORY -> "Categoría";
            case BRAND -> "Marca";
            case DAY -> "Día";
        };
    }

    private static String inventoryGroupLabel(InventoryValuationGroupBy groupBy) {
        return switch (groupBy) {
            case CATEGORY -> "Categoría";
            case STORE -> "Local";
            case PRODUCT -> "Producto";
        };
    }

    private static String shrinkageGroupLabel(ShrinkageGroupBy groupBy) {
        return switch (groupBy) {
            case TYPE -> "Tipo";
            case PRODUCT -> "Producto";
            case CATEGORY -> "Categoría";
            case STORE -> "Local";
            case MONTH -> "Mes";
        };
    }
}
