package cl.minimarket.pos.notification.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * Renderiza el cuerpo HTML y texto plano de los correos de la outbox según su plantilla lógica.
 */
@Component
public class EmailTemplateRenderer {

    /**
     * Resultado del renderizado de una plantilla de correo.
     *
     * @param htmlBody cuerpo HTML con escape seguro de variables
     * @param textBody cuerpo en texto plano alternativo
     */
    public record RenderedEmail(String htmlBody, String textBody) {
    }

    /**
     * Genera el contenido HTML y texto plano para la plantilla y payload indicados.
     */
    public RenderedEmail render(String template, String subject, Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        return switch (template) {
            case "purchase-order" -> renderPurchaseOrder(subject, safePayload);
            default -> renderGeneric(subject, safePayload);
        };
    }

    private RenderedEmail renderPurchaseOrder(String subject, Map<String, Object> payload) {
        String orderNumber = str(payload.get("orderNumber"));
        String storeName = str(payload.get("storeName"));
        String supplierName = str(payload.get("supplierName"));
        String expectedDate = str(payload.get("expectedDate"));
        String notes = str(payload.get("notes"));

        StringBuilder html = new StringBuilder();
        html.append("<html><body style=\"font-family: Arial, sans-serif; color: #222;\">")
                .append("<h2>").append(esc(subject)).append("</h2>")
                .append("<p>Estimado/a <strong>").append(esc(supplierName)).append("</strong>,</p>")
                .append("<p>Adjuntamos la orden de compra <strong>").append(esc(orderNumber))
                .append("</strong> emitida por <strong>").append(esc(storeName)).append("</strong>.</p>");
        if (!expectedDate.isBlank()) {
            html.append("<p><strong>Fecha esperada de entrega:</strong> ")
                    .append(esc(expectedDate)).append("</p>");
        }
        if (!notes.isBlank()) {
            html.append("<p><strong>Observaciones:</strong> ")
                    .append(esc(notes)).append("</p>");
        }
        html.append("<p>Atentamente,<br/>").append(esc(storeName)).append("</p>")
                .append("</body></html>");

        StringBuilder text = new StringBuilder();
        text.append(subject).append("\n\n")
                .append("Estimado/a ").append(supplierName).append(",\n")
                .append("Adjuntamos la orden de compra ").append(orderNumber)
                .append(" emitida por ").append(storeName).append(".\n");
        if (!expectedDate.isBlank()) {
            text.append("Fecha esperada de entrega: ").append(expectedDate).append("\n");
        }
        if (!notes.isBlank()) {
            text.append("Observaciones: ").append(notes).append("\n");
        }
        text.append("\nAtentamente,\n").append(storeName);

        return new RenderedEmail(html.toString(), text.toString());
    }

    private RenderedEmail renderGeneric(String subject, Map<String, Object> payload) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style=\"font-family: Arial, sans-serif; color: #222;\">")
                .append("<h2>").append(esc(subject)).append("</h2><ul>");
        StringBuilder text = new StringBuilder(subject).append("\n\n");
        payload.forEach((k, v) -> {
            String key = str(k);
            String val = str(v);
            html.append("<li><strong>").append(esc(key)).append(":</strong> ")
                    .append(esc(val)).append("</li>");
            text.append(key).append(": ").append(val).append("\n");
        });
        html.append("</ul></body></html>");
        return new RenderedEmail(html.toString(), text.toString());
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
