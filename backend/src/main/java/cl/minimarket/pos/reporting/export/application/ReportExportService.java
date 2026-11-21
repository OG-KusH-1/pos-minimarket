package cl.minimarket.pos.reporting.export.application;

import cl.minimarket.pos.audit.AuditEvent;
import cl.minimarket.pos.audit.AuditService;
import cl.minimarket.pos.reporting.cash.CashDifferenceRow;
import cl.minimarket.pos.reporting.cash.application.CashDifferenceReportService;
import cl.minimarket.pos.reporting.dashboard.application.DashboardService;
import cl.minimarket.pos.reporting.dashboard.application.DashboardView;
import cl.minimarket.pos.reporting.export.domain.ExportFormat;
import cl.minimarket.pos.reporting.export.domain.ExportedReport;
import cl.minimarket.pos.reporting.export.domain.ReportExportDocument;
import cl.minimarket.pos.reporting.export.domain.ReportExportType;
import cl.minimarket.pos.reporting.export.infrastructure.ReportExportMetadataRepository;
import cl.minimarket.pos.reporting.inventory.InventoryValuationGroupBy;
import cl.minimarket.pos.reporting.inventory.InventoryValuationRow;
import cl.minimarket.pos.reporting.inventory.application.InventoryValuationReportService;
import cl.minimarket.pos.reporting.margin.MarginGroupBy;
import cl.minimarket.pos.reporting.margin.MarginRow;
import cl.minimarket.pos.reporting.margin.application.MarginReportService;
import cl.minimarket.pos.reporting.purchasing.PurchasesBySupplierRow;
import cl.minimarket.pos.reporting.purchasing.application.PurchasingReportService;
import cl.minimarket.pos.reporting.sales.application.AbcMetric;
import cl.minimarket.pos.reporting.sales.application.RankingMetric;
import cl.minimarket.pos.reporting.sales.application.RankingOrder;
import cl.minimarket.pos.reporting.sales.application.SalesGroupBy;
import cl.minimarket.pos.reporting.sales.application.SalesReportsService;
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
import cl.minimarket.pos.reporting.shrinkage.application.ShrinkageReportService;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Servicio de aplicación que coordina la exportación de todos los reportes a XLSX o PDF y
 * registra el evento {@code DATA_EXPORTED} en la bitácora inmutable ({@code HU-REP-10},
 * {@code API.md} F.2).
 *
 * <p>Toda operación abre una transacción de lectura/escritura porque {@link AuditService#record}
 * participa en la transacción del llamador ({@code Propagation.MANDATORY}). Si falla cualquier
 * validación o generación del archivo, la transacción hace rollback y no queda rastro huérfano en
 * {@code audit_log}.
 */
@Service
@Transactional
public class ReportExportService {

    private static final String ACTION_DATA_EXPORTED = "DATA_EXPORTED";
    private static final String ENTITY_REPORT = "report";

    private final DashboardService dashboardService;
    private final SalesReportsService salesReportsService;
    private final MarginReportService marginReportService;
    private final InventoryValuationReportService inventoryValuationReportService;
    private final PurchasingReportService purchasingReportService;
    private final ShrinkageReportService shrinkageReportService;
    private final CashDifferenceReportService cashDifferenceReportService;
    private final ReportExportMetadataRepository metadataRepository;
    private final ReportExportRenderer renderer;
    private final AuditService auditService;
    private final BusinessClock clock;

    public ReportExportService(
            DashboardService dashboardService,
            SalesReportsService salesReportsService,
            MarginReportService marginReportService,
            InventoryValuationReportService inventoryValuationReportService,
            PurchasingReportService purchasingReportService,
            ShrinkageReportService shrinkageReportService,
            CashDifferenceReportService cashDifferenceReportService,
            ReportExportMetadataRepository metadataRepository,
            ReportExportRenderer renderer,
            AuditService auditService,
            BusinessClock clock) {
        this.dashboardService = Objects.requireNonNull(dashboardService, "dashboardService");
        this.salesReportsService = Objects.requireNonNull(salesReportsService, "salesReportsService");
        this.marginReportService = Objects.requireNonNull(marginReportService, "marginReportService");
        this.inventoryValuationReportService = Objects.requireNonNull(
                inventoryValuationReportService, "inventoryValuationReportService");
        this.purchasingReportService = Objects.requireNonNull(purchasingReportService, "purchasingReportService");
        this.shrinkageReportService = Objects.requireNonNull(shrinkageReportService, "shrinkageReportService");
        this.cashDifferenceReportService = Objects.requireNonNull(
                cashDifferenceReportService, "cashDifferenceReportService");
        this.metadataRepository = Objects.requireNonNull(metadataRepository, "metadataRepository");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Exporta el dashboard ejecutivo diario en XLSX o PDF. */
    public ExportedReport exportDashboard(
            LocalDate date,
            List<Long> storeIds,
            Long activeStoreId,
            Long userId,
            boolean allStoreAccess,
            String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        DashboardView view = dashboardService.getDashboard(date, storeIds, activeStoreId, userId, allStoreAccess);

        List<Long> effectiveStoreIds = (storeIds == null || storeIds.isEmpty())
                ? List.of(activeStoreId)
                : storeIds.stream().distinct().toList();
        String storeLabels = metadataRepository.formatStoreNames(effectiveStoreIds);
        ReportExportDocument doc = ReportExportDocumentMapper.toDashboardDocument(view, storeLabels);

        Map<String, Object> filtersAudit = new LinkedHashMap<>();
        filtersAudit.put("date", view.date().toString());
        filtersAudit.put("storeIds", effectiveStoreIds);
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el reporte de ventas agrupadas en XLSX o PDF. */
    public ExportedReport exportSales(ReportFilter filter, SalesGroupBy groupBy, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<SalesReportView> response = salesReportsService.salesReport(filter, groupBy);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toSalesDocument(response, groupBy, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("groupBy", groupBy.name());
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el reporte de ventas por cajero en XLSX o PDF. */
    public ExportedReport exportSalesByCashier(ReportFilter filter, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<CashierSalesView> response = salesReportsService.salesByCashier(filter);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toSalesByCashierDocument(response, storeLabels);

        return finalizeExport(doc, exportFormat, rangeFiltersAudit(response.filters()));
    }

    /** Exporta el reporte de ventas por medio de pago en XLSX o PDF. */
    public ExportedReport exportSalesByPaymentMethod(ReportFilter filter, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<PaymentMethodView> response = salesReportsService.salesByPaymentMethod(filter);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toSalesByPaymentMethodDocument(response, storeLabels);

        return finalizeExport(doc, exportFormat, rangeFiltersAudit(response.filters()));
    }

    /** Exporta el ranking de productos más o menos vendidos en XLSX o PDF. */
    public ExportedReport exportProductRanking(
            ReportFilter filter,
            RankingOrder order,
            RankingMetric metric,
            int limit,
            Long categoryId,
            String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<ProductRankingView> response =
                salesReportsService.productRanking(filter, order, metric, limit, categoryId);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        String categoryName = metadataRepository.findCategoryName(categoryId).orElse(null);
        ReportExportDocument doc = ReportExportDocumentMapper.toProductRankingDocument(
                response, order, metric, limit, categoryName, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("order", order.name());
        filtersAudit.put("metric", metric.name());
        filtersAudit.put("limit", limit);
        if (categoryId != null) {
            filtersAudit.put("categoryId", categoryId);
        }
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /**
     * Exporta productos sin rotación en XLSX o PDF, omitiendo la columna de valorización cuando
     * {@code includeCost} es {@code false}.
     */
    public ExportedReport exportNoRotation(
            ReportFilter filter,
            int days,
            boolean includeCost,
            String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<NoRotationView> response = salesReportsService.noRotation(filter, days, includeCost);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toNoRotationDocument(
                response, days, includeCost, storeLabels, clock.zone());

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("days", days);
        filtersAudit.put("includeCosts", includeCost);
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el análisis Pareto ABC en XLSX o PDF. */
    public ExportedReport exportAbc(ReportFilter filter, AbcMetric metric, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<AbcView> response = salesReportsService.abc(filter, metric);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toAbcDocument(response, metric, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("metric", metric.name());
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el reporte de margen y utilidad en XLSX o PDF. */
    public ExportedReport exportMargin(ReportFilter filter, MarginGroupBy groupBy, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<MarginRow> response = marginReportService.generate(filter, groupBy);
        MarginGroupBy effectiveGroupBy = groupBy == null ? MarginGroupBy.PRODUCT : groupBy;
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toMarginDocument(
                response, effectiveGroupBy, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("groupBy", effectiveGroupBy.paramValue());
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta la valorización de inventario actual en XLSX o PDF. */
    public ExportedReport exportInventoryValuation(
            ReportFilter filter,
            InventoryValuationGroupBy groupBy,
            String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<InventoryValuationRow> response = inventoryValuationReportService.generate(filter, groupBy);
        InventoryValuationGroupBy effectiveGroupBy =
                groupBy == null ? InventoryValuationGroupBy.CATEGORY : groupBy;
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toInventoryValuationDocument(
                response, effectiveGroupBy, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("groupBy", effectiveGroupBy.paramValue());
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el reporte de compras por proveedor en XLSX o PDF. */
    public ExportedReport exportPurchasesBySupplier(ReportFilter filter, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<PurchasesBySupplierRow> response = purchasingReportService.generate(filter);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toPurchasesBySupplierDocument(response, storeLabels);

        return finalizeExport(doc, exportFormat, rangeFiltersAudit(response.filters()));
    }

    /** Exporta el reporte de mermas aprobadas en XLSX o PDF. */
    public ExportedReport exportShrinkage(ReportFilter filter, ShrinkageGroupBy groupBy, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<ShrinkageReportRow> response = shrinkageReportService.generate(filter, groupBy);
        ShrinkageGroupBy effectiveGroupBy = groupBy == null ? ShrinkageGroupBy.TYPE : groupBy;
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toShrinkageDocument(
                response, effectiveGroupBy, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("groupBy", effectiveGroupBy.paramValue());
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el ranking de productos con mayor costo de merma en XLSX o PDF. */
    public ExportedReport exportShrinkageTopProducts(ReportFilter filter, Integer limit, String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<ShrinkageReportRow> response = shrinkageReportService.generateTopProducts(filter, limit);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        ReportExportDocument doc = ReportExportDocumentMapper.toShrinkageTopProductsDocument(
                response, limit, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        filtersAudit.put("limit", limit == null ? ShrinkageReportService.DEFAULT_TOP_LIMIT : limit);
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    /** Exporta el reporte de diferencias de caja en XLSX o PDF. */
    public ExportedReport exportCashDifferences(
            ReportFilter filter,
            Long cashierId,
            Boolean withDifference,
            String format) {
        ExportFormat exportFormat = ExportFormat.fromParam(format);
        ReportResponse<CashDifferenceRow> response =
                cashDifferenceReportService.generate(filter, cashierId, withDifference);
        String storeLabels = metadataRepository.formatStoreNames(response.filters().storeIds());
        String cashierName = metadataRepository.findUserDisplayName(cashierId).orElse(null);
        ReportExportDocument doc = ReportExportDocumentMapper.toCashDifferencesDocument(
                response, cashierId, cashierName, withDifference, storeLabels);

        Map<String, Object> filtersAudit = rangeFiltersAudit(response.filters());
        if (cashierId != null) {
            filtersAudit.put("cashierId", cashierId);
        }
        if (withDifference != null) {
            filtersAudit.put("withDifference", withDifference);
        }
        return finalizeExport(doc, exportFormat, filtersAudit);
    }

    private ExportedReport finalizeExport(
            ReportExportDocument document,
            ExportFormat format,
            Map<String, Object> filtersAudit) {
        String companyName = format == ExportFormat.PDF
                ? metadataRepository.findCompanyDisplayName()
                : null;
        ExportedReport exported = renderer.render(document, format, companyName);

        ReportExportType type = document.type();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type.auditType());
        payload.put("format", format.extension());
        payload.put("filters", filtersAudit);
        payload.put("rows", document.dataRowCount());

        auditService.record(new AuditEvent(
                ACTION_DATA_EXPORTED,
                ENTITY_REPORT,
                type.entityId(),
                null,
                payload,
                null,
                null));

        return exported;
    }

    private static Map<String, Object> rangeFiltersAudit(ReportFilter filter) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (filter.from() != null) {
            map.put("from", filter.from().toString());
        }
        if (filter.to() != null) {
            map.put("to", filter.to().toString());
        }
        map.put("storeIds", filter.storeIds());
        return map;
    }
}
