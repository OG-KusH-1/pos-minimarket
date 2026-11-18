package cl.minimarket.pos.organization.infrastructure;

import cl.minimarket.pos.organization.StoreUsageGuard;
import cl.minimarket.pos.organization.TerminalUsageGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura implementaciones por defecto (no-op) para los puertos {@link StoreUsageGuard} y
 * {@link TerminalUsageGuard}. El módulo {@code cash} los reemplazará en T29 para impedir
 * desactivar locales o terminales con turnos de caja abiertos.
 */
@Configuration
public class OrganizationGuardsConfiguration {

    @Bean
    @ConditionalOnMissingBean(StoreUsageGuard.class)
    public StoreUsageGuard defaultStoreUsageGuard() {
        return storeId -> {
            // Implementación por defecto: permite desactivar hasta que cash registre su guard
        };
    }

    @Bean
    @ConditionalOnMissingBean(TerminalUsageGuard.class)
    public TerminalUsageGuard defaultTerminalUsageGuard() {
        return terminalId -> {
            // Implementación por defecto: permite desactivar hasta que cash registre su guard
        };
    }
}
