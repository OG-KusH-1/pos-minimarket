package cl.minimarket.pos.security.access;

import cl.minimarket.pos.shared.context.StoreAccessValidator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Configuración del bean {@link StoreAccessValidator} del módulo {@code security}.
 *
 * <p>Registra {@link StoreAccessValidatorImpl} mediante un
 * {@link BeanDefinitionRegistryPostProcessor} con {@link Ordered#LOWEST_PRECEDENCE} para que se
 * evalúe después de todas las {@code @Configuration} y {@code @TestConfiguration} del contexto,
 * respetando los beans sustitutos de prueba ({@code ConfigurableStoreAccessValidator}) declarados en
 * otros módulos sin generar colisiones de nombres ni de tipo.
 */
@Configuration(proxyBeanMethods = false)
public class StoreAccessConfig {

    /**
     * Post-procesador que registra {@link StoreAccessValidatorImpl} solo si no existe ningún otro
     * bean de tipo {@link StoreAccessValidator} en el contexto.
     */
    @Bean
    public static StoreAccessValidatorRegistrar storeAccessValidatorRegistrar() {
        return new StoreAccessValidatorRegistrar();
    }

    /**
     * Registra la implementación real de {@link StoreAccessValidator} cuando no hay sustituto de
     * pruebas en la fábrica de beans.
     */
    public static final class StoreAccessValidatorRegistrar
            implements BeanDefinitionRegistryPostProcessor, Ordered {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            if (registry instanceof ListableBeanFactory lbf) {
                String[] existing = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                        lbf, StoreAccessValidator.class, true, false);
                if (existing.length > 0) {
                    return;
                }
            }
            RootBeanDefinition definition = new RootBeanDefinition(StoreAccessValidatorImpl.class);
            definition.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            registry.registerBeanDefinition("securityStoreAccessValidator", definition);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
