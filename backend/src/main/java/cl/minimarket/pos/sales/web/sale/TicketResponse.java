package cl.minimarket.pos.sales.web.sale;

import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.application.sale.Sale;
import cl.minimarket.pos.sales.application.sale.SaleDetail;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CompanyInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.TerminalInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.UserInfo;
import cl.minimarket.pos.sales.application.sale.TicketDetail;
import cl.minimarket.pos.sales.web.sale.SaleResponse.TaxBreakdown;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Datos estructurados para impresión térmica (80 mm) y reimpresión de ticket ({@code API.md} F.3
 * {@code TicketResponse}).
 */
public record TicketResponse(
        CompanyRef company,
        StoreRef store,
        TerminalRef terminal,
        String number,
        Instant soldAt,
        UserRef cashier,
        List<TicketLine> lines,
        TicketTotals totals,
        List<TaxBreakdown> taxBreakdown,
        List<SalePaymentResponse> payments,
        BigDecimal change,
        BigDecimal rounding,
        List<String> headerLines,
        List<String> footerLines,
        int reprintCount,
        String copyLabel,
        boolean showTaxBreakdown,
        int paperWidthMm
) {

    /** Datos tributarios y comerciales de la empresa para cabecera del ticket. */
    public record CompanyRef(String tradeName, String rut, String address, String businessActivity) {
    }

    /** Datos del local emisor. */
    public record StoreRef(Long id, String code, String name, String address, String commune, String city, String phone) {
    }

    /** Datos de la terminal emisora. */
    public record TerminalRef(Long id, String code, String name) {
    }

    /** Datos del cajero emisor. */
    public record UserRef(Long id, String username, String fullName) {
    }

    /** Línea compacta para el ticket impreso. */
    public record TicketLine(
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal total
    ) {
    }

    /** Totales de resumen del ticket. */
    public record TicketTotals(
            BigDecimal subtotalGross,
            BigDecimal discountTotal,
            BigDecimal totalGross,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal exemptTotal,
            BigDecimal totalPayable,
            BigDecimal paidTotal
    ) {
    }

    /** Convierte un {@link TicketDetail} de aplicación al DTO REST. */
    public static TicketResponse from(TicketDetail ticket) {
        SaleDetail detail = ticket.saleDetail();
        Sale sale = detail.sale();
        CompanyInfo co = ticket.company();
        StoreInfo st = detail.store();
        TerminalInfo term = detail.terminal();
        UserInfo cashier = detail.cashier();

        List<TicketLine> lines = detail.lines().stream()
                .map(line -> new TicketLine(
                        line.getSku(),
                        line.getProductName(),
                        line.getQuantity(),
                        line.getUnitPriceGross(),
                        line.getDiscountAmount(),
                        line.getLineTotalGross()
                ))
                .toList();

        List<TaxBreakdown> taxes = detail.taxBreakdown().stream()
                .map(t -> new TaxBreakdown(t.taxCode(), t.rate(), t.baseAmount(), t.taxAmount()))
                .toList();

        List<SalePaymentResponse> payments = detail.payments().stream()
                .map(SalePaymentResponse::from)
                .toList();

        return new TicketResponse(
                new CompanyRef(co.tradeName(), co.rut(), co.address(), co.businessActivity()),
                st != null
                        ? new StoreRef(st.id(), st.code(), st.name(), st.address(), st.commune(), st.city(), st.phone())
                        : new StoreRef(sale.getStoreId(), null, null, null, null, null, null),
                term != null
                        ? new TerminalRef(term.id(), term.code(), term.name())
                        : new TerminalRef(sale.getTerminalId(), null, null),
                sale.getNumber(),
                sale.getSoldAt(),
                cashier != null
                        ? new UserRef(cashier.id(), cashier.username(), cashier.fullName())
                        : new UserRef(sale.getCashierUserId(), null, null),
                lines,
                new TicketTotals(
                        sale.getSubtotalGross(),
                        sale.getDiscountTotal(),
                        sale.getTotalGross(),
                        sale.getNetTotal(),
                        sale.getTaxTotal(),
                        sale.getExemptTotal(),
                        sale.getTotalPayable(),
                        sale.getPaidTotal()
                ),
                taxes,
                payments,
                sale.getChangeTotal(),
                sale.getRoundingAdjustment(),
                ticket.headerLines(),
                ticket.footerLines(),
                ticket.reprintCount(),
                ticket.copyLabel(),
                ticket.showTaxBreakdown(),
                ticket.paperWidthMm()
        );
    }
}
