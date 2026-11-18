package cl.minimarket.pos.shared.context;

import cl.minimarket.pos.shared.context.support.ConfigurableStoreAccessValidator;
import cl.minimarket.pos.shared.context.support.ProbeConfiguration;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StoreContextFilter} con la configuración por defecto ({@code allow-anonymous=false}):
 * falla cerrado cuando no hay un usuario identificable.
 */
@Import(ProbeConfiguration.class)
@TestPropertySource(properties = "pos.security.chain-enabled=false")
class StoreContextFilterStrictIT extends AbstractIntegrationIT {

    private static final String STORE = "/api/v1/t11/store";

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigurableStoreAccessValidator validator;

    @BeforeEach
    void resetValidator() {
        validator.reset();
    }

    @Test
    void anonymousRequestToNonGlobalEndpointIsUnauthenticated() throws Exception {
        mvc.perform(get(STORE).header("X-Store-Id", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(validator.calls()).isEmpty();
    }

    @Test
    void authenticatedPrincipalWithoutExtractableIdIsUnauthenticated() throws Exception {
        var noId = new TestingAuthenticationToken("admin", "x", "ROLE_USER");

        mvc.perform(get(STORE).with(authentication(noId)).header("X-Store-Id", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(validator.calls()).isEmpty();
    }

    @Test
    void authenticatedPrincipalWithNumericIdIsValidated() throws Exception {
        var withId = new TestingAuthenticationToken("42", "x", "ROLE_USER");

        mvc.perform(get(STORE).with(authentication(withId)).header("X-Store-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(7));

        assertThat(validator.calls()).containsExactly(new ConfigurableStoreAccessValidator.Call(42L, 7L, null));
    }

    @Test
    void globalEndpointStillAcceptsAnonymousRequests() throws Exception {
        mvc.perform(get("/api/v1/t11/global")).andExpect(status().isOk());
    }
}
