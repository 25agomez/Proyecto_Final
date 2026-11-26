package cr.logisticaportuaria.optimizer.congestion.infrastructure.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.optimizer.congestion.application.CongestionProperties;
import cr.logisticaportuaria.optimizer.congestion.domain.ConfidenceLevel;
import cr.logisticaportuaria.optimizer.congestion.domain.CongestionPrediction;
import cr.logisticaportuaria.optimizer.congestion.domain.RiskLevel;
import cr.logisticaportuaria.shared.mqtt.MqttProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the congestion alert publisher.
 *
 * <p>The broker is never contacted on purpose: one case disables the alerts and the other
 * points to a closed port to prove that a missing broker never breaks the forecast.
 */
class CongestionAlertPublisherTest {

    private static final UUID GATE = UUID.fromString("33333333-3333-7333-8333-333333333331");
    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");

    private static CongestionPrediction prediction(RiskLevel risk) {
        return new CongestionPrediction(GATE, "G1", 30, 12.345, 8.0, 19.9, 7.0, risk, ConfidenceLevel.ALTA,
                "HIBRIDO", 21.5, 0.94,
                List.of("7 camiones esperando un carril", "21,5 llegadas/hora previstas"),
                List.of("Abrir un cuarto carril durante la punta"));
    }

    private static CongestionAlertPublisher publisher(boolean alerts, boolean mqtt, String url) {
        CongestionProperties properties = new CongestionProperties();
        properties.setAlertsEnabled(alerts);
        properties.setAlertTopic("yard/%s/alerts/congestion");
        MqttProperties mqttProperties = new MqttProperties();
        mqttProperties.setEnabled(mqtt);
        mqttProperties.setUrl(url);
        mqttProperties.setClientId("test");
        mqttProperties.setQos(1);
        return new CongestionAlertPublisher(new ObjectMapper(), properties, mqttProperties);
    }

    @Test
    @DisplayName("con las alertas apagadas no se abre ninguna conexión")
    void disabledAlertsAreNoOp() {
        CongestionAlertPublisher publisher = publisher(false, true, "tcp://127.0.0.1:1");
        assertThatCode(() -> publisher.publish(YARD, prediction(RiskLevel.ALTO))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("con el broker caído el pronóstico sigue funcionando")
    void unreachableBrokerDoesNotBreakTheForecast() {
        CongestionAlertPublisher publisher = publisher(true, true, "mqtt://127.0.0.1:1");
        assertThatCode(() -> publisher.publish(YARD, prediction(RiskLevel.ALTO))).doesNotThrowAnyException();
        assertThat(publisher.json(prediction(RiskLevel.ALTO))).contains("\"type\":\"CONGESTION\"");
    }

    @Test
    @DisplayName("el payload de la alerta cumple el contrato MQTT/STOMP")
    void payloadHasTheContractFields() throws Exception {
        CongestionAlertPublisher publisher = publisher(false, false, "tcp://127.0.0.1:1");
        JsonNode json = new ObjectMapper().readTree(publisher.json(prediction(RiskLevel.ALTO)));

        assertThat(json.get("type").asText()).isEqualTo("CONGESTION");
        assertThat(json.get("gateId").asText()).isEqualTo(GATE.toString());
        assertThat(json.get("gateCode").asText()).isEqualTo("G1");
        assertThat(json.get("horizonMin").asInt()).isEqualTo(30);
        assertThat(json.get("expectedWaitMin").asDouble()).isEqualTo(12.3);
        assertThat(json.get("p10").asDouble()).isEqualTo(8.0);
        assertThat(json.get("p90").asDouble()).isEqualTo(19.9);
        assertThat(json.get("queueLength").asDouble()).isEqualTo(7.0);
        assertThat(json.get("riskLevel").asText()).isEqualTo("ALTO");
        assertThat(json.get("confidence").asText()).isEqualTo("ALTA");
        assertThat(json.get("drivers")).hasSize(2);
        assertThat(json.get("computedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("cerrar un publicador sin conexión no falla")
    void stopWithoutConnectionIsSafe() {
        CongestionAlertPublisher publisher = publisher(false, false, "tcp://127.0.0.1:1");
        assertThatCode(publisher::stop).doesNotThrowAnyException();
    }
}
