package cl.minimarket.pos.shared.context.support;

import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.context.RequiresTerminal;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.shared.idempotency.Idempotent;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlador de pruebas de T11 (solo classpath de test, namespace {@code /api/v1/t11}) que ejercita
 * los filtros de contexto de local e idempotencia sin depender de los módulos de negocio.
 *
 * <p>Es {@code @TestComponent}: el escaneo de componentes de la aplicación lo excluye, así que no aparece en
 * otros contextos de prueba (ni en el OpenAPI exportado). Solo lo registra {@link ProbeConfiguration} con
 * {@code @Import}.
 */
@TestComponent
@Controller
@RequestMapping("/api/v1/t11")
@ResponseBody
public class ProbeController {

    /** Cuántas veces se ejecutó realmente un endpoint idempotente. */
    public static final AtomicInteger EXECUTIONS = new AtomicInteger();

    /** Se libera cuando {@code /idem-slow} ya está dentro del controlador. */
    public static volatile CountDownLatch entered = new CountDownLatch(1);

    /** {@code /idem-slow} espera a este latch antes de responder. */
    public static volatile CountDownLatch release = new CountDownLatch(1);

    /** Reinicia el estado compartido entre pruebas. */
    public static void reset() {
        EXECUTIONS.set(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    /** Endpoint por local: devuelve el contexto y el MDC vistos dentro del controlador. */
    @GetMapping("/store")
    public Map<String, Object> store() {
        return describeContext();
    }

    /** Endpoint [T]: exige terminal. */
    @GetMapping("/terminal")
    @RequiresTerminal
    public Map<String, Object> terminal() {
        return describeContext();
    }

    /** Endpoint global por anotación. */
    @GetMapping("/global")
    @GlobalEndpoint
    public Map<String, Object> global() {
        return describeContext();
    }

    /** Endpoint global solo por la lista de rutas configurada en la prueba. */
    @GetMapping("/by-path")
    public Map<String, Object> byPath() {
        return describeContext();
    }

    /** Endpoint por local e idempotente: el caso típico (p. ej. crear una venta). */
    @PostMapping("/idem")
    @Idempotent
    public ResponseEntity<Map<String, Object>> idem(@RequestBody Map<String, Object> body) {
        if ("fail".equals(body.get("name"))) {
            throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT, "Sin stock");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        int execution = EXECUTIONS.incrementAndGet();
        response.put("execution", execution);
        response.put("name", body.get("name"));
        return ResponseEntity.created(URI.create("/api/v1/t11/idem/" + execution)).body(response);
    }

    /** Idempotente sin cuerpo de respuesta. */
    @PostMapping("/idem-empty")
    @Idempotent
    @GlobalEndpoint
    public ResponseEntity<Void> idemEmpty() {
        EXECUTIONS.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    /** Idempotente y lento: permite probar la clave en curso. */
    @PostMapping("/idem-slow")
    @Idempotent
    @GlobalEndpoint
    public Map<String, Object> idemSlow(@RequestBody Map<String, Object> body) throws InterruptedException {
        int execution = EXECUTIONS.incrementAndGet();
        entered.countDown();
        if (!release.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("La prueba no liberó el endpoint lento");
        }
        return Map.of("execution", execution, "name", String.valueOf(body.get("name")));
    }

    /** Sin {@code @Idempotent}: no debe exigir el header. */
    @PostMapping("/plain")
    @GlobalEndpoint
    public Map<String, Object> plain() {
        return Map.of("ok", true);
    }

    private static Map<String, Object> describeContext() {
        Map<String, Object> response = new LinkedHashMap<>();
        StoreContext.current().ifPresentOrElse(context -> {
            response.put("storeId", context.storeId());
            response.put("terminalId", context.terminalId());
        }, () -> response.put("context", "none"));
        response.put("mdcStoreId", MDC.get(StoreContext.MDC_STORE_ID));
        response.put("mdcTerminalId", MDC.get(StoreContext.MDC_TERMINAL_ID));
        return response;
    }
}
