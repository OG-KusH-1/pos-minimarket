package cl.minimarket.pos.shared.export;

/**
 * Neutraliza la inyección de fórmulas (CSV/Formula Injection, OWASP) en celdas de texto exportadas.
 *
 * <p>Excel, LibreOffice y Google Sheets interpretan como fórmula una celda de texto que comienza con
 * {@code =}, {@code +}, {@code -} o {@code @}. Si el texto proviene de un usuario (nombre de
 * producto, observación, etc.) un atacante podría ejecutar fórmulas al abrir el archivo. La
 * mitigación (H.3 T12 del plan) es anteponer un apóstrofo {@code '}, que Excel trata como prefijo de
 * texto.
 *
 * <p>Decisiones de diseño:
 * <ul>
 *   <li>Solo se sanean <b>textos</b>. Los números y las fechas se escriben con su tipo nativo, por lo
 *       que un {@code -5} numérico jamás se altera.</li>
 *   <li>Además de los cuatro caracteres del plan se neutralizan tabulación y retorno de carro
 *       iniciales (recomendación OWASP), que algunos programas ignoran antes de evaluar la fórmula.</li>
 *   <li>La clase es el único punto de la regla: {@link XlsxWriter} y {@link CsvWriter} la comparten,
 *       de modo que ampliar la lista de caracteres peligrosos se hace en un solo lugar.</li>
 * </ul>
 */
public final class FormulaSanitizer {

    private FormulaSanitizer() {
    }

    /**
     * Antepone {@code '} si el texto empieza con un carácter que dispara fórmulas.
     *
     * @param text texto original; puede ser {@code null}
     * @return el mismo texto si es seguro (o {@code null}); en caso contrario {@code '} + texto
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return isDangerousStart(text.charAt(0)) ? "'" + text : text;
    }

    private static boolean isDangerousStart(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
    }
}
