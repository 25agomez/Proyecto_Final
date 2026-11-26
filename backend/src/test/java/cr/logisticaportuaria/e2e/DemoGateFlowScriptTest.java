package cr.logisticaportuaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cr.logisticaportuaria.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs {@code infra/scripts/demo_gate_flow.sh} against a real server.
 *
 * <p>The script is an acceptance criterion of P3: it simulates an ingress and
 * an egress with {@code curl} and prints the times computed by the digital
 * logbook. Running it from the build keeps it from rotting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = {"app.mqtt.enabled=false", "app.edge.open-ingestion=true"})
class DemoGateFlowScriptTest extends AbstractIntegrationTest {

    /** Maximum time the script may take (it sleeps between the stages). */
    private static final long TIMEOUT_SECONDS = 120;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("El script de demo registra un ingreso y una salida con curl")
    void scriptRunsTheWholeFlow() throws Exception {
        Path script = Path.of("..", "infra", "scripts", "demo_gate_flow.sh").toAbsolutePath().normalize();
        assumeThat(Files.exists(script)).as("existe %s", script).isTrue();

        ProcessBuilder builder = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true);
        builder.environment().put("BASE_URL", "http://localhost:" + port);
        builder.environment().remove("EDGE_API_KEY");

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        assertThat(finished).as("el script terminó dentro del tiempo límite").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output)
                .contains("Demo completada")
                .contains("INGRESO")
                .contains("SALIDA")
                .contains("espera en puerta")
                .contains("tiempo en patio")
                .contains("tiempo de proceso")
                .contains("comprobante:")
                .contains("bitacora CSV:");
    }
}
