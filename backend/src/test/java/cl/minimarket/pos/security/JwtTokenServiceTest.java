package cl.minimarket.pos.security;

import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.JwtTokenService;
import cl.minimarket.pos.security.auth.TokenScope;
import cl.minimarket.pos.security.config.SecurityConfig;
import cl.minimarket.pos.security.config.SecurityProperties;
import cl.minimarket.pos.shared.context.CurrentUserId;
import cl.minimarket.pos.shared.security.Permissions;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias de emisión, decodificación HS256 y conversión de alcance ({@code FULL}/{@code POS})
 * de tokens JWT.
 */
class JwtTokenServiceTest {

    private static final String TEST_SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final SecurityProperties properties = new SecurityProperties(
            new SecurityProperties.Jwt(TEST_SECRET, "pos-minimarket", Duration.ofMinutes(15)),
            new SecurityProperties.Password(10)
    );
    private final BusinessClock clock = BusinessClock.fixed(Instant.now(), ZoneId.of("America/Santiago"));

    @Test
    void issuesValidHs256TokenWithUserIdentityAndPermissions() {
        SecretKey key = securityConfig.jwtSecretKey(properties);
        JwtEncoder encoder = securityConfig.jwtEncoder(key);
        JwtDecoder decoder = securityConfig.jwtDecoder(key, properties);
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.jwtAuthenticationConverter();
        JwtTokenService service = new JwtTokenService(encoder, properties, clock);

        JwtTokenService.IssuedToken issued = service.issueAccessToken(
                42L,
                "cajero1",
                TokenScope.FULL,
                List.of(Permissions.SALES_POS_OPERATE, Permissions.CATALOG_PRODUCT_READ)
        );

        assertThat(issued.expiresIn()).isEqualTo(900L);
        Jwt decoded = decoder.decode(issued.accessToken());
        assertThat(decoded.getSubject()).isEqualTo("42");

        AbstractAuthenticationToken auth = converter.convert(decoded);
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isInstanceOf(CurrentUser.class);
        assertThat(CurrentUserId.of(auth)).contains(42L);
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(Permissions.SALES_POS_OPERATE, Permissions.CATALOG_PRODUCT_READ);
    }

    @Test
    void posScopeRestrictsAuthoritiesToSalesCashAndCustomers() {
        SecretKey key = securityConfig.jwtSecretKey(properties);
        JwtEncoder encoder = securityConfig.jwtEncoder(key);
        JwtDecoder decoder = securityConfig.jwtDecoder(key, properties);
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.jwtAuthenticationConverter();
        JwtTokenService service = new JwtTokenService(encoder, properties, clock);

        JwtTokenService.IssuedToken issued = service.issueAccessToken(
                7L,
                "pos_user",
                TokenScope.POS,
                List.of(
                        Permissions.SALES_POS_OPERATE,
                        Permissions.CASH_SESSION_OPEN,
                        Permissions.CUSTOMERS_CUSTOMER_READ,
                        Permissions.SECURITY_USER_READ,
                        Permissions.INVENTORY_STOCK_READ
                )
        );

        AbstractAuthenticationToken auth = converter.convert(decoder.decode(issued.accessToken()));
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        Permissions.SALES_POS_OPERATE,
                        Permissions.CASH_SESSION_OPEN,
                        Permissions.CUSTOMERS_CUSTOMER_READ
                );
    }

    @Test
    void rejectsShortJwtSecret() {
        SecurityProperties shortSecret = new SecurityProperties(
                new SecurityProperties.Jwt("too-short", "pos-minimarket", Duration.ofMinutes(15)),
                new SecurityProperties.Password(10)
        );
        assertThatThrownBy(() -> securityConfig.jwtSecretKey(shortSecret))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExpiredTamperedAndWrongIssuerTokens() {
        SecretKey key = securityConfig.jwtSecretKey(properties);
        JwtEncoder encoder = securityConfig.jwtEncoder(key);
        JwtDecoder decoder = securityConfig.jwtDecoder(key, properties);
        Instant now = Instant.now();

        String expiredToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("pos-minimarket")
                        .issuedAt(now.minusSeconds(1200))
                        .expiresAt(now.minusSeconds(60))
                        .subject("1")
                        .claim("scope", "FULL")
                        .build()
        )).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(expiredToken))
                .isInstanceOf(JwtValidationException.class);

        String wrongIssuerToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("otro-emisor")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .subject("1")
                        .claim("scope", "FULL")
                        .build()
        )).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(wrongIssuerToken))
                .isInstanceOf(JwtValidationException.class);

        String validToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("pos-minimarket")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .subject("1")
                        .claim("scope", "FULL")
                        .build()
        )).getTokenValue();
        String tampered = validToken.substring(0, validToken.length() - 4) + "AAAA";
        assertThatThrownBy(() -> decoder.decode(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMalformedSubjectAndScopeClaims() {
        SecretKey key = securityConfig.jwtSecretKey(properties);
        JwtEncoder encoder = securityConfig.jwtEncoder(key);
        JwtDecoder decoder = securityConfig.jwtDecoder(key, properties);
        Converter<Jwt, AbstractAuthenticationToken> converter = securityConfig.jwtAuthenticationConverter();
        Instant now = Instant.now();

        Jwt badSub = decoder.decode(encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("pos-minimarket")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .subject("not-a-number")
                        .claim("scope", "FULL")
                        .build()
        )).getTokenValue());
        assertThatThrownBy(() -> converter.convert(badSub))
                .isInstanceOf(InvalidBearerTokenException.class);

        Jwt badScope = decoder.decode(encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer("pos-minimarket")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .subject("1")
                        .claim("scope", "ADMIN")
                        .build()
        )).getTokenValue());
        assertThatThrownBy(() -> converter.convert(badScope))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
