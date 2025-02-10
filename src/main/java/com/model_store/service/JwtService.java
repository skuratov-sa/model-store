package com.model_store.service;

import com.model_store.model.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.micrometer.common.lang.NonNull;
import reactor.core.publisher.Mono;

public interface JwtService {

    // Генерация JWT
    String generateAccessToken(@NonNull CustomUserDetails userDetails);

    String generateRefreshToken(@NonNull CustomUserDetails userDetails);

    // Верификация JWT токена
    Claims parseAccessToken(@NonNull String token);

    // Верификация refresh токена и создание нового access токена
    Mono<String> refreshAccessToken(@NonNull String refreshToken);
}