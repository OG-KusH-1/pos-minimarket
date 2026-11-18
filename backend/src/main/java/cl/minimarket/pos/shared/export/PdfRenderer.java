package cl.minimarket.pos.shared.export;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Genera PDF (órdenes de compra, cierre de caja, reportes) a partir de plantillas Thymeleaf
 * (ADR-07): Thymeleaf produce XHTML y OpenHTMLtoPDF lo convierte a PDF.
 *
 * <h2>Uso</h2>
 * <pre>{@code
 * PdfRenderer renderer = new PdfRenderer();
 * byte[] pdf = renderer.render("purchase-order", Map.of("company", "Minimarket", "order", order));
 * }</pre>
 * La plantilla {@code templates/pdf/purchase-order.html} del classpath se arma sobre el layout
 * {@code templates/pdf/base.html} (ver su documentación).
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 *   <li><b>{@link TemplateEngine} propio</b>, con un resolver de classpath limitado a
 *       {@code templates/pdf/*.html}: no usa ni modifica la configuración web de Thymeleaf de Spring
 *       Boot, de modo que esta clase es un objeto simple, instanciable sin contexto de Spring y
 *       testeable en aislamiento. Si se requiere como bean, basta declararlo en el módulo que lo use.</li>
 *   <li><b>Seguridad</b>: el nombre de plantilla se valida contra {@code [A-Za-z0-9_-]+} (sin rutas ni
 *       {@code ..}); las plantillas deben escribir los datos con {@code th:text} (escapa HTML) y nunca
 *       con {@code th:utext} para datos de usuario.</li>
 *   <li><b>Fuentes</b>: se usan las fuentes PDF estándar integradas (Helvetica/Times/Courier),
 *       codificación WinAnsi, que cubre tildes, ñ y el signo {@code €}. No cubre símbolos fuera de
 *       Latin-1/WinAnsi; si se necesitan, registrar una fuente TTF con {@code useFont} (punto de
 *       extensión en {@link #toPdf}). No se incrusta ninguna fuente para no añadir recursos ni licencias.</li>
 *   <li><b>UTF-8</b> en el resolver y en el HTML entregado al conversor.</li>
 *   <li><b>Fecha de generación</b>: la variable {@code generatedAt} (texto {@code dd-MM-yyyy HH:mm},
 *       hora de {@code America/Santiago}) se agrega al modelo si el llamador no la define; el reloj es
 *       inyectable para pruebas.</li>
 *   <li>Locale de las plantillas: {@code es-CL}.</li>
 * </ul>
 *
 * <p>Instancia inmutable y segura para uso concurrente (el motor de Thymeleaf lo es).
 */
public final class PdfRenderer {

    /** Carpeta del classpath que contiene las plantillas PDF. */
    static final String TEMPLATE_PREFIX = "templates/pdf/";

    private static final Pattern TEMPLATE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Locale LOCALE = Locale.of("es", "CL");
    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    private final TemplateEngine engine;
    private final Clock clock;

    /** Renderizador con el reloj de la zona de negocio {@code America/Santiago}. */
    public PdfRenderer() {
        this(Clock.system(ExportFormats.BUSINESS_ZONE));
    }

    /**
     * @param clock reloj usado para {@code generatedAt}; su zona define la hora mostrada
     */
    public PdfRenderer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        resolver.setCacheable(true);
        // SpringTemplateEngine (SpEL) en vez de TemplateEngine: Thymeleaf 3.1 sin OGNL no evalúa ${...};
        // no requiere ApplicationContext y es la misma sintaxis que usa Spring Boot.
        this.engine = new SpringTemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    /**
     * Renderiza la plantilla y devuelve el PDF completo en memoria.
     *
     * @param templateName nombre sin extensión de una plantilla de {@code templates/pdf/}
     * @param model        variables de la plantilla; puede ser vacío
     * @return bytes del PDF (empiezan con {@code %PDF})
     * @throws IllegalArgumentException si el nombre de plantilla es inválido
     * @throws PdfRenderException       si la plantilla no existe, está mal formada o falla la conversión
     */
    public byte[] render(String templateName, Map<String, Object> model) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
        render(templateName, model, buffer);
        return buffer.toByteArray();
    }

    /**
     * Igual que {@link #render(String, Map)} pero escribe en un flujo (p. ej. la respuesta HTTP).
     * El flujo no se cierra.
     *
     * @throws IllegalArgumentException si el nombre de plantilla es inválido
     * @throws PdfRenderException       si falla el procesamiento o la conversión
     */
    public void render(String templateName, Map<String, Object> model, OutputStream out) {
        Objects.requireNonNull(out, "out");
        toPdf(renderHtml(templateName, model), out);
    }

    /**
     * Fase 1: plantilla Thymeleaf a XHTML. Visible en el paquete para poder inspeccionar el HTML en pruebas.
     */
    String renderHtml(String templateName, Map<String, Object> model) {
        if (templateName == null || !TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new IllegalArgumentException("Nombre de plantilla inválido");
        }
        Map<String, Object> variables = new HashMap<>(model == null ? Map.of() : model);
        variables.putIfAbsent("generatedAt", GENERATED_AT.format(LocalDateTime.now(clock)));
        try {
            return engine.process(templateName, new Context(LOCALE, variables));
        } catch (RuntimeException e) {
            throw new PdfRenderException("No se pudo procesar la plantilla PDF '" + templateName + "'", e);
        }
    }

    /** Fase 2: XHTML a PDF. Punto de extensión para registrar fuentes o metadatos. */
    private static void toPdf(String html, OutputStream out) {
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException | RuntimeException e) {
            throw new PdfRenderException("No se pudo convertir el HTML a PDF", e);
        }
    }
}
