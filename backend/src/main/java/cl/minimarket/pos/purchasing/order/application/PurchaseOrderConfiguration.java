package cl.minimarket.pos.purchasing.order.application;

import cl.minimarket.pos.shared.export.PdfRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de beans auxiliares del sub-módulo de órdenes de compra.
 */
@Configuration
public class PurchaseOrderConfiguration {

    /**
     * Registra {@link PdfRenderer} si ningún otro módulo lo ha registrado en el contexto.
     */
    @Bean
    @ConditionalOnMissingBean(PdfRenderer.class)
    public PdfRenderer purchaseOrderPdfRenderer() {
        return new PdfRenderer();
    }
}
