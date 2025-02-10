package com.model_store.service.impl;

import com.model_store.model.CustomUserDetails;
import com.model_store.service.JwtService;
import io.jsonwebtoken.*;
import io.micrometer.common.lang.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtServiceImpl implements JwtService {
    private final ReactiveUserDetailsService userDetailsService;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    @Autowired
    public JwtServiceImpl(ReactiveUserDetailsService userDetailsService) throws Exception {
        // Чтение приватного ключа для верификации JWT
        ClassPathResource privateKeyResource = new ClassPathResource("keys/private.pk8");
        String privateKeyString = new String(Files.readAllBytes(privateKeyResource.getFile().toPath()));

        // Убираем начальные и конечные разделители и пробелы
        privateKeyString = privateKeyString.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");


        byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyString);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        this.privateKey = keyFactory.generatePrivate(privateKeySpec);


        // Чтение публичного ключа для верификации JWT (в формате X.509)
        ClassPathResource publicKeyResource = new ClassPathResource("keys/public.key");
        String publicKeyString = new String(Files.readAllBytes(publicKeyResource.getFile().toPath()));

        // Убираем начальные и конечные разделители и пробелы для публичного ключа
        publicKeyString = publicKeyString.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        // Декодируем публичный ключ
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        this.publicKey = keyFactory.generatePublic(publicKeySpec);

        this.userDetailsService = userDetailsService;
    }

    // Генерация JWT
    @Override
    public String generateAccessToken(@NonNull CustomUserDetails userDetails) {
        final LocalDateTime now = LocalDateTime.now();
        final Instant accessExpirationInstant = now.plusMinutes(30).atZone(ZoneId.systemDefault()).toInstant();
        final Date accessExpiration = Date.from(accessExpirationInstant);

        return Jwts.builder()
                .setSubject(userDetails.getUsername())
                .setExpiration(accessExpiration)
                .claim("type", "access")  // Добавляем тип токена
                .claim("id", userDetails.getId())
                .claim("login", userDetails.getLogin())
                .claim("email", userDetails.getEmail())
                .claim("fullName", userDetails.getFullName())
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
                .setSubject(userDetails.getUsername())
                .setExpiration(refreshExpiration)
                .claim("type", "refresh")  // Добавляем тип токена
                .signWith(privateKey)
                .compact();
    }

    // Верификация JWT токена
    @Override
    public Claims parseAccessToken(@NonNull String token) {
        try {
            JwtParser jwtParser = Jwts.parserBuilder()
                    .setSigningKey(publicKey) // Устанавливаем публичный ключ для проверки подписи
                    .build();

            // Парсим токен и извлекаем его тело (claims)
            Jws<Claims> claimsJws = jwtParser.parseClaimsJws(token);
            return claimsJws.getBody();  // Получаем claims
        } catch (JwtException | IllegalArgumentException e) {
            // В случае ошибки при верификации токена
            throw new RuntimeException("Invalid JWT token", e);
        }
    }

    // Верификация refresh токена и создание нового access токена
    @Override
    public Mono<String> refreshAccessToken(@NonNull String refreshToken) {
        try {
            // Проверяем refresh токен
            JwtParser jwtParser = Jwts.parserBuilder()
                    .setSigningKey(publicKey) // Устанавливаем публичный ключ для проверки подписи
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
                    .map(userDetails -> generateAccessToken((CustomUserDetails) userDetails))
                    .switchIfEmpty(Mono.error(new RuntimeException("User not found")));

        } catch (JwtException | IllegalArgumentException e) {
            return Mono.error(new RuntimeException("Invalid or expired refresh token", e));
        }
    }
}