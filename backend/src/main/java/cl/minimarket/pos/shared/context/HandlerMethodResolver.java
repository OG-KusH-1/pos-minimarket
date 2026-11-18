package cl.minimarket.pos.shared.context;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * Resuelve, desde un filtro servlet, el método de controlador que atenderá la request.
 *
 * <p>Los filtros de shared ({@link StoreContextFilter} y el de idempotencia) necesitan leer
 * anotaciones del controlador ({@link GlobalEndpoint}, {@link RequiresTerminal},
 * {@code @Idempotent}) antes de que el {@code DispatcherServlet} actúe. Se pregunta al
 * {@link RequestMappingHandlerMapping} igual que hace Spring Security con sus
 * {@code MvcRequestMatcher}: es de solo lectura y deja en la request únicamente atributos que el
 * dispatcher vuelve a escribir.
 *
 * <p>El resultado se cachea en un atributo de la request para que varios filtros no repitan la
 * búsqueda.
 *
 * <p>Supuesto: solo importan los endpoints anotados con {@code @RequestMapping} (los de recursos
 * estáticos o actuator devuelven vacío; para esos se usan listas de rutas configurables).
 */
@Component
public class HandlerMethodResolver {

    private static final String CACHE_ATTRIBUTE = HandlerMethodResolver.class.getName() + ".RESULT";

    /** Marca de "se buscó y no hay método de controlador" para distinguirlo de "no se ha buscado". */
    private static final Object NONE = new Object();

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

    /**
     * @param handlerMapping mapeo de controladores de la aplicación (no el de actuator); se resuelve de forma perezosa para no forzar
     *        su inicialización durante el registro de filtros
     */
    public HandlerMethodResolver(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /**
     * Busca el método de controlador de la request.
     *
     * @param request request en curso
     * @return el {@link HandlerMethod}, o vacío si ninguno coincide (404, 405, recursos estáticos…)
     */
    public Optional<HandlerMethod> resolve(HttpServletRequest request) {
        Object cached = request.getAttribute(CACHE_ATTRIBUTE);
        if (cached != null) {
            return cached == NONE ? Optional.empty() : Optional.of((HandlerMethod) cached);
        }
        Optional<HandlerMethod> found = lookup(request);
        request.setAttribute(CACHE_ATTRIBUTE, found.isPresent() ? found.get() : NONE);
        return found;
    }

    /**
     * Indica si el método o su clase llevan la anotación (directa o como meta-anotación).
     *
     * @param handler método de controlador
     * @param annotation tipo de anotación buscada
     * @return {@code true} si está presente en el método o en el controlador
     */
    public static boolean hasAnnotation(HandlerMethod handler, Class<? extends Annotation> annotation) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), annotation)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), annotation);
    }

    private Optional<HandlerMethod> lookup(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
        if (mapping == null) {
            return Optional.empty();
        }
        boolean alreadyParsed = ServletRequestPathUtils.hasParsedRequestPath(request);
        if (!alreadyParsed) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        try {
            HandlerExecutionChain chain = mapping.getHandler(request);
            if (chain != null && chain.getHandler() instanceof HandlerMethod method) {
                return Optional.of(method);
            }
            return Optional.empty();
        } catch (Exception e) {
            // 405, 415 y similares: los reportará el DispatcherServlet con su manejador normal.
            return Optional.empty();
        } finally {
            if (!alreadyParsed) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }
}
