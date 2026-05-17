package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiException;
import com.model_store.exception.constant.ErrorCode;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.model.constant.ParticipantStatus;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtServiceImplTest {

    private JwtServiceImpl jwtService;
    private ReactiveUserDetailsService mockUserDetailsService;
    private CustomUserDetails testUser;

    @BeforeEach
    void setUp() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var keyPair = gen.generateKeyPair();

        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        Path privateKeyPath = Files.createTempFile("test_priv", ".pem");
        Path publicKeyPath = Files.createTempFile("test_pub", ".pem");
        Files.writeString(privateKeyPath, privateKeyPem);
        Files.writeString(publicKeyPath, publicKeyPem);

        ApplicationProperties props = new ApplicationProperties();
        props.setPrivateKeyPath(privateKeyPath.toAbsolutePath().toString());
        props.setPublicKeyPath(publicKeyPath.toAbsolutePath().toString());

        mockUserDetailsService = mock(ReactiveUserDetailsService.class);
        jwtService = new JwtServiceImpl(mockUserDetailsService, props);

        testUser = CustomUserDetails.builder()
                .id(42L)
                .login("testuser")
                .email("testuser@example.com")
                .fullName("Test User")
                .role(ParticipantRole.USER.name())
                .password("encoded_pass")
                .status(ParticipantStatus.ACTIVE)
                .imageId(null)
                .build();
    }

    @Test
    void generateAccessToken_containsRequiredClaims() {
        String token = jwtService.generateAccessToken(testUser, Duration.ofMinutes(30));

        Claims claims = jwtService.parseAccessToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(jwtService.getIdByAccessToken(token)).isEqualTo(42L);
        assertThat(jwtService.getRoleByAccessToken(token)).isEqualTo(ParticipantRole.USER);
    }

    @Test
    void generateAgentToken_containsAgentClaims() {
        String token = jwtService.generateAgentToken(testUser, Duration.ofHours(24));

        Claims claims = jwtService.parseAccessToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("agent_access");
        assertThat(claims.get("issuedBy", String.class)).isEqualTo("admin");
        assertThat(jwtService.getIdByAccessToken(token)).isEqualTo(42L);
    }

    @Test
    void generateRefreshToken_containsRefreshType() {
        String token = jwtService.generateRefreshToken(testUser);

        Claims claims = jwtService.parseAccessToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.getSubject()).isEqualTo("testuser@example.com");
    }

    @Test
    void parseAccessToken_invalidToken_throwsApiException() {
        assertThatThrownBy(() -> jwtService.parseAccessToken("not.a.valid.jwt"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getCode() == ErrorCode.TOKEN_INVALID_OR_EXPIRED);
    }

    @Test
    void isAgentToken_accessToken_returnsFalse() {
        String token = jwtService.generateAccessToken(testUser, Duration.ofMinutes(30));
        assertThat(jwtService.isAgentToken(token)).isFalse();
    }

    @Test
    void isAgentToken_agentToken_returnsTrue() {
        String token = jwtService.generateAgentToken(testUser, Duration.ofHours(24));
        assertThat(jwtService.isAgentToken(token)).isTrue();
    }

    @Test
    void refreshAccessToken_withValidRefreshToken_returnsNewAccessToken() {
        String refreshToken = jwtService.generateRefreshToken(testUser);
        when(mockUserDetailsService.findByUsername(testUser.getEmail())).thenReturn(Mono.just(testUser));

        StepVerifier.create(jwtService.refreshAccessToken(refreshToken))
                .assertNext(token -> {
                    Claims claims = jwtService.parseAccessToken(token);
                    assertThat(claims.get("type", String.class)).isEqualTo("access");
                })
                .verifyComplete();
    }

    @Test
    void refreshAccessToken_withAccessTokenInsteadOfRefresh_returnsError() {
        String accessToken = jwtService.generateAccessToken(testUser, Duration.ofMinutes(30));

        StepVerifier.create(jwtService.refreshAccessToken(accessToken))
                .expectErrorMatches(e -> e instanceof ApiException
                        && ((ApiException) e).getCode() == ErrorCode.TOKEN_INVALID_OR_EXPIRED)
                .verify();
    }

    @Test
    void refreshAccessToken_withAgentRefreshToken_returnsAgentToken() {
        String agentRefreshToken = jwtService.generateAgentRefreshToken(testUser, Duration.ofDays(30));
        when(mockUserDetailsService.findByUsername(testUser.getEmail())).thenReturn(Mono.just(testUser));

        StepVerifier.create(jwtService.refreshAccessToken(agentRefreshToken))
                .assertNext(token -> {
                    Claims claims = jwtService.parseAccessToken(token);
                    assertThat(claims.get("type", String.class)).isEqualTo("agent_access");
                })
                .verifyComplete();
    }

    @Test
    void parseAccessToken_withBearerPrefix_stripsPrefix() {
        String token = jwtService.generateAccessToken(testUser, Duration.ofMinutes(30));
        String bearerToken = "Bearer " + token;

        assertThat(jwtService.getIdByAccessToken(bearerToken)).isEqualTo(42L);
    }
}
