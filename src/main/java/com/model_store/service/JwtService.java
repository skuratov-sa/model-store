package com.model_store.service;

import com.model_store.model.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.micrometer.common.lang.NonNull;
import reactor.core.publisher.Mono;

public interface JwtService {

    /**
     * Генерация Access токена
     */
    String generateAccessToken(@NonNull CustomUserDetails userDetails);

    /**
     * Генерация Refresh токена
     */
    String generateRefreshToken(@NonNull CustomUserDetails userDetails);

    /**
     * Получаем список параметров, которые хранит token
     */
    Claims parseAccessToken(@NonNull String token);

    /**
     * Верификация refresh токена и создание нового access токена
     */
    Mono<String> refreshAccessToken(@NonNull String refreshToken);

    /**
     * Получить id пользователя по токену
     */
    Long getIdByAccessToken(@NonNull String accessToken);
}