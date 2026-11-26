package cr.logisticaportuaria.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security of the API: stateless JWT (resource server) with the roles
 * {@link AppRole}.
 *
 * <p>Two ways to validate the tokens are supported:
 * <ul>
 *   <li>a shared secret (HS256) configured in {@code app.security.jwt.secret}: used by the
 *       {@code dev} profile and by the tests;</li>
 *   <li>a JWKS endpoint ({@code app.security.jwt.jwk-set-uri}) or an issuer
 *       ({@code app.security.jwt.issuer-uri}): the way to connect Keycloak without changing
 *       the code (see {@code backend/README.md}).</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Filter chain of the API.
     *
     * @param http        the HTTP security builder
     * @param edgeFilter  authentication of the ingestion endpoint
     * @return the filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            EdgeApiKeyFilter edgeFilter,
            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/**")
                        .permitAll()
                        .requestMatchers("/api/v1/gate-events")
                        .permitAll()
                        // The WebSocket handshake is public; the STOMP CONNECT frame is
                        // authenticated by FleetStompAuthInterceptor with the same JWT.
                        .requestMatchers("/ws", "/ws/**")
                        .permitAll()
                        .requestMatchers("/api/v1/**")
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterBefore(edgeFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Translates the claims of the token into granted authorities.
     *
     * <p>The default converter only reads the {@code scope}/{@code scp} claim, so the roles
     * would never reach {@code @PreAuthorize}. This converter also reads the {@code roles}
     * claim emitted by {@link DevTokenController} and the {@code realm_access.roles} claim of
     * Keycloak; a role without the {@code ROLE_} prefix is prefixed automatically.
     *
     * @return the converter
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Collection<GrantedAuthority>> authorities = jwt -> {
            List<GrantedAuthority> granted = new ArrayList<>(scopes.convert(jwt));
            addRoles(granted, jwt.getClaimAsStringList("roles"));
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
                addRoles(granted, roles.stream().map(String::valueOf).toList());
            }
            return granted;
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private void addRoles(List<GrantedAuthority> authorities, Collection<String> roles) {
        if (roles == null) {
            return;
        }
        for (String role : roles) {
            String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            authorities.add(new SimpleGrantedAuthority(authority));
        }
    }

    /**
     * Decodes the JWT: shared secret, JWKS endpoint or issuer.
     *
     * @param secret    shared secret (may be empty)
     * @param jwkSetUri JWKS endpoint (may be empty)
     * @param issuerUri issuer of the tokens (may be empty)
     * @return the decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${app.security.jwt.secret:}") String secret,
            @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${app.security.jwt.issuer-uri:}") String issuerUri) {
        if (secret != null && !secret.isBlank()) {
            SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            return NimbusJwtDecoder.withSecretKey(key)
                    .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                    .build();
        }
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        if (issuerUri != null && !issuerUri.isBlank()) {
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        LOGGER.warn("No hay 'app.security.jwt.secret', 'app.security.jwt.jwk-set-uri' ni "
                + "'app.security.jwt.issuer-uri' configurados: se usa un secreto efímero y los tokens "
                + "solo valen dentro de esta ejecución.");
        byte[] ephemeral = new byte[32];
        new java.security.SecureRandom().nextBytes(ephemeral);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(ephemeral, "HmacSHA256"))
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
    }

    /**
     * Password encoder used by the in-memory users of the development profile.
     *
     * @return the encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * In-memory users of the development profile: one account per role.
     *
     * @param passwordEncoder encoder of the passwords
     * @return the user details manager
     */
    @Bean
    @Profile("dev")
    public InMemoryUserDetailsManager devUsers(PasswordEncoder passwordEncoder) {
        List<UserDetails> users = List.of(
                user("operador", "operador", AppRole.OPERADOR_PUERTA, passwordEncoder),
                user("supervisor", "supervisor", AppRole.SUPERVISOR, passwordEncoder),
                user("admin", "admin", AppRole.ADMIN, passwordEncoder),
                user("auditor", "auditor", AppRole.AUDITOR, passwordEncoder));
        return new InMemoryUserDetailsManager(users);
    }

    private UserDetails user(String username, String password, AppRole role, PasswordEncoder encoder) {
        return User.withUsername(username)
                .password(encoder.encode(password))
                .roles(role.name())
                .build();
    }
}
