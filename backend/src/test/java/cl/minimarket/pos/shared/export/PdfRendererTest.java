package cl.minimarket.pos.shared.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfRendererTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-03-05T15:30:00Z"), ZoneId.of("America/Santiago"));

    private final PdfRenderer renderer = new PdfRenderer(FIXED);

    @Test
    void generaPdfConTextoConTildesYEnie() throws IOException {
        byte[] pdf = renderer.render("export-test", Map.of(
                "title", "Orden de compra Nº 15",
                "company", "Distribuidora Peñalolén Ltda.",
                "lines", List.of("Azúcar granulada 1 kg", "Piña en conserva", "Ñoquis frescos")));

        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        String text = extractText(pdf);
        assertThat(text)
                .contains("Orden de compra Nº 15", "Distribuidora Peñalolén Ltda.", "Azúcar granulada 1 kg",
                        "Piña en conserva", "Ñoquis frescos")
                .contains("Página 1 de 1")
                .contains("Generado: 05-03-2026 12:30");
    }

    @Test
    void escapaHtmlEnLosDatosDeUsuario() throws IOException {
        byte[] pdf = renderer.render("export-test", Map.of(
                "title", "Reporte", "lines", List.of("<b>negrita</b> & más")));

        assertThat(extractText(pdf)).contains("<b>negrita</b> & más");
    }

    @Test
    void rechazaNombresDePlantillaInvalidos() {
        assertThatThrownBy(() -> renderer.render("../application", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> renderer.render(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plantillaInexistenteLanzaPdfRenderException() {
        assertThatThrownBy(() -> renderer.render("no-existe", Map.of()))
                .isInstanceOf(PdfRenderException.class);
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
