package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CompanyInfo;

import java.util.List;

/**
 * Vista completa de aplicación para emitir o reimprimir un ticket térmico de 80 mm.
 */
public record TicketDetail(
        SaleDetail saleDetail,
        CompanyInfo company,
        List<String> headerLines,
        List<String> footerLines,
        boolean showTaxBreakdown,
        int paperWidthMm,
        int reprintCount,
        String copyLabel
) {
}
