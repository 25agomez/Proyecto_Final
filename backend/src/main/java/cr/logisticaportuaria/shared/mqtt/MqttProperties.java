package cr.logisticaportuaria.shared.mqtt;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the MQTT subscriber ({@code app.mqtt.*}).
 *
 * <p>When {@code enabled} is false the subscriber does not connect: the API
 * keeps working with the ingestion by HTTP only (this is the case when the
 * broker is not available in the development environment).
 */
@Validated
@ConfigurationProperties(prefix = "app.mqtt")
public class MqttProperties {

    /** Whether the subscriber connects to the broker. */
    private boolean enabled = true;

    /** URL of the broker (mqtt://host:1883). */
    @NotBlank
    private String url = "mqtt://localhost:1883";

    /** Topic of the readings of the gate. */
    @NotBlank
    private String gateEventsTopic = "yard/+/gate/+/events";

    /** Identifier of the client in the broker. */
    @NotBlank
    private String clientId = "logisticaportuaria-backend";

    /** Quality of service of the subscription. */
    @Min(0)
    private int qos = 1;

    /** User of the broker (may be empty). */
    private String username = "";

    /** Password of the broker (may be empty). */
    private String password = "";

    /** Whether the connection is closed when the application stops. */
    private boolean cleanSession = true;

    /** Seconds between reconnection attempts. */
    @Min(1)
    private int reconnectDelaySeconds = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGateEventsTopic() {
        return gateEventsTopic;
    }

    public void setGateEventsTopic(String gateEventsTopic) {
        this.gateEventsTopic = gateEventsTopic;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getQos() {
        return qos;
    }

    public void setQos(int qos) {
        this.qos = qos;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    public int getReconnectDelaySeconds() {
        return reconnectDelaySeconds;
    }

    public void setReconnectDelaySeconds(int reconnectDelaySeconds) {
        this.reconnectDelaySeconds = reconnectDelaySeconds;
    }
}
