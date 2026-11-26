package cr.logisticaportuaria.optimizer.congestion.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.optimizer.congestion.application.CongestionProperties;
import cr.logisticaportuaria.optimizer.congestion.domain.CongestionPrediction;
import cr.logisticaportuaria.shared.mqtt.MqttProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes the congestion alerts in {@code yard/{yardId}/alerts/congestion}.
 *
 * <p>The publisher connects lazily on the first alert and never blocks the API: when the
 * broker is not available the alert is logged and the forecast keeps working (the same
 * degradation rule as the MQTT subscriber of the gate).
 */
@Component
public class CongestionAlertPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(CongestionAlertPublisher.class);

    private final ObjectMapper objectMapper;
    private final CongestionProperties properties;
    private final MqttProperties mqttProperties;

    private volatile MqttClient client;

    /**
     * Constructor.
     *
     * @param objectMapper    serializer of the payload
     * @param properties      configuration of the congestion module
     * @param mqttProperties  configuration of the broker
     */
    public CongestionAlertPublisher(ObjectMapper objectMapper, CongestionProperties properties,
            MqttProperties mqttProperties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mqttProperties = mqttProperties;
    }

    /**
     * Publishes an alert.
     *
     * @param yardId     yard of the gate (used in the topic)
     * @param prediction prediction with {@code riskLevel = ALTO}
     */
    public void publish(UUID yardId, CongestionPrediction prediction) {
        if (!properties.isAlertsEnabled() || !mqttProperties.isEnabled()) {
            return;
        }
        try {
            String topic = properties.getAlertTopic().formatted(yardId);
            byte[] body = objectMapper.writeValueAsBytes(payload(prediction));
            MqttMessage message = new MqttMessage(body);
            message.setQos(mqttProperties.getQos());
            ensureClient().publish(topic, message);
            LOGGER.info("Alerta de congestión publicada en '{}' ({}).", topic, prediction.riskLevel());
        } catch (Exception exception) {
            LOGGER.warn("No se pudo publicar la alerta de congestión: {}", exception.getMessage());
        }
    }

    private MqttClient ensureClient() throws Exception {
        MqttClient current = client;
        if (current != null && current.isConnected()) {
            return current;
        }
        synchronized (this) {
            if (client != null && client.isConnected()) {
                return client;
            }
            String url = mqttProperties.getUrl();
            if (url.startsWith("mqtt://")) {
                url = "tcp://" + url.substring("mqtt://".length());
            }
            String clientId = mqttProperties.getClientId() + "-congestion-" + UUID.randomUUID().toString().substring(0, 8);
            MqttClient created = new MqttClient(url, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            if (mqttProperties.getUsername() != null && !mqttProperties.getUsername().isBlank()) {
                options.setUserName(mqttProperties.getUsername());
                options.setPassword(mqttProperties.getPassword() == null
                        ? new char[0]
                        : mqttProperties.getPassword().toCharArray());
            }
            created.connect(options);
            client = created;
            return created;
        }
    }

    /**
     * Builds the body of an alert as the STOMP broadcaster would send it.
     *
     * @param prediction prediction
     * @return the payload
     */
    public Map<String, Object> payload(CongestionPrediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "CONGESTION");
        payload.put("gateId", prediction.gateId());
        payload.put("gateCode", prediction.gateCode());
        payload.put("horizonMin", prediction.horizonMin());
        payload.put("expectedWaitMin", prediction.roundedWait());
        payload.put("p10", Math.round(prediction.p10() * 10.0) / 10.0);
        payload.put("p90", Math.round(prediction.p90() * 10.0) / 10.0);
        payload.put("queueLength", prediction.queueLength());
        payload.put("riskLevel", prediction.riskLevel().name());
        payload.put("confidence", prediction.confidence().name());
        payload.put("drivers", prediction.drivers());
        payload.put("computedAt", Instant.now().toString());
        return payload;
    }

    /** Closes the connection. */
    @PreDestroy
    public void stop() {
        MqttClient current = client;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect();
            }
            current.close();
        } catch (Exception exception) {
            LOGGER.debug("No se pudo cerrar el cliente MQTT de congestión: {}", exception.getMessage());
        }
    }

    /**
     * @return the payload as UTF-8 JSON, used by the tests
     *
     * @param prediction prediction
     * @return the JSON text
     */
    public String json(CongestionPrediction prediction) {
        try {
            return new String(objectMapper.writeValueAsBytes(payload(prediction)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "{}";
        }
    }
}
