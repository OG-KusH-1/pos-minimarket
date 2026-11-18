package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CustomerInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.ReturnInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.TerminalInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.UserInfo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Vista completa de aplicación para una venta emitida, sus líneas, impuestos, descuentos,
 * pagos, devoluciones y referencias maestras.
 */
public record SaleDetail(
        Sale sale,
        StoreInfo store,
        TerminalInfo terminal,
        UserInfo cashier,
        CustomerInfo customer,
        List<SaleLine> lines,
        Map<Long, List<SaleLineTax>> taxesByLineId,
        List<TaxSummary> taxBreakdown,
        List<SaleDiscount> discounts,
        Map<Long, String> promotionNamesById,
        List<SalePayment> payments,
        List<ReturnInfo> returns
) {

    /**
     * Desglose agregado por impuesto sobre el documento.
     */
    public record TaxSummary(
            String taxCode,
            BigDecimal rate,
            BigDecimal baseAmount,
            BigDecimal taxAmount
    ) {
    }
}
