package cr.logisticaportuaria.gate.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.logisticaportuaria.gate.application.GateEventIngestionService;
import cr.logisticaportuaria.gate.application.dto.GateEventRequest;
import cr.logisticaportuaria.gate.domain.EventSource;
import cr.logisticaportuaria.shared.mqtt.MqttProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Subscriber of the readings published by the edge service in the topic
 * {@code yard/{yardId}/gate/{gateId}/events}.
 *
 * <p>The subscriber never stops the application: when the broker is not
 * available it retries in the background and the API keeps working with the
 * ingestion by HTTP.
 */
@Component
public class GateEventMqttSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(GateEventMqttSubscriber.class);

    private final MqttProperties properties;
    private final GateEventIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile MqttClient client;
    private volatile Thread worker;
    private volatile Instant lastMessageAt;

    /**
     * Constructor.
     *
     * @param properties       configuration of the broker
     * @param ingestionService service that stores and reconciles the readings
     * @param objectMapper     mapper used to read the JSON of the broker
     */
    public GateEventMqttSubscriber(MqttProperties properties,
            GateEventIngestionService ingestionService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Starts the connection in the background.
     */
    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            LOGGER.info("Suscriptor MQTT deshabilitado ('app.mqtt.enabled=false').");
            return;
        }
        running.set(true);
        worker = new Thread(this::connectLoop, "mqtt-gate-events");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Closes the connection.
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        MqttClient current = client;
        if (current != null) {
            try {
                if (current.isConnected()) {
                    current.disconnect();
                }
                current.close();
            } catch (MqttException exception) {
                LOGGER.debug("No se pudo cerrar el cliente MQTT: {}", exception.getMessage());
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void connectLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                connectAndWait();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                LOGGER.warn("Fallo la conexión MQTT: {}", exception.getMessage());
            }
            if (!running.get()) {
                return;
            }
            try {
                Thread.sleep(properties.getReconnectDelaySeconds() * 1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void connectAndWait() throws InterruptedException {
        String clientId = properties.getClientId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (MqttClient mqttClient = new MqttClient(properties.getUrl(), clientId, new MemoryPersistence())) {
            this.client = mqttClient;
            mqttClient.setCallback(new Callback());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(properties.isCleanSession());
            options.setConnectionTimeout(10);
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                options.setUserName(properties.getUsername());
                options.setPassword(properties.getPassword() == null
                        ? new char[0]
                        : properties.getPassword().toCharArray());
            }
            mqttClient.connect(options);
            mqttClient.subscribe(properties.getGateEventsTopic(), properties.getQos());
            LOGGER.info("Suscrito al tema MQTT '{}' (broker {}).", properties.getGateEventsTopic(), properties.getUrl());
            while (running.get() && mqttClient.isConnected()) {
                Thread.sleep(1000L);
            }
        } catch (MqttException exception) {
            LOGGER.warn("No se pudo conectar al broker MQTT {}: {}", properties.getUrl(), exception.getMessage());
        } finally {
            this.client = null;
        }
    }

    /** Callback of the subscription: every message is a reading of the edge. */
    private final class Callback implements MqttCallback {

        @Override
        public void connectionLost(Throwable cause) {
            LOGGER.warn("Se perdió la conexión MQTT: {}", cause == null ? "sin detalle" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            lastMessageAt = Instant.now();
            try {
                GateEventRequest request = objectMapper.readValue(payload, GateEventRequest.class);
                ingestionService.ingest(request, EventSource.EDGE);
                LOGGER.debug("Lectura '{}' ingerida desde el tema {}.",
                        request.eventId(), topic);
            } catch (Exception exception) {
                LOGGER.error("No se pudo procesar la lectura del tema {}: {}", topic, exception.getMessage());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // nothing to do: the ingestion is synchronous
        }
    }

    /**
     * @return true when the subscriber is connected to the broker
     */
    public boolean isConnected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    /**
     * @return the instant of the last message received (null when there was none)
     */
    public Instant lastMessageAt() {
        return lastMessageAt;
    }
}
