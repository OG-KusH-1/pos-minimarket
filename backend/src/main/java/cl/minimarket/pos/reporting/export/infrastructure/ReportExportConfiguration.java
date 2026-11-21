package cl.minimarket.pos.reporting.export.infrastructure;

import cl.minimarket.pos.shared.export.PdfRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de beans auxiliares para la exportación de reportes ({@code reporting/export}).
 */
@Configuration
public class ReportExportConfiguration {

    /**
     * Registra {@link PdfRenderer} si ningún otro módulo lo ha declarado ya en el contexto.
     */
    @Bean
    @ConditionalOnMissingBean(PdfRenderer.class)
    public PdfRenderer reportingPdfRenderer() {
        return new PdfRenderer();
    }
}
