package cr.logisticaportuaria.fleet.infrastructure.websocket;

import java.util.List;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP {@code CONNECT} frame of the fleet dashboard.
 *
 * <p>The HTTP handshake is open ({@code /ws}) because browsers cannot set headers on a
 * WebSocket handshake; the token travels in the {@code Authorization} header of the STOMP
 * {@code CONNECT} frame and is validated with the same {@link JwtDecoder} as the REST API
 * (ADR-014).
 */
@Component
public class FleetStompAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter converter;

    /**
     * Constructor.
     *
     * @param jwtDecoder decoder of the resource server
     * @param converter  converter that maps the claims to authorities
     */
    public FleetStompAuthInterceptor(JwtDecoder jwtDecoder,
            JwtAuthenticationConverter converter) {
        this.jwtDecoder = jwtDecoder;
        this.converter = converter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        List<String> header = accessor.getNativeHeader("Authorization");
        if (header == null || header.isEmpty() || !header.get(0).startsWith("Bearer ")) {
            throw new AccessDeniedException("STOMP CONNECT requires a Bearer token");
        }
        Jwt jwt = jwtDecoder.decode(header.get(0).substring("Bearer ".length()));
        accessor.setUser(converter.convert(jwt));
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }
}
