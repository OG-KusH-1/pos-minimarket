package cl.minimarket.pos.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asigna un identificador de traza a cada solicitud (A.5 "Logging").
 *
 * <p>Toma el {@code X-Request-Id} entrante solo si cumple {@code [A-Za-z0-9-]{8,40}} (evita
 * inyección en logs y cabeceras); si falta o no cumple, genera un UUID. El valor se publica en el
 * MDC con la clave {@value #MDC_KEY} (lo leen logback y {@code GlobalExceptionHandler}), se
 * devuelve en la cabecera {@value #RESPONSE_HEADER} y se limpia del MDC al terminar, porque los
 * hilos del servidor se reutilizan.
 *
 * <p>Corre muy temprano ({@code HIGHEST_PRECEDENCE + 10}) para que también los filtros de
 * seguridad registren con el {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Clave del MDC. */
    public static final String MDC_KEY = "traceId";

    /** Cabecera de entrada opcional. */
    public static final String REQUEST_HEADER = "X-Request-Id";

    /** Cabecera de respuesta con el identificador efectivo. */
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{8,40}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_HEADER);
        String traceId = incoming != null && VALID_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
