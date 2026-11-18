package cl.minimarket.pos.files.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración del módulo de archivos.
 */
@Configuration
@EnableConfigurationProperties(FilesProperties.class)
public class FilesConfiguration {
}
