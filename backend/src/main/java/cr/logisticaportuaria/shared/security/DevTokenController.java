package cr.logisticaportuaria.shared.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token endpoint of the development profile: exchanges the credentials of the
 * in-memory users for a JWT (HS256).
 *
 * <p>It exists only to make the local development and the demo script usable
 * without Keycloak. In the other profiles the tokens come from the identity
 * provider configured in {@code app.security.jwt.*} (see
 * {@code backend/README.md}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Profile("dev")
public class DevTokenController {

    /** Issuer written in the tokens of the development profile. */
    private static final String ISSUER = "logisticaportuaria-cr";

    private final JwtEncoder encoder;
    private final InMemoryUserDetailsManager users;
    private final PasswordEncoder passwordEncoder;
    private final int expiresInSeconds;

    /**
     * Constructor.
     *
     * @param secret            shared secret of the development profile
     * @param users             in-memory users of the development profile
     * @param passwordEncoder   encoder of the passwords
     * @param expiresInSeconds  lifetime of the token
     */
    public DevTokenController(
            @Value("${app.security.jwt.secret:}") String secret,
            InMemoryUserDetailsManager users,
            PasswordEncoder passwordEncoder,
            @Value("${app.security.jwt.expires-in-seconds:3600}") int expiresInSeconds) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.expiresInSeconds = expiresInSeconds;
    }

    /**
     * Issues a token for the credentials of an in-memory user.
     *
     * <p>Credentials are received as form fields or query parameters so the
     * endpoint does not depend on HTTP Basic being enabled for the API.
     *
     * @param username user name (operador, supervisor, admin or auditor)
     * @param password password of the user
     * @return the token, its type and its lifetime
     */
    @PostMapping(path = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> token(
            @RequestParam(defaultValue = "operador") String username,
            @RequestParam(defaultValue = "operador") String password) {
        UserDetails user;
        try {
            user = users.loadUserByUsername(username);
        } catch (UsernameNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas.");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas.");
        }

        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expiresInSeconds))
                .subject(user.getUsername())
                .claim("scope", "logisticaportuaria")
                .claim("roles", user.getAuthorities().stream()
                        .map(Object::toString)
                        .toList())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", expiresInSeconds);
    }
}
