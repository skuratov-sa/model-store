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
                .setExpiration(accessExpiration)
                .claim("type", "verify")  // Добавляем тип токена
                .claim("id", participantId)
                .signWith(privateKey)
                .compact();
    }

    @Override
    public String generateAccessToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime) {
        final LocalDateTime now = LocalDateTime.now();
        final Instant accessExpirationInstant = now.plus(lifetime).atZone(ZoneId.systemDefault()).toInstant();
        final Date accessExpiration = Date.from(accessExpirationInstant);

        return Jwts.builder()
                .setSubject(userDetails.getUsername())
                .setExpiration(accessExpiration)
                .claim("type", "access")  // Добавляем тип токена
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
        final LocalDateTime now = LocalDateTime.now();
        final Instant refreshExpirationInstant = now.plusDays(30).atZone(ZoneId.systemDefault()).toInstant();
        final Date refreshExpiration = Date.from(refreshExpirationInstant);
        return Jwts.builder()
                .setSubject(userDetails.getEmail())
                .setExpiration(refreshExpiration)
                .claim("type", "refresh")  // Добавляем тип токена
                .signWith(privateKey)
                .compact();
    }

    @Override
    public Mono<String> refreshAccessToken(@NonNull String refreshToken) {
        try {
            if (refreshToken.startsWith("Bearer ")) {
                refreshToken = refreshToken.substring(7);  // Убираем "Bearer " (7 символов)
            }

            JwtParser jwtParser = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .build();

            Jws<Claims> claimsJws = jwtParser.parseClaimsJws(refreshToken);
            Claims claims = claimsJws.getBody();

            // Проверка на "type" claim
            String tokenType = claims.get("type", String.class);
            if (tokenType == null || !tokenType.equals("refresh")) {
                return Mono.error(new RuntimeException("Invalid token type"));
            }

            // Проверка, что токен не просрочен
            Date expiration = claims.getExpiration();
            if (expiration.before(new Date())) {
                return Mono.error(new RuntimeException("Refresh token has expired"));
            }

            String username = claims.getSubject();

            // Если refresh токен валиден, генерируем новый access токен
            return userDetailsService.findByUsername(username)
                    .map(userDetails -> generateAccessToken((CustomUserDetails) userDetails, Duration.ofMinutes(30)))
                    .switchIfEmpty(Mono.error(new RuntimeException("User not found")));

        } catch (JwtException | IllegalArgumentException e) {
            return Mono.error(new RuntimeException("Invalid or expired refresh token", e));
        }
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
    public Claims parseAccessToken(@NonNull String token) {
        try {
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);  // Убираем "Bearer " (7 символов)
            }

            JwtParser jwtParser = Jwts.parserBuilder()
                    .setSigningKey(publicKey) // Устанавливаем публичный ключ для проверки подписи
                    .build();

            Jws<Claims> claimsJws = jwtParser.parseClaimsJws(token);
            return claimsJws.getBody();
        } catch (JwtException | IllegalArgumentException e) {
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