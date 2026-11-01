package com.drawspace.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket (SockJS) wiring per CONTRACT §4:
 * endpoint {@code /ws-drawspace} (SockJS enabled), application destination prefix
 * {@code /app}, simple brokers {@code /topic} + {@code /queue}, user destination prefix
 * {@code /user}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String ENDPOINT_PATH = "/ws-drawspace";
    private static final int MAX_MESSAGE_BYTES = 1_048_576;   // 1 MiB payload cap (§6: payload ≤ 512 KB)
    private static final int SEND_BUFFER_BYTES = 1_048_576;
    private static final int SEND_TIME_LIMIT_MS = 20_000;

    private final DrawSpaceProperties properties;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    public WebSocketConfig(DrawSpaceProperties properties, WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.properties = properties;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT_PATH)
                .setAllowedOriginPatterns(properties.cors().allowedOriginList().toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MAX_MESSAGE_BYTES);
        registration.setSendBufferSizeLimit(SEND_BUFFER_BYTES);
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MS);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
