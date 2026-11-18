package cl.minimarket.pos.cash.application;

import cl.minimarket.pos.shared.export.PdfRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de beans auxiliares del módulo {@code cash}.
 */
@Configuration
public class CashConfiguration {

    /**
     * Registra {@link PdfRenderer} si no existe otro bean en el contexto.
     */
    @Bean
    @ConditionalOnMissingBean(PdfRenderer.class)
    public PdfRenderer cashPdfRenderer() {
        return new PdfRenderer();
    }
}
