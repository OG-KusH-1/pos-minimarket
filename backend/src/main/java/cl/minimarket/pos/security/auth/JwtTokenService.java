package cl.minimarket.pos.security.auth;

import cl.minimarket.pos.security.config.SecurityProperties;
import cl.minimarket.pos.shared.time.BusinessClock;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Servicio de emisión de JWT de acceso firmados con HS256 (ADR-20).
 *
 * <p>El claim {@code sub} contiene el id numérico del usuario ({@code users.id}), {@code scope}
 * indica {@code FULL} o {@code POS}, y {@code permissions} contiene los códigos de permiso D.1.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final BusinessClock clock;

    /**
     * @param jwtEncoder codificador Nimbus HS256
     * @param properties propiedades JWT ({@code issuer}, {@code accessTokenTtl})
     * @param clock reloj de negocio inyectable
     */
    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties, BusinessClock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Genera un access token JWT firmado con HS256 para el usuario indicado.
     *
     * @param userId id numérico del usuario en {@code users}
     * @param username nombre de usuario
     * @param scope alcance del token ({@link TokenScope#FULL} o {@link TokenScope#POS})
     * @param permissions códigos de permiso efectivos
     * @return token serializado y su duración en segundos
     */
    public IssuedToken issueAccessToken(Long userId, String username, TokenScope scope, List<String> permissions) {
        Instant now = clock.now();
        long ttlSeconds = properties.jwt().accessTokenTtl().toSeconds();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("scope", scope.name())
                .claim("permissions", permissions == null ? List.of() : permissions)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(tokenValue, ttlSeconds);
    }

    /**
     * Resultado de la emisión de un access token.
     *
     * @param accessToken token JWT compacto
     * @param expiresIn tiempo de expiración en segundos
     */
    public record IssuedToken(String accessToken, long expiresIn) {
    }
}
