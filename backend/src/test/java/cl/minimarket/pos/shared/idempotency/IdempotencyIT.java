package cl.minimarket.pos.shared.idempotency;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.context.support.ProbeConfiguration;
import cl.minimarket.pos.shared.context.support.ProbeController;
import cl.minimarket.pos.shared.error.BusinessException;
import cl.minimarket.pos.shared.error.ErrorCode;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import cl.minimarket.pos.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotencia de extremo a extremo (A.5, ADR-14): filtro + servicio + tabla real
 * {@code idempotency_keys} en PostgreSQL, a través de MockMvc.
 */
@Import(ProbeConfiguration.class)
@TestPropertySource(properties = "pos.security.chain-enabled=false")
class IdempotencyIT extends AbstractIntegrationIT {

    private static final String IDEM = "/api/v1/t11/idem";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    @Autowired
    IdempotencyService service;

    private String userA;
    private String userB;

    @BeforeEach
    void setUp() {
        ProbeController.reset();
        validator.reset();
        userA = String.valueOf(TestData.insertUser(jdbc));
        userB = String.valueOf(TestData.insertUser(jdbc));
    }

    private MockHttpServletRequestBuilder idem(String path, String userId, UUID key, String body) {
        MockHttpServletRequestBuilder request = post(path)
                .with(user(userId))
                .header("X-Store-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return key == null ? request : request.header("Idempotency-Key", key.toString());
    }

    @Test
    void sameKeyAndSameBodyReturnsTheStoredResponseWithoutReexecuting() throws Exception {
        UUID key = UUID.randomUUID();

        MvcResult first = mvc.perform(idem(IDEM, userA, key, "{\"name\":\"cola\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.execution").value(1))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(header().string("Location", "/api/v1/t11/idem/1"))
                .andReturn();
        MvcResult second = mvc.perform(idem(IDEM, userA, key, "{\"name\":\"cola\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "/api/v1/t11/idem/1"))
                .andReturn();

        // jsonb normaliza espacios y orden de claves: se compara el JSON, no el texto.
        JSONAssert.assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString(), true);
        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM idempotency_keys WHERE idem_key = :k").param("k", key)
                .query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void semanticallyEqualJsonWithDifferentFieldOrderIsTheSameRequest() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"a\",\"qty\":1}")).andExpect(status().isCreated());
        mvc.perform(idem(IDEM, userA, key, "{ \"qty\": 1, \"name\": \"a\" }"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"cola\"}")).andExpect(status().isCreated());

        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"otra\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
    }

    @Test
    void keyStillInProgressIsConflictInProgress() throws Exception {
        UUID key = UUID.randomUUID();
        String body = "{\"name\":\"lenta\"}";
        CompletableFuture<MvcResult> running = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(idem("/api/v1/t11/idem-slow", userA, key, body)).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(ProbeController.entered.await(15, TimeUnit.SECONDS)).isTrue();

        mvc.perform(idem("/api/v1/t11/idem-slow", userA, key, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
        assertThat(jdbc.sql("SELECT status FROM idempotency_keys WHERE idem_key = :k").param("k", key)
                .query(String.class).single()).isEqualTo("IN_PROGRESS");

        ProbeController.release.countDown();
        MvcResult finished = running.get(15, TimeUnit.SECONDS);
        assertThat(finished.getResponse().getStatus()).isEqualTo(200);

        // Terminada la primera, el reintento recibe su respuesta guardada.
        mvc.perform(idem("/api/v1/t11/idem-slow", userA, key, body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.execution").value(1));
        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
    }

    @Test
    void missingHeaderIsValidationError() throws Exception {
        mvc.perform(idem(IDEM, userA, null, "{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        assertThat(ProbeController.EXECUTIONS.get()).isZero();
    }

    @Test
    void malformedKeyIsValidationError() throws Exception {
        mvc.perform(idem(IDEM, userA, null, "{\"name\":\"x\"}").header("Idempotency-Key", "no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
    }

    @Test
    void failedRequestReleasesTheKeySoItCanBeRetried() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"fail\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idem_key = :k").param("k", key)
                .query(Long.class).single()).isZero();

        // Con la misma clave y otro cuerpo ya no hay conflicto: la clave quedó libre.
        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"ok\"}")).andExpect(status().isCreated());
    }

    @Test
    void emptyResponseIsStoredAndReplayed() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(idem("/api/v1/t11/idem-empty", userA, key, "{}")).andExpect(status().isNoContent());
        mvc.perform(idem("/api/v1/t11/idem-empty", userA, key, "{}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
    }

    @Test
    void sameKeyFromAnotherUserIsIndependent() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(idem(IDEM, userA, key, "{\"name\":\"cola\"}")).andExpect(status().isCreated());
        mvc.perform(idem(IDEM, userB, key, "{\"name\":\"cola\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(2);
    }

    @Test
    void unauthenticatedIdempotentRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/t11/idem-empty").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void endpointWithoutAnnotationDoesNotRequireTheHeader() throws Exception {
        mvc.perform(post("/api/v1/t11/plain").with(user(userA))).andExpect(status().isOk());
    }

    @Test
    void expiredKeyCanBeReused() {
        UUID key = UUID.randomUUID();
        long userId = Long.parseLong(userA);
        String endpoint = IdempotencyService.endpointOf("POST", "/x");
        insertKey(key, userId, endpoint, "a".repeat(64), "COMPLETED", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        // Distinto hash: sin expiración sería conflicto; vencida, se reutiliza.
        assertThat(service.begin(key, userId, endpoint, "b".repeat(64))).isEmpty();
    }

    @Test
    void abandonedInProgressKeyIsTakenOverAfterTheTimeout() {
        UUID key = UUID.randomUUID();
        long userId = Long.parseLong(userA);
        String endpoint = IdempotencyService.endpointOf("POST", "/y");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertKey(key, userId, endpoint, "a".repeat(64), "IN_PROGRESS", now.minusMinutes(30), now.plusHours(23));

        assertThat(service.begin(key, userId, endpoint, "a".repeat(64))).isEmpty();
    }

    @Test
    void recentInProgressKeyIsNotTakenOver() {
        UUID key = UUID.randomUUID();
        long userId = Long.parseLong(userA);
        String endpoint = IdempotencyService.endpointOf("POST", "/z");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertKey(key, userId, endpoint, "a".repeat(64), "IN_PROGRESS", now.minusSeconds(5), now.plusHours(24));

        assertThatThrownBy(() -> service.begin(key, userId, endpoint, "a".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
    }

    @Test
    void concurrentRequestsWithTheSameKeyExecuteOnlyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return mvc.perform(idem(IDEM, userA, key, "{\"name\":\"carrera\"}"))
                                .andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            // Una request ejecuta (201); las demás o ven la respuesta guardada (201) o la clave en curso (409).
            assertThat(statuses).allMatch(s -> s == 201 || s == 409);
            assertThat(statuses).contains(201);
            assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void insertKey(UUID key, long userId, String endpoint, String hash, String status,
                           OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        jdbc.sql("""
                INSERT INTO idempotency_keys (idem_key, user_id, endpoint, request_hash, status, created_at, expires_at)
                VALUES (:k, :u, :e, :h, :s, :c, :x)
                """)
                .param("k", key).param("u", userId).param("e", endpoint).param("h", hash)
                .param("s", status).param("c", createdAt).param("x", expiresAt)
                .update();
    }
}
