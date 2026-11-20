package cl.minimarket.pos.billing.application;

import cl.minimarket.pos.sales.SaleCompletedEvent;
import cl.minimarket.pos.sales.SaleReturnedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Listener asíncrono y transaccional de eventos de {@code sales} para emitir documentos
 * tributarios electrónicos ({@code PLAN.md} A.4, {@code HU-POS-12}).
 */
@Component
public class BillingEventListener {

    private final TaxDocumentService taxDocumentService;

    public BillingEventListener(TaxDocumentService taxDocumentService) {
        this.taxDocumentService = Objects.requireNonNull(taxDocumentService, "taxDocumentService");
    }

    /** Emite la boleta electrónica (39 o 41) tras el commit de una venta completada. */
    @ApplicationModuleListener
    public void onSaleCompleted(SaleCompletedEvent event) {
        taxDocumentService.issueForSale(event);
    }

    /** Emite la nota de crédito electrónica (61) tras el commit de una anulación o devolución. */
    @ApplicationModuleListener
    public void onSaleReturned(SaleReturnedEvent event) {
        taxDocumentService.issueForSaleReturn(event);
    }
}
