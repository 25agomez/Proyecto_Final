package cr.logisticaportuaria.fleet.infrastructure.mqtt;

import cr.logisticaportuaria.fleet.application.FleetProperties;
import cr.logisticaportuaria.fleet.application.FleetTelemetryService;
import cr.logisticaportuaria.fleet.application.IngestResult;
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
 * Subscriber of the fleet telemetry published in
 * {@code yard/{yardId}/fleet/{vehicleId}/telemetry}.
 *
 * <p>Like the gate subscriber, it never stops the application: when the broker is not
 * available it retries in the background. The processing itself is delegated to
 * {@link FleetTelemetryService#ingest(String, String)}, which is also the entry point used by
 * the tests (no broker required).
 */
@Component
public class FleetTelemetryMqttSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(FleetTelemetryMqttSubscriber.class);

    private final MqttProperties mqttProperties;
    private final FleetProperties properties;
    private final FleetTelemetryService telemetryService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile MqttClient client;
    private volatile Thread worker;
    private volatile Instant lastMessageAt;

    /**
     * Constructor.
     *
     * @param mqttProperties  configuration of the broker
     * @param properties      configuration of the fleet module
     * @param telemetryService service of telemetry ingestion
     */
    public FleetTelemetryMqttSubscriber(MqttProperties mqttProperties, FleetProperties properties,
            FleetTelemetryService telemetryService) {
        this.mqttProperties = mqttProperties;
        this.properties = properties;
        this.telemetryService = telemetryService;
    }

    /**
     * Starts the connection in the background.
     */
    @PostConstruct
    public void start() {
        if (!mqttProperties.isEnabled() || !properties.isMqttEnabled()) {
            LOGGER.info("Suscriptor MQTT de flota deshabilitado.");
            return;
        }
        running.set(true);
        worker = new Thread(this::connectLoop, "mqtt-fleet-telemetry");
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
                LOGGER.debug("No se pudo cerrar el cliente MQTT de flota: {}", exception.getMessage());
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
                LOGGER.warn("Fallo la conexion MQTT de flota: {}", exception.getMessage());
            }
            if (!running.get()) {
                return;
            }
            try {
                Thread.sleep(mqttProperties.getReconnectDelaySeconds() * 1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void connectAndWait() throws InterruptedException {
        String clientId = mqttProperties.getClientId() + "-fleet-"
                + UUID.randomUUID().toString().substring(0, 8);
        try (MqttClient mqttClient = new MqttClient(mqttProperties.getUrl(), clientId,
                new MemoryPersistence())) {
            this.client = mqttClient;
            mqttClient.setCallback(new Callback());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(mqttProperties.isCleanSession());
            options.setConnectionTimeout(10);
            if (mqttProperties.getUsername() != null && !mqttProperties.getUsername().isBlank()) {
                options.setUserName(mqttProperties.getUsername());
                options.setPassword(mqttProperties.getPassword() == null
                        ? new char[0]
                        : mqttProperties.getPassword().toCharArray());
            }
            mqttClient.connect(options);
            mqttClient.subscribe(properties.getTelemetryTopic(), mqttProperties.getQos());
            LOGGER.info("Suscrito al tema MQTT '{}' (broker {}).", properties.getTelemetryTopic(),
                    mqttProperties.getUrl());
            while (running.get() && mqttClient.isConnected()) {
                Thread.sleep(1000L);
            }
        } catch (MqttException exception) {
            LOGGER.warn("No se pudo conectar al broker MQTT {}: {}", mqttProperties.getUrl(),
                    exception.getMessage());
        } finally {
            this.client = null;
        }
    }

    /**
     * Processes one message. Public so the tests can inject telemetry without a broker.
     *
     * @param topic   MQTT topic
     * @param payload JSON payload
     * @return result of the ingestion
     */
    public IngestResult handleTopic(String topic, String payload) {
        lastMessageAt = Instant.now();
        return telemetryService.ingest(topic, payload);
    }

    /**
     * @return whether the subscriber is connected to the broker
     */
    public boolean isConnected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    /**
     * @return instant of the last message received, may be null
     */
    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    private final class Callback implements MqttCallback {

        @Override
        public void connectionLost(Throwable cause) {
            LOGGER.warn("Se perdio la conexion MQTT de flota: {}",
                    cause == null ? "sin detalle" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            try {
                handleTopic(topic, payload);
            } catch (RuntimeException exception) {
                LOGGER.error("No se pudo procesar la telemetria del tema {}: {}", topic,
                        exception.getMessage());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // nothing to do: the ingestion is synchronous
        }
    }
}
