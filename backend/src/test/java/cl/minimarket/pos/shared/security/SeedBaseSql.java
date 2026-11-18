package cl.minimarket.pos.shared.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidad de pruebas: lee el script de la semilla base desde el classpath y extrae sentencias
 * {@code INSERT} por tabla.
 *
 * <p>Es un análisis textual deliberadamente simple (no un parser SQL): se apoya en el formato de
 * la semilla (una sentencia {@code INSERT INTO <tabla>} que termina en {@code ;} al final de
 * línea, sin {@code ;} dentro de los textos). Si alguien cambia ese formato, las pruebas fallan
 * de forma ruidosa en vez de pasar en falso, porque exigen encontrar filas.
 *
 * <p>Los comentarios {@code --} se eliminan antes de analizar para no contar ejemplos citados
 * en ellos.
 */
final class SeedBaseSql {

    /** Ruta del script en el classpath (Flyway lo lee desde {@code classpath:db/migration}). */
    static final String RESOURCE = "/db/migration/V2026120100151__seed_base.sql";

    private SeedBaseSql() {
    }

    /**
     * Lee el script completo sin comentarios de línea.
     *
     * @return contenido SQL sin líneas {@code --} completas
     * @throws IllegalStateException si el recurso no existe o no se puede leer
     */
    static String read() {
        try (InputStream in = SeedBaseSql.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró " + RESOURCE + " en el classpath");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return raw.replaceAll("(?m)^\\s*--.*$", "");
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + RESOURCE, e);
        }
    }

    /**
     * Devuelve el texto de cada sentencia {@code INSERT INTO <table>} del script.
     *
     * @param table nombre exacto de la tabla
     * @return una cadena por sentencia, en orden de aparición (vacía si no hay ninguna)
     */
    static List<String> insertsInto(String table) {
        Pattern p = Pattern.compile("(?ms)^INSERT INTO " + Pattern.quote(table) + "\\b.*?;\\s*$");
        Matcher m = p.matcher(read());
        List<String> statements = new ArrayList<>();
        while (m.find()) {
            statements.add(m.group());
        }
        return statements;
    }
}
