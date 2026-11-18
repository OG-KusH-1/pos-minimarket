package cl.minimarket.pos.sales.application.sale;

import cl.minimarket.pos.organization.StoreApi;
import cl.minimarket.pos.organization.StoreInfo;
import cl.minimarket.pos.sales.application.sale.SaleDetail.TaxSummary;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.CustomerInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.ReturnInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.TerminalInfo;
import cl.minimarket.pos.sales.application.sale.SaleJdbcRepository.UserInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ensambla un {@link SaleDetail} desde entidades persistidas o recién creadas, cargando sus
 * relaciones maestras (local, terminal, cajero, cliente, promociones, impuestos y devoluciones).
 */
@Component
class SaleDetailAssembler {

    private final SaleLineRepository lineRepository;
    private final SaleLineTaxRepository lineTaxRepository;
    private final SaleDiscountRepository discountRepository;
    private final SalePaymentRepository paymentRepository;
    private final SaleJdbcRepository jdbcRepository;
    private final StoreApi storeApi;

    SaleDetailAssembler(
            SaleLineRepository lineRepository,
            SaleLineTaxRepository lineTaxRepository,
            SaleDiscountRepository discountRepository,
            SalePaymentRepository paymentRepository,
            SaleJdbcRepository jdbcRepository,
            StoreApi storeApi
    ) {
        this.lineRepository = lineRepository;
        this.lineTaxRepository = lineTaxRepository;
        this.discountRepository = discountRepository;
        this.paymentRepository = paymentRepository;
        this.jdbcRepository = jdbcRepository;
        this.storeApi = storeApi;
    }

    SaleDetail load(Sale sale) {
        List<SaleLine> lines = lineRepository.findBySaleIdOrderByLineNumberAsc(sale.getId());
        List<Long> lineIds = lines.stream().map(SaleLine::getId).toList();
        List<SaleLineTax> lineTaxes = lineIds.isEmpty()
                ? List.of()
                : lineTaxRepository.findBySaleLineIdInOrderByTaxIdAsc(lineIds);
        List<SaleDiscount> discounts = discountRepository.findBySaleIdOrderByIdAsc(sale.getId());
        List<SalePayment> payments = paymentRepository.findBySaleIdOrderByIdAsc(sale.getId());
        return assemble(sale, lines, lineTaxes, discounts, payments);
    }

    SaleDetail assemble(
            Sale sale,
            List<SaleLine> lines,
            List<SaleLineTax> lineTaxes,
            List<SaleDiscount> discounts,
            List<SalePayment> payments
    ) {
        StoreInfo store = storeApi.get(sale.getStoreId());
        Map<Long, TerminalInfo> terminals = jdbcRepository.findTerminalsByIds(List.of(sale.getTerminalId()));
        Map<Long, UserInfo> users = jdbcRepository.findUsersByIds(List.of(sale.getCashierUserId()));
        CustomerInfo customer = sale.getCustomerId() != null
                ? jdbcRepository.findCustomerById(sale.getCustomerId()).orElse(null)
                : null;

        Set<Long> promoIds = new HashSet<>();
        for (SaleDiscount discount : discounts) {
            if (discount.getPromotionId() != null) {
                promoIds.add(discount.getPromotionId());
            }
        }
        Map<Long, String> promoNames = jdbcRepository.findPromotionNamesByIds(promoIds);

        Map<Long, List<SaleLineTax>> taxesByLineId = new HashMap<>();
        Map<Long, TaxAccumulator> taxTotals = new LinkedHashMap<>();
        for (SaleLineTax tax : lineTaxes) {
            taxesByLineId.computeIfAbsent(tax.getSaleLineId(), k -> new ArrayList<>()).add(tax);
            taxTotals.compute(tax.getTaxId(), (id, existing) -> {
                if (existing == null) {
                    return new TaxAccumulator(tax.getTaxCode(), tax.getRate(), tax.getBaseAmount(), tax.getTaxAmount());
                }
                return new TaxAccumulator(
                        existing.taxCode(),
                        existing.rate(),
                        existing.baseAmount().add(tax.getBaseAmount()),
                        existing.taxAmount().add(tax.getTaxAmount())
                );
            });
        }

        List<TaxSummary> taxBreakdown = taxTotals.values().stream()
                .map(acc -> new TaxSummary(acc.taxCode(), acc.rate(), acc.baseAmount(), acc.taxAmount()))
                .toList();

        List<ReturnInfo> returns = jdbcRepository.findReturnsBySaleId(sale.getId());

        return new SaleDetail(
                sale,
                store,
                terminals.get(sale.getTerminalId()),
                users.get(sale.getCashierUserId()),
                customer,
                lines,
                taxesByLineId,
                taxBreakdown,
                discounts,
                promoNames,
                payments,
                returns
        );
    }

    private record TaxAccumulator(
            String taxCode,
            BigDecimal rate,
            BigDecimal baseAmount,
            BigDecimal taxAmount
    ) {
    }
}
