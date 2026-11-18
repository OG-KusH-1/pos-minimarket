package cl.minimarket.pos.shared.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreContextTest {

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void currentIsEmptyOutsideAScope() {
        assertThat(StoreContext.current()).isEmpty();
        assertThatThrownBy(StoreContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindExposesContextAndMdcUntilClosed() {
        try (StoreContext.Scope ignored = StoreContext.bind(new StoreContext(7L, 3L))) {
            assertThat(StoreContext.require()).isEqualTo(new StoreContext(7L, 3L));
            assertThat(MDC.get(StoreContext.MDC_STORE_ID)).isEqualTo("7");
            assertThat(MDC.get(StoreContext.MDC_TERMINAL_ID)).isEqualTo("3");
        }
        assertThat(StoreContext.current()).isEmpty();
        assertThat(MDC.get(StoreContext.MDC_STORE_ID)).isNull();
        assertThat(MDC.get(StoreContext.MDC_TERMINAL_ID)).isNull();
    }

    @Test
    void nestedScopesRestoreThePreviousContext() {
        try (StoreContext.Scope outer = StoreContext.bind(new StoreContext(1L, 10L))) {
            try (StoreContext.Scope inner = StoreContext.bind(new StoreContext(2L, null))) {
                assertThat(StoreContext.require().storeId()).isEqualTo(2L);
                assertThat(MDC.get(StoreContext.MDC_TERMINAL_ID)).isNull();
            }
            assertThat(StoreContext.require()).isEqualTo(new StoreContext(1L, 10L));
            assertThat(MDC.get(StoreContext.MDC_TERMINAL_ID)).isEqualTo("10");
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        StoreContext.Scope scope = StoreContext.bind(new StoreContext(1L, null));
        scope.close();
        scope.close();
        assertThat(StoreContext.current()).isEmpty();
    }

    @Test
    void storeIdIsMandatory() {
        assertThatThrownBy(() -> new StoreContext(null, 1L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentUserIdReadsNumericNameOrUserIdentityPrincipal() {
        assertThat(CurrentUserId.of(new TestingAuthenticationToken("42", "x", "ROLE_USER"))).contains(42L);
        UserIdentity identity = () -> 9L;
        assertThat(CurrentUserId.of(new TestingAuthenticationToken(identity, "x", "ROLE_USER"))).contains(9L);
    }

    @Test
    void currentUserIdIsEmptyForMissingAnonymousOrNonNumeric() {
        assertThat(CurrentUserId.of(null)).isEmpty();
        assertThat(CurrentUserId.of(new TestingAuthenticationToken("admin", "x", "ROLE_USER"))).isEmpty();
        assertThat(CurrentUserId.of(new TestingAuthenticationToken("0", "x", "ROLE_USER"))).isEmpty();
        assertThat(CurrentUserId.of(new TestingAuthenticationToken("-3", "x", "ROLE_USER"))).isEmpty();
        TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken("5", "x", "ROLE_USER");
        unauthenticated.setAuthenticated(false);
        assertThat(CurrentUserId.of(unauthenticated)).isEmpty();
        assertThat(CurrentUserId.of(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isEmpty();
    }
}
