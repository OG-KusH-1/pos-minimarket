package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.security.config.SecurityProperties;
import cl.minimarket.pos.shared.context.GlobalEndpoint;
import cl.minimarket.pos.shared.context.StoreAccessValidator;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.support.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración de control de acceso HTTP:
 * <ul>
 *   <li>Sin token: 401 {@code UNAUTHENTICATED} en formato {@code ProblemDetail}.</li>
 *   <li>Token válido sin el permiso requerido: 403 {@code ACCESS_DENIED}.</li>
 *   <li>Endpoint {@code @GlobalEndpoint} con token válido: no exige {@code X-Store-Id}.</li>
 *   <li>Endpoint no global con token válido: exige {@code X-Store-Id}.</li>
 * </ul>
 */
@Import(SecurityAccessControlIT.ProbeConfig.class)
class SecurityAccessControlIT extends AbstractIntegrationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SecurityProperties securityProperties;

    @Test
    void unauthenticatedRequestReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"));
    }

    @Test
    void validTokenWithoutPermissionReturns403AccessDenied() throws Exception {
        String token = jwtTokenService.issueAccessToken(
                1L, "cajero", TokenScope.FULL, List.of(Permissions.CATALOG_PRODUCT_READ)).accessToken();

        mockMvc.perform(get("/api/v1/t19-probe/restricted")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void globalEndpointWithValidTokenDoesNotRequireStoreHeader() throws Exception {
        String token = jwtTokenService.issueAccessToken(
                1L, "admin", TokenScope.FULL, List.of(Permissions.SECURITY_USER_READ)).accessToken();

        mockMvc.perform(get("/api/v1/t19-probe/restricted")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void nonGlobalEndpointRequiresStoreHeaderEvenWithValidToken() throws Exception {
        String token = jwtTokenService.issueAccessToken(
                1L, "admin", TokenScope.FULL, List.of(Permissions.INVENTORY_STOCK_READ)).accessToken();

        mockMvc.perform(get("/api/v1/t19-probe/scoped")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/t19-probe/scoped")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void expiredTamperedWrongIssuerAndMalformedTokensReturn401Unauthenticated() throws Exception {
        Instant now = Instant.now();
        String validIssuer = securityProperties.jwt().issuer();

        String expiredToken = encodeToken(validIssuer, now.minusSeconds(1200), now.minusSeconds(60), "1", "FULL");
        String wrongIssuerToken = encodeToken("emisor-invalido", now, now.plusSeconds(300), "1", "FULL");
        String validToken = jwtTokenService.issueAccessToken(
                1L, "admin", TokenScope.FULL, List.of(Permissions.SECURITY_USER_READ)).accessToken();
        String tamperedToken = validToken.substring(0, validToken.length() - 4) + "AAAA";
        String badSubjectToken = encodeToken(validIssuer, now, now.plusSeconds(300), "no-numerico", "FULL");
        String badScopeToken = encodeToken(validIssuer, now, now.plusSeconds(300), "1", "INVALID_SCOPE");

        for (String token : List.of(expiredToken, wrongIssuerToken, tamperedToken, badSubjectToken, badScopeToken)) {
            mockMvc.perform(get("/api/v1/t19-probe/restricted")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    private String encodeToken(String issuer, Instant issuedAt, Instant expiresAt, String subject, String scope) {
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .subject(subject)
                        .claim("username", "admin")
                        .claim("scope", scope)
                        .claim("permissions", List.of(Permissions.SECURITY_USER_READ))
                        .build()
        )).getTokenValue();
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        StoreAccessValidator testStoreAccessValidator() {
            return (userId, storeId, terminalId) -> {
            };
        }

        @RestController
        @RequestMapping("/api/v1/t19-probe")
        static class ProbeController {

            @GetMapping("/restricted")
            @GlobalEndpoint
            @PreAuthorize("hasAuthority('" + Permissions.SECURITY_USER_READ + "')")
            Map<String, Boolean> restricted() {
                return Map.of("ok", true);
            }

            @GetMapping("/scoped")
            @PreAuthorize("hasAuthority('" + Permissions.INVENTORY_STOCK_READ + "')")
            Map<String, Boolean> scoped() {
                return Map.of("ok", true);
            }
        }
    }
}
