package com.drawspace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.drawspace.service.ParticipantService;

/**
 * Registers the {@link RoomParticipantAuthenticator} SPI implementation required by
 * {@link WebSocketAuthInterceptor} (B1 foundation): every STOMP CONNECT is verified
 * against room state + participant token via {@link ParticipantService#authenticate}.
 */
@Configuration
public class ParticipantAuthConfig {

    @Bean
    public RoomParticipantAuthenticator roomParticipantAuthenticator(ParticipantService participantService) {
        return participantService::authenticate;
    }
}
