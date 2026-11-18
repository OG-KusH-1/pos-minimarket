package cl.minimarket.pos.shared.export;

/** Falla al procesar la plantilla Thymeleaf o al convertir el HTML resultante a PDF. */
public class PdfRenderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PdfRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
