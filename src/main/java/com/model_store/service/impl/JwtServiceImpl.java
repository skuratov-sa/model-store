package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.exception.ApiErrors;
import com.model_store.model.CustomUserDetails;
import com.model_store.model.constant.ParticipantRole;
import com.model_store.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.micrometer.common.lang.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Date;

import static com.model_store.exception.constant.ErrorCode.TOKEN_INVALID_OR_EXPIRED;

@Slf4j
@Service
public class JwtServiceImpl implements JwtService {
    private final ReactiveUserDetailsService userDetailsService;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    @Autowired
    public JwtServiceImpl(@Lazy ReactiveUserDetailsService userDetailsService, ApplicationProperties applicationProperties) throws Exception {
        this.userDetailsService = userDetailsService;

        this.privateKey = loadPrivateKey(applicationProperties.getPrivateKeyPath());
        this.publicKey = loadPublicKey(applicationProperties.getPublicKeyPath());
    }

    @Override
    public String generateVerificationAccessToken(@NonNull Long participantId) {
        final LocalDateTime now = LocalDateTime.now();
        final Instant accessExpirationInstant = now.plus(Duration.ofDays(1)).atZone(ZoneId.systemDefault()).toInstant();
        final Date accessExpiration = Date.from(accessExpirationInstant);

        return Jwts.builder()
                .expiration(accessExpiration)
                .claim("type", "verify")
                .claim("id", participantId)
                .signWith(privateKey)
                .compact();
    }

    @Override
    public String generateAccessToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime) {
        log.debug("Generating access token: participantId={}, lifetime={}", userDetails.getId(), lifetime);
        final LocalDateTime now = LocalDateTime.now();
        final Instant accessExpirationInstant = now.plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();
        final Date accessExpiration = Date.from(accessExpirationInstant);

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .expiration(accessExpiration)
                .claim("type", "access")
                .claim("id", userDetails.getId())
                .claim("login", userDetails.getLogin())
                .claim("email", userDetails.getEmail())
                .claim("fullName", userDetails.getFullName())
                .claim("imageId", userDetails.getImageId())
                .claim("role", userDetails.getAuthorities().iterator().next().getAuthority())
                .signWith(privateKey)
                .compact();
    }

    @Override
    public String generateRefreshToken(@NonNull CustomUserDetails userDetails) {
        return generateRefreshToken(userDetails, Duration.ofDays(30));
    }

    @Override
    public String generateRefreshToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime) {
        final LocalDateTime now = LocalDateTime.now();
        final Instant refreshExpirationInstant = now.plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();
        final Date refreshExpiration = Date.from(refreshExpirationInstant);
        return Jwts.builder()
                .subject(userDetails.getEmail())
                .expiration(refreshExpiration)
                .claim("type", "refresh")
                .signWith(privateKey)
                .compact();
    }

    @Override
    public Mono<String> refreshAccessToken(@NonNull String refreshToken) {
        final String token = refreshToken.startsWith("Bearer ") ? refreshToken.substring(7) : refreshToken;

        return Mono.fromCallable(() -> {
                    JwtParser jwtParser = Jwts.parser().verifyWith(publicKey).build();
                    return jwtParser.parseSignedClaims(token).getPayload();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(JwtException.class, e -> {
                    log.warn("Invalid refresh token: {}", e.getMessage());
                    return ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Недействительный токен для обновления");
                })
                .onErrorMap(IllegalArgumentException.class, e -> {
                    log.warn("Malformed refresh token: {}", e.getMessage());
                    return ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Недействительный токен для обновления");
                })
                .flatMap(claims -> {
                    String tokenType = claims.get("type", String.class);
                    if (tokenType == null || !tokenType.equals("refresh")) {
                        log.warn("Token refresh rejected: wrong type={}", tokenType);
                        return Mono.error(ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Неверный тип токена"));
                    }
                    if (claims.getExpiration().before(new Date())) {
                        log.warn("Token refresh rejected: expired");
                        return Mono.error(ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Токен просрочен"));
                    }

                    String username = claims.getSubject();
                    boolean isAgent = "admin".equals(claims.get("issuedBy", String.class));
                    log.debug("Refreshing token: username={}, isAgent={}", username, isAgent);

                    return userDetailsService.findByUsername(username)
                            .map(userDetails -> isAgent
                                    ? generateAgentToken((CustomUserDetails) userDetails, Duration.ofHours(24))
                                    : generateAccessToken((CustomUserDetails) userDetails, Duration.ofMinutes(30)))
                            .switchIfEmpty(Mono.error(ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Пользователь не найден")));
                });
    }

    @Override
    public Long getIdByAccessToken(@NonNull String token) {
        Claims claims = parseAccessToken(token);
        return Long.valueOf(claims.get("id").toString());
    }

    @Override
    public ParticipantRole getRoleByAccessToken(String accessToken) {
        Claims claims = parseAccessToken(accessToken);
        return ParticipantRole.valueOf(claims.get("role").toString());
    }

    @Override
    public boolean isAgentToken(@NonNull String accessToken) {
        Claims claims = parseAccessToken(accessToken);
        return "agent_access".equals(claims.get("type", String.class))
                && "admin".equals(claims.get("issuedBy", String.class));
    }

    @Override
    public String generateAgentToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime) {
        log.info("Generating agent token: participantId={}, lifetime={}", userDetails.getId(), lifetime);
        final LocalDateTime now = LocalDateTime.now();
        final Instant exp = now.plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(now.atZone(ZoneId.systemDefault()).toInstant()))
                .expiration(Date.from(exp))
                .claim("type", "agent_access")
                .claim("issuedBy", "admin")
                .claim("id", userDetails.getId())
                .claim("login", userDetails.getLogin())
                .claim("email", userDetails.getEmail())
                .claim("fullName", userDetails.getFullName())
                .claim("imageId", userDetails.getImageId())
                .claim("role", userDetails.getAuthorities().iterator().next().getAuthority())
                .signWith(privateKey)
                .compact();
    }

    @Override
    public String generateAgentRefreshToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime) {
        final LocalDateTime now = LocalDateTime.now();
        final Instant exp = now.plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();
        return Jwts.builder()
                .subject(userDetails.getEmail())
                .expiration(Date.from(exp))
                .claim("type", "refresh")
                .claim("issuedBy", "admin")
                .signWith(privateKey)
                .compact();
    }



    @Override
    public Claims parseAccessToken(@NonNull String token) {
        try {
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }

            JwtParser jwtParser = Jwts.parser()
                    .verifyWith(publicKey)
                    .build();

            Jws<Claims> claimsJws = jwtParser.parseSignedClaims(token);
            return claimsJws.getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token validation failed: {}", e.getMessage());
            throw ApiErrors.authException(TOKEN_INVALID_OR_EXPIRED, "Token недействителен или срок его действия истек");
        }
    }

    private PrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        String privateKeyString = KeyLoader.loadKey(privateKeyPath);
        privateKeyString = cleanKey(privateKeyString);

        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyString);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        return keyFactory.generatePrivate(privateKeySpec);
    }

    private PublicKey loadPublicKey(String publicKeyPath) throws Exception {
        String publicKeyString = KeyLoader.loadKey(publicKeyPath);
        publicKeyString = cleanKey(publicKeyString);

        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");

        return keyFactory.generatePublic(publicKeySpec);
    }

    private String cleanKey(String keyString) {
        return keyString.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }

}