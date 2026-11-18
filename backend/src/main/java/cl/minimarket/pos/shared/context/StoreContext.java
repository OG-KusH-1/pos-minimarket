package cl.minimarket.pos.shared.context;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;

/**
 * Local (y terminal) activos de la request en curso (ADR-12, RN-27).
 *
 * <p>El cliente los declara con los headers {@code X-Store-Id} y {@code X-Terminal-Id};
 * {@link StoreContextFilter} los valida contra el usuario autenticado a través del puerto
 * {@link StoreAccessValidator} y los deja aquí. Los casos de uso de otros módulos leen el local
 * con {@link #current()} o {@link #require()} en lugar de recibirlo como parámetro oculto.
 *
 * <p><b>Decisión de diseño: alcance de request con un {@link ThreadLocal} estático</b> (y no un
 * bean {@code @RequestScope}). Motivos: (1) se puede leer desde cualquier capa sin inyectar un
 * proxy ni depender de {@code RequestContextHolder}; (2) es trivial de fijar en pruebas
 * ({@link #bind(StoreContext)}) y falla de forma explícita fuera de una request, en vez de lanzar
 * {@code IllegalStateException: No thread-bound request}; (3) los jobs {@code @Scheduled} pueden
 * ejecutar casos de uso por local envolviéndolos en un {@link #bind(StoreContext)}.
 * Consecuencia: el contexto <b>no se propaga</b> a hilos nuevos (pools, {@code @Async}); quien
 * delegue trabajo a otro hilo debe pasar los ids explícitamente o volver a hacer {@code bind}.
 *
 * <p>El filtro siempre limpia el contexto al terminar (bloque {@code finally}), de modo que un
 * hilo reutilizado del pool del servidor nunca ve el local de la request anterior.
 *
 * <p>MDC: mientras el contexto está activo se publican las claves {@value #MDC_STORE_ID} y
 * {@value #MDC_TERMINAL_ID} (A.5 "Logging").
 *
 * <p>Los endpoints globales ({@link GlobalEndpoint}) no tienen contexto: {@link #current()}
 * devuelve vacío y {@link #require()} lanza {@link IllegalStateException}.
 *
 * @param storeId id del local activo; nunca nulo
 * @param terminalId id de la terminal activa; {@code null} si la request no declaró terminal
 */
public record StoreContext(Long storeId, Long terminalId) {

    /** Clave del MDC con el local activo. */
    public static final String MDC_STORE_ID = "storeId";

    /** Clave del MDC con la terminal activa. */
    public static final String MDC_TERMINAL_ID = "terminalId";

    private static final ThreadLocal<StoreContext> HOLDER = new ThreadLocal<>();

    /**
     * @throws NullPointerException si {@code storeId} es nulo
     */
    public StoreContext {
        Objects.requireNonNull(storeId, "storeId");
    }

    /**
     * Contexto activo del hilo actual.
     *
     * @return el contexto, o vacío si la request es global o no hay request
     */
    public static Optional<StoreContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * Contexto activo, exigido.
     *
     * @return el contexto del hilo actual
     * @throws IllegalStateException si no hay contexto (error de programación: caso de uso por
     *         local invocado desde un endpoint global o fuera de una request)
     */
    public static StoreContext require() {
        StoreContext context = HOLDER.get();
        if (context == null) {
            throw new IllegalStateException(
                    "No hay StoreContext activo: el endpoint es global o se ejecuta fuera de una request");
        }
        return context;
    }

    /**
     * Activa un contexto en el hilo actual hasta que se cierre el {@link Scope} devuelto. Restaura
     * el contexto anterior al cerrar, por lo que admite anidamiento.
     *
     * <p>Uso: {@code try (var scope = StoreContext.bind(ctx)) { ... }}.
     *
     * @param context contexto a activar
     * @return ámbito que debe cerrarse (siempre, en {@code finally} o try-with-resources)
     * @throws NullPointerException si {@code context} es nulo
     */
    public static Scope bind(StoreContext context) {
        Objects.requireNonNull(context, "context");
        Scope scope = new Scope(HOLDER.get(), MDC.get(MDC_STORE_ID), MDC.get(MDC_TERMINAL_ID));
        HOLDER.set(context);
        MDC.put(MDC_STORE_ID, String.valueOf(context.storeId()));
        if (context.terminalId() != null) {
            MDC.put(MDC_TERMINAL_ID, String.valueOf(context.terminalId()));
        } else {
            MDC.remove(MDC_TERMINAL_ID);
        }
        return scope;
    }

    /**
     * Ámbito de un {@link StoreContext} activo; al cerrarlo se restaura el estado previo del hilo
     * (contexto y MDC). Cerrarlo más de una vez es inofensivo.
     */
    public static final class Scope implements AutoCloseable {

        private final StoreContext previous;
        private final String previousStoreMdc;
        private final String previousTerminalMdc;
        private boolean closed;

        private Scope(StoreContext previous, String previousStoreMdc, String previousTerminalMdc) {
            this.previous = previous;
            this.previousStoreMdc = previousStoreMdc;
            this.previousTerminalMdc = previousTerminalMdc;
        }

        /** Restaura el contexto y el MDC anteriores. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
            restore(MDC_STORE_ID, previousStoreMdc);
            restore(MDC_TERMINAL_ID, previousTerminalMdc);
        }

        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
