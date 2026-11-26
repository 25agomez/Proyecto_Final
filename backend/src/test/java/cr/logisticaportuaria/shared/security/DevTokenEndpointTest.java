package cr.logisticaportuaria.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the token endpoint of the development profile.
 *
 * <p>It also closes the loop of the security configuration: the token issued
 * by {@link DevTokenController} must be accepted by the JWT decoder of the
 * resource server and its roles must reach {@code @PreAuthorize}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = {"app.mqtt.enabled=false", "app.edge.open-ingestion=true"})
class DevTokenEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("El token de desarrollo se emite y abre las rutas autenticadas")
    void issuesTokenAcceptedByTheApi() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                        .param("username", "auditor")
                        .param("password", "auditor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode token = objectMapper.readTree(body);
        assertThat(token.get("expires_in").asLong()).isPositive();

        mockMvc.perform(get("/api/v1/visits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get("access_token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Los roles del token se aplican en @PreAuthorize")
    void rolesOfTheTokenAreEnforced() throws Exception {
        String code = "ROLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String carrier = """
                {"code":"%s","name":"Transportes %s","taxId":"3-101-000000"}
                """.formatted(code, code);

        mockMvc.perform(post("/api/v1/carriers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("auditor"))
                        .contentType(APPLICATION_JSON)
                        .content(carrier))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/carriers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("supervisor"))
                        .contentType(APPLICATION_JSON)
                        .content(carrier))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("Un password incorrecto responde 401 con ProblemDetail")
    void rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .param("username", "auditor")
                        .param("password", "incorrecto"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Un usuario inexistente responde 401 con ProblemDetail")
    void rejectsUnknownUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .param("username", "fantasma")
                        .param("password", "fantasma"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    private String token(String username) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/token")
                        .param("username", username)
                        .param("password", username))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("access_token").asText();
    }
}
