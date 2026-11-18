package cl.minimarket.pos.files.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Propiedades del módulo de archivos (prefijo {@code pos.files}).
 *
 * @param root directorio raíz en el sistema de archivos donde se guardan los binarios (ADR-09)
 */
@ConfigurationProperties(prefix = "pos.files")
public record FilesProperties(
        @DefaultValue("./data/files") Path root
) {
}
