package cl.minimarket.pos.shared.context;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.context.support.ProbeConfiguration;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StoreContextFilter} con el contexto completo de Spring (MockMvc, cadena de seguridad que
 * permite todo y {@code GlobalExceptionHandler} real): verifica los códigos de error y el contenido
 * del {@link StoreContext} dentro del controlador.
 */
@SpringBootTest(properties = {"pos.store-context.global-paths=/api/v1/t11/by-path",
        "pos.store-context.allow-anonymous=true", "pos.security.chain-enabled=false"})
@AutoConfigureMockMvc
@Import(ProbeConfiguration.class)
class StoreContextFilterIT extends AbstractIntegrationIT {

    private static final String STORE = "/api/v1/t11/store";

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    @BeforeEach
    void resetValidator() {
        validator.reset();
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String userId) {
        return request.with(user(userId));
    }

    @Test
    void validHeadersBindContextAndValidateAgainstPort() throws Exception {
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(7))
                .andExpect(jsonPath("$.terminalId").doesNotExist())
                .andExpect(jsonPath("$.mdcStoreId").value("7"));

        assertThat(validator.calls())
                .containsExactly(new ConfigurableStoreAccessValidator.Call(42L, 7L, null));
    }

    @Test
    void optionalTerminalIsValidatedAndBound() throws Exception {
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "7").header("X-Terminal-Id", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value(3))
                .andExpect(jsonPath("$.mdcTerminalId").value("3"));

        assertThat(validator.calls())
                .containsExactly(new ConfigurableStoreAccessValidator.Call(42L, 7L, 3L));
    }

    @Test
    void missingStoreHeaderIsValidationError() throws Exception {
        mvc.perform(asUser(get(STORE), "42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("X-Store-Id"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        assertThat(validator.calls()).isEmpty();
    }

    @Test
    void invalidStoreHeaderFormatsAreValidationErrors() throws Exception {
        for (String invalid : new String[] {"abc", "0", "-5", "1.5", "99999999999999999999", " "}) {
            mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("X-Store-Id"));
        }
        assertThat(validator.calls()).isEmpty();
    }

    @Test
    void repeatedStoreHeaderIsRejected() throws Exception {
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATED"));
    }

    @Test
    void invalidTerminalFormatIsValidationError() throws Exception {
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "7").header("X-Terminal-Id", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("X-Terminal-Id"));
    }

    @Test
    void accessDeniedBecomesForbiddenStoreAccessDenied() throws Exception {
        validator.denyStore(9L);

        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ACCESS_DENIED"));
    }

    @Test
    void invalidTerminalFromPortIsPropagated() throws Exception {
        validator.invalidateTerminal(5L);

        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "7").header("X-Terminal-Id", "5"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TERMINAL_INVALID"));
    }

    @Test
    void requiresTerminalEndpointRejectsMissingTerminal() throws Exception {
        mvc.perform(asUser(get("/api/v1/t11/terminal"), "42").header("X-Store-Id", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("X-Terminal-Id"));

        mvc.perform(asUser(get("/api/v1/t11/terminal"), "42").header("X-Store-Id", "7").header("X-Terminal-Id", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value(3));
    }

    @Test
    void annotatedGlobalEndpointNeedsNoHeaderAndHasNoContext() throws Exception {
        mvc.perform(asUser(get("/api/v1/t11/global"), "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value("none"));

        // Un header enviado a un endpoint global se ignora (no se valida).
        mvc.perform(asUser(get("/api/v1/t11/global"), "42").header("X-Store-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value("none"));
        assertThat(validator.calls()).isEmpty();
    }

    @Test
    void configuredGlobalPathNeedsNoHeader() throws Exception {
        mvc.perform(asUser(get("/api/v1/t11/by-path"), "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value("none"));
    }

    @Test
    void unauthenticatedRequestValidatesFormatButSkipsPort() throws Exception {
        mvc.perform(get(STORE).header("X-Store-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(7));
        assertThat(validator.calls()).isEmpty();

        mvc.perform(get(STORE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRouteIsNotFoundNotValidationError() throws Exception {
        mvc.perform(asUser(get("/api/v1/t11/no-existe"), "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    void contextAndMdcAreClearedAfterTheRequest() throws Exception {
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "7").header("X-Terminal-Id", "3"))
                .andExpect(status().isOk());

        assertThat(StoreContext.current()).isEmpty();
        assertThat(MDC.get(StoreContext.MDC_STORE_ID)).isNull();
        assertThat(MDC.get(StoreContext.MDC_TERMINAL_ID)).isNull();
        assertThat(MDC.get(StoreContextFilter.MDC_USER_ID)).isNull();
    }

    @Test
    void contextIsClearedEvenWhenTheRequestFails() throws Exception {
        validator.denyStore(9L);
        mvc.perform(asUser(get(STORE), "42").header("X-Store-Id", "9")).andExpect(status().isForbidden());

        assertThat(StoreContext.current()).isEmpty();
    }
}
