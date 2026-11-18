package cl.minimarket.pos.security.config;

import cl.minimarket.pos.security.auth.CurrentUser;
import cl.minimarket.pos.security.auth.StorePermissionEvaluator;
import cl.minimarket.pos.security.auth.TokenScope;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuración central de Spring Security para la API REST stateless con JWT HS256 (ADR-20).
 *
 * <p>Decisiones clave:
 * <ul>
 *   <li>Sin sesiones HTTP ni CSRF (autenticación por Bearer token en cabecera).</li>
 *   <li>Rutas públicas limitadas a {@code POST /api/v1/auth/login}, {@code POST /api/v1/auth/refresh},
 *       actuator {@code health}/{@code info} y OpenAPI/Swagger fuera del perfil {@code prod}.</li>
 *   <li>Errores 401 ({@code UNAUTHENTICATED}) y 403 ({@code ACCESS_DENIED}) delegados a
 *       {@link HandlerExceptionResolver} para emitir {@code ProblemDetail} RFC 9457 uniforme.</li>
 *   <li>El JWT se convierte a una autenticación cuyo principal es {@link CurrentUser} (implementa
 *       {@code UserIdentity}) para que {@code StoreContextFilter} reconozca al usuario.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
public class SecurityConfig {

    private static final int BCRYPT_COST = 12;
    private static final int MIN_HMAC_KEY_BYTES = 32;

    /**
     * Define la cadena de filtros de seguridad HTTP.
     *
     * @param http constructor de seguridad HTTP
     * @param env entorno activo para habilitar springdoc solo fuera de {@code prod}
     * @param corsSource fuente de configuración CORS
     * @param jwtConverter conversor de JWT a autenticación con {@link CurrentUser}
     * @param exceptionResolver resolvedor global para traducir 401/403 a {@code ProblemDetail}
     * @return cadena de filtros configurada
     */
    @Bean
    @ConditionalOnProperty(prefix = "pos.security", name = "chain-enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment env,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtConverter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver
    ) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, authException) ->
                exceptionResolver.resolveException(request, response, null, authException);
        AccessDeniedHandler deniedHandler = (request, response, accessDeniedException) ->
                exceptionResolver.resolveException(request, response, null, accessDeniedException);

        boolean nonProd = !env.acceptsProfiles(Profiles.of("prod"));

        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    if (nonProd) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    auth.anyRequest().authenticated();
                });

        return http.build();
    }

    /**
     * Codificador de contraseñas y PIN con BCrypt costo 12 (ADR-20).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    /**
     * Clave simétrica HMAC-SHA256 derivada de {@code pos.security.jwt.secret}.
     *
     * <p>Falla al arrancar si el secreto es nulo, blanco o menor a 256 bits (32 bytes).
     */
    @Bean
    public SecretKey jwtSecretKey(SecurityProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("La propiedad pos.security.jwt.secret es obligatoria");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException(
                    "pos.security.jwt.secret debe tener al menos 32 bytes (256 bits) para HS256");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * Decodificador JWT con Nimbus para firma HS256 y validación estricta de emisor ({@code iss}) y expiración.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    /**
     * Codificador JWT con Nimbus para emisión de tokens firmados con HS256.
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /**
     * Conversor de {@link Jwt} a {@link PosJwtAuthenticationToken} con {@link CurrentUser} como principal.
     *
     * <p>Si el token tiene {@code scope = POS}, restringe las authorities efectivas únicamente a los
     * módulos {@code sales:*}, {@code cash:*} y {@code customers:*} (ROLES_PERMISOS D.4).
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            long userId = parseUserId(jwt.getSubject());
            String username = jwt.getClaimAsString("username");
            TokenScope scope = parseScope(jwt.getClaimAsString("scope"));
            List<String> rawPermissions = jwt.getClaimAsStringList("permissions");
            Set<String> effectivePermissions = filterByScope(rawPermissions, scope);
            CurrentUser principal = new CurrentUser(
                    userId,
                    username != null ? username : jwt.getSubject(),
                    scope,
                    effectivePermissions
            );
            Collection<GrantedAuthority> authorities = effectivePermissions.stream()
                    .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                    .toList();
            return new PosJwtAuthenticationToken(jwt, principal, authorities);
        };
    }

    private static long parseUserId(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("El token JWT no contiene un identificador de usuario válido.");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException ex) {
            throw new InvalidBearerTokenException("El sujeto del token JWT no es un identificador numérico válido.", ex);
        }
    }

    private static TokenScope parseScope(String scopeClaim) {
        if (scopeClaim == null) {
            return TokenScope.FULL;
        }
        try {
            return TokenScope.valueOf(scopeClaim);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("El alcance (scope) del token JWT no es válido.", ex);
        }
    }

    /**
     * Registra {@link StorePermissionEvaluator} en el manejador de expresiones de seguridad de método.
     */
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler(StorePermissionEvaluator evaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(evaluator);
        return handler;
    }

    /**
     * Fuente de configuración CORS a partir de {@link CorsProperties}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
        CorsConfiguration config = new CorsConfiguration();
        if (!cors.allowedOrigins().isEmpty()) {
            config.setAllowedOrigins(cors.allowedOrigins());
        }
        config.setAllowedMethods(cors.allowedMethods());
        config.setAllowedHeaders(cors.allowedHeaders());
        config.setExposedHeaders(cors.exposedHeaders());
        config.setAllowCredentials(cors.allowCredentials());
        config.setMaxAge(cors.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static Set<String> filterByScope(List<String> permissions, TokenScope scope) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String code : permissions) {
            if (code == null || code.isBlank()) {
                continue;
            }
            if (scope == TokenScope.POS && !isPosScopePermission(code)) {
                continue;
            }
            result.add(code);
        }
        return Set.copyOf(result);
    }

    private static boolean isPosScopePermission(String code) {
        return code.startsWith("sales:") || code.startsWith("cash:") || code.startsWith("customers:");
    }

    /**
     * Token de autenticación JWT cuyo principal es {@link CurrentUser} (implementa {@code UserIdentity}).
     */
    public static final class PosJwtAuthenticationToken extends AbstractAuthenticationToken {

        private final Jwt jwt;
        private final CurrentUser principal;

        /**
         * @param jwt token JWT validado
         * @param principal usuario autenticado
         * @param authorities permisos efectivos del token
         */
        public PosJwtAuthenticationToken(Jwt jwt, CurrentUser principal,
                                           Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.jwt = jwt;
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return jwt;
        }

        @Override
        public CurrentUser getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return String.valueOf(principal.userId());
        }
    }
}
