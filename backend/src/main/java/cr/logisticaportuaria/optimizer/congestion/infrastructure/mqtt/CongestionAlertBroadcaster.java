package cr.logisticaportuaria.optimizer.congestion.infrastructure.mqtt;

import cr.logisticaportuaria.optimizer.congestion.domain.CongestionPrediction;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Broadcasts the congestion alerts over STOMP when the WebSocket broker exists.
 *
 * <p>The fleet module (P6) is the owner of the STOMP configuration, so this component does
 * not register any endpoint: it looks for the {@link SimpMessagingTemplate} and, when it is
 * not present, the alert stays in MQTT only. Once P6 registers the broker, the alerts reach
 * {@code /topic/yard/{yardId}/alerts/congestion} without further changes here.
 */
@Component
public class CongestionAlertBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(CongestionAlertBroadcaster.class);

    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;
    private final CongestionAlertPublisher publisher;

    /**
     * Constructor.
     *
     * @param messagingTemplate STOMP template, when the broker is configured
     * @param publisher         builder of the payload and MQTT publisher
     */
    public CongestionAlertBroadcaster(ObjectProvider<SimpMessagingTemplate> messagingTemplate,
            CongestionAlertPublisher publisher) {
        this.messagingTemplate = messagingTemplate;
        this.publisher = publisher;
    }

    /**
     * Sends the alert to the WebSocket subscribers.
     *
     * @param yardId     yard of the gate
     * @param prediction prediction with {@code riskLevel = ALTO}
     * @return whether the alert was broadcast
     */
    public boolean broadcast(UUID yardId, CongestionPrediction prediction) {
        SimpMessagingTemplate template = messagingTemplate.getIfAvailable();
        if (template == null) {
            LOGGER.debug("Sin broker STOMP: la alerta de congestión solo se publica en MQTT.");
            return false;
        }
        Map<String, Object> payload = publisher.payload(prediction);
        template.convertAndSend("/topic/yard/" + yardId + "/alerts/congestion", payload);
        return true;
    }
}
