package cr.logisticaportuaria.fleet.infrastructure.websocket;


import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP configuration of the fleet dashboard.
 *
 * <p>Exposes {@code /ws} (WebSocket, no SockJS fallback) with a simple in-memory broker.
 * Clients subscribe to {@code /topic/yards/{yardId}/fleet} and
 * {@code /topic/yards/{yardId}/alerts} (ADR-014).
 */
@Configuration
@EnableWebSocketMessageBroker
public class FleetWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final FleetStompAuthInterceptor authInterceptor;


    /**
     * Constructor.
     *
     * @param authInterceptor interceptor that validates the JWT of the CONNECT frame
     * @param properties      configuration of the module
     */
    public FleetWebSocketConfig(FleetStompAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
