package cl.minimarket.pos.shared.context.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuración de pruebas web de T11: el {@link ProbeController}, un {@link ConfigurableStoreAccessValidator}
 * y una cadena de seguridad que deja pasar todo (security real es de T21), de modo que la
 * autenticación de cada request la decide la prueba con {@code SecurityMockMvcRequestPostProcessors.user(id)}.
 *
 * <p>Se importa explícitamente con {@code @Import}; al ser {@code @TestConfiguration} no la recoge el
 * escaneo de componentes de la aplicación.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ProbeController.class)
public class ProbeConfiguration {

    /** Validador configurable; las pruebas lo inyectan y lo reinician. */
    @Bean
    ConfigurableStoreAccessValidator storeAccessValidator() {
        return new ConfigurableStoreAccessValidator();
    }

    /** Sin restricciones y sin CSRF; la autenticación (si la hay) la inyecta la prueba. */
    @Bean
    @Order(0)
    SecurityFilterChain permitAllSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}
