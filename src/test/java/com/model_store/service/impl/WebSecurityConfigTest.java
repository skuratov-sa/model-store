package com.model_store.service.impl;

import com.model_store.configuration.WebSecurityConfig;
import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import com.model_store.repository.ParticipantRepository;
import com.model_store.service.ParticipantService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSecurityConfigTest {
    @Test
    void decoderRejectsExpiredAndRefreshTokensButAcceptsActiveAccessToken() throws Exception {
        ApplicationProperties properties = new ApplicationProperties();
        properties.setPrivateKeyPath("keys/test_private_key.pem");
        properties.setPublicKeyPath("keys/test_public_key.pem");
        JwtServiceImpl tokens = new JwtServiceImpl(mock(ReactiveUserDetailsService.class), properties,
                mock(ParticipantRepository.class));
        WebSecurityConfig security = new WebSecurityConfig(mock(ParticipantService.class), properties);
        var decoder = security.jwtDecoder();
        CustomUserDetails admin = CustomUserDetails.builder()
                .id(42L)
                .login("admin")
                .email("admin@example.com")
                .role(ParticipantRole.ADMIN.name())
                .status(ParticipantStatus.ACTIVE)
                .build();

        StepVerifier.create(decoder.decode(tokens.generateAccessToken(admin, Duration.ofMinutes(-5))))
                .expectError(JwtException.class)
                .verify();
        StepVerifier.create(decoder.decode(tokens.generateRefreshToken(admin)))
                .expectError(JwtException.class)
                .verify();
        StepVerifier.create(decoder.decode(tokens.generateAccessToken(admin, Duration.ofMinutes(5))))
                .assertNext(jwt -> assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN"))
                .verifyComplete();
    }
}
