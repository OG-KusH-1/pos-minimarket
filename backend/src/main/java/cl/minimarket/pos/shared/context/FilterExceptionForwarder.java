package cl.minimarket.pos.shared.context;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Entrega a la cadena de manejo de errores de Spring MVC las excepciones lanzadas desde un filtro.
 *
 * <p>Un {@code @RestControllerAdvice} solo ve las excepciones del {@code DispatcherServlet}; las de
 * un filtro servlet escaparían como 500 genérico. Los filtros de shared capturan sus
 * {@code BusinessException} y las pasan por el {@link HandlerExceptionResolver} global, de modo
 * que {@code GlobalExceptionHandler} produce el mismo {@code ProblemDetail} RFC 9457 que en
 * cualquier controlador (sin duplicar el formato del error en cada filtro).
 */
@Component
public class FilterExceptionForwarder {

    private final ObjectProvider<HandlerExceptionResolver> resolver;

    /**
     * @param resolver resolvedor compuesto de MVC; se obtiene de forma perezosa
     */
    public FilterExceptionForwarder(
            @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> resolver) {
        this.resolver = resolver;
    }

    /**
     * Escribe en la respuesta el error correspondiente a la excepción.
     *
     * @param request request en curso
     * @param response respuesta a escribir (no debe estar comprometida)
     * @param exception excepción a traducir
     * @throws ServletException si ningún manejador sabe traducirla (se propaga como error del servidor)
     */
    public void forward(HttpServletRequest request, HttpServletResponse response, Exception exception)
            throws ServletException {
        HandlerExceptionResolver delegate = resolver.getIfAvailable();
        ModelAndView resolved = delegate == null ? null : delegate.resolveException(request, response, null, exception);
        if (resolved == null) {
            throw new ServletException(exception);
        }
    }
}
