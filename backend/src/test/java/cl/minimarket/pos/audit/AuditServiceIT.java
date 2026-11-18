package cl.minimarket.pos.audit;

import cl.minimarket.pos.audit.application.AuditQueryService;
import cl.minimarket.pos.audit.application.AuditVerificationResult;
import cl.minimarket.pos.shared.context.StoreContext;
import cl.minimarket.pos.shared.web.TraceIdFilter;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de integración y concurrencia para {@link AuditService} y la verificación de cadena.
 */
class AuditServiceIT extends AbstractIntegrationIT {

    @Autowired
    AuditService auditService;

    @Autowired
    AuditQueryService queryService;

    @Autowired
    TransactionTemplate txTemplate;

    @Test
    void recordRequiresActiveTransaction() {
        AuditEvent event = new AuditEvent("IT_NO_TX", "audit", "0", null, null, null, null);
        assertThatThrownBy(() -> auditService.record(event))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rollbackLeavesNoRowInAuditLog() {
        String marker = "rb-" + UUID.randomUUID().toString().substring(0, 20);
        txTemplate.executeWithoutResult(status -> {
            auditService.record(new AuditEvent("IT_ROLLBACK", "it_test", marker, null, null, "test", null));
            status.setRollbackOnly();
        });

        Integer count = appJdbc().sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id")
                .param("id", marker)
                .query(Integer.class)
                .single();
        assertThat(count).isZero();
    }

    @Test
    void chainVerifiesValidAndDetectsTamperedRow() {
        String prefix = "chain-" + UUID.randomUUID().toString().substring(0, 12);
        List<Long> ids = new ArrayList<>();

        authenticateAsAdmin();
        MDC.put(TraceIdFilter.MDC_KEY, "trace-" + prefix);
        try (var ignored = StoreContext.bind(new StoreContext(1L, 2L))) {
            for (int i = 1; i <= 3; i++) {
                String entityId = prefix + "-" + i;
                int index = i;
                txTemplate.executeWithoutResult(status -> auditService.record(new AuditEvent(
                        "PRODUCT_PRICE_CHANGED",
                        "product",
                        entityId,
                        Map.of("price", 1000 * index, "currency", "CLP"),
                        Map.of("currency", "CLP", "price", 1200 * index),
                        "Ajuste " + index,
                        null
                )));
                Long id = appJdbc().sql("SELECT id FROM audit_log WHERE entity_id = :entityId ORDER BY id DESC LIMIT 1")
                        .param("entityId", entityId)
                        .query(Long.class)
                        .single();
                ids.add(id);
            }
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove(TraceIdFilter.MDC_KEY);
        }

        long firstId = ids.getFirst();
        long middleId = ids.get(1);
        long lastId = ids.getLast();

        AuditVerificationResult validResult = queryService.verify(firstId, lastId);
        assertThat(validResult.valid()).isTrue();
        assertThat(validResult.checked()).isGreaterThanOrEqualTo(3L);
        assertThat(validResult.firstBrokenId()).isNull();

        String originalReason = ownerJdbc().sql("SELECT reason FROM audit_log WHERE id = :id")
                .param("id", middleId)
                .query(String.class)
                .single();

        // Alteramos el registro intermedio deshabilitando el trigger como pos_owner y restauramos al final.
        ownerJdbc().sql("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_immutable").update();
        try {
            ownerJdbc().sql("UPDATE audit_log SET reason = 'manipulado' WHERE id = :id")
                    .param("id", middleId)
                    .update();

            AuditVerificationResult brokenResult = queryService.verify(firstId, lastId);
            assertThat(brokenResult.valid()).isFalse();
            assertThat(brokenResult.firstBrokenId()).isEqualTo(middleId);
        } finally {
            try {
                ownerJdbc().sql("UPDATE audit_log SET reason = :reason WHERE id = :id")
                        .param("reason", originalReason)
                        .param("id", middleId)
                        .update();
            } finally {
                ownerJdbc().sql("ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_immutable").update();
            }
        }
    }

    @Test
    void ipv6RemoteAddrIsCanonicalizedBeforeHashingAndVerifiesValid() {
        String marker = "ipv6-" + UUID.randomUUID().toString().substring(0, 12);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            txTemplate.executeWithoutResult(status -> auditService.record(new AuditEvent(
                    "IT_IPV6", "audit", marker, null, null, "ipv6 loopback", null)));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        Long id = appJdbc().sql("SELECT id FROM audit_log WHERE entity_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", marker)
                .query(Long.class)
                .single();
        String storedIp = appJdbc().sql("SELECT host(ip) FROM audit_log WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(storedIp).isEqualTo("::1");

        AuditVerificationResult result = queryService.verify(id, id);
        assertThat(result.valid()).isTrue();
        assertThat(result.firstBrokenId()).isNull();
    }

    @Test
    void bigDecimalWithExponentIsCanonicalizedBeforeHashingAndVerifiesValid() {
        String marker = "exp-" + UUID.randomUUID().toString().substring(0, 12);
        txTemplate.executeWithoutResult(status -> auditService.record(new AuditEvent(
                "IT_BIGDEC",
                "product",
                marker,
                Map.of("amount", new BigDecimal("1.0E10")),
                Map.of("amount", new BigDecimal("1.5E-4"), "count", new BigDecimal("2E+5")),
                "exponente",
                null)));

        Long id = appJdbc().sql("SELECT id FROM audit_log WHERE entity_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", marker)
                .query(Long.class)
                .single();

        AuditVerificationResult result = queryService.verify(id, id);
        assertThat(result.valid()).isTrue();
        assertThat(result.firstBrokenId()).isNull();
    }

    @Test
    void concurrentInsertsProduceValidLinearChain() throws Exception {
        String prefix = "conc-" + UUID.randomUUID().toString().substring(0, 12);
        int threads = 8;
        int eventsPerThread = 4;
        int totalEvents = threads * eventsPerThread;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int threadIdx = t;
                tasks.add(() -> {
                    List<Long> threadIds = new ArrayList<>();
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    for (int e = 0; e < eventsPerThread; e++) {
                        String entityId = prefix + "-" + threadIdx + "-" + e;
                        txTemplate.executeWithoutResult(status -> auditService.record(new AuditEvent(
                                "IT_CONCURRENT",
                                "concurrency",
                                entityId,
                                null,
                                Map.of("thread", threadIdx, "seq", entityId),
                                null,
                                null
                        )));
                        Long id = appJdbc().sql("SELECT id FROM audit_log WHERE entity_id = :entityId")
                                .param("entityId", entityId)
                                .query(Long.class)
                                .single();
                        threadIds.add(id);
                    }
                    return threadIds;
                });
            }

            List<Future<List<Long>>> futures = new ArrayList<>();
            for (Callable<List<Long>> task : tasks) {
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Long> allIds = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                allIds.addAll(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(allIds).hasSize(totalEvents);
            long minId = allIds.stream().mapToLong(Long::longValue).min().orElseThrow();
            long maxId = allIds.stream().mapToLong(Long::longValue).max().orElseThrow();

            AuditVerificationResult result = queryService.verify(minId, maxId);
            assertThat(result.valid()).isTrue();
            assertThat(result.firstBrokenId()).isNull();
            assertThat(result.checked()).isGreaterThanOrEqualTo(totalEvents);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void authenticateAsAdmin() {
        var auth = new UsernamePasswordAuthenticationToken(
                "1",
                "n/a",
                List.of(new SimpleGrantedAuthority("security:store:all-access"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
