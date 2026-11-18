package cl.minimarket.pos.inventory.application.transfer;

import cl.minimarket.pos.inventory.TransferDifferenceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de componentes de transferencias en el módulo de inventario.
 *
 * <p>Registra una implementación no-op de {@link TransferDifferenceHandler} mientras el módulo
 * {@code shrinkage} (T63) no esté presente para registrar diferencias como mermas sistémicas.
 */
@Configuration(proxyBeanMethods = false)
public class TransferConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TransferConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(TransferDifferenceHandler.class)
    TransferDifferenceHandler noopTransferDifferenceHandler() {
        return new NoopTransferDifferenceHandler();
    }

    /** Implementación no-op por defecto mientras no exista un módulo {@code shrinkage} activo. */
    static class NoopTransferDifferenceHandler implements TransferDifferenceHandler {
        @Override
        public void handle(TransferDifference difference) {
            log.debug(
                    "Diferencia en recepción de transferencia {} línea {} (producto {}, faltante {}): sin manejador de mermas configurado",
                    difference.transferNumber(),
                    difference.transferLineId(),
                    difference.productId(),
                    difference.differenceQuantity());
        }
    }
}
