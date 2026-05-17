package com.model_store.service;

import com.model_store.model.CustomUserDetails;
import com.model_store.model.constant.ParticipantRole;
import io.jsonwebtoken.Claims;
import io.micrometer.common.lang.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.publisher.Mono;

import java.time.Period;
import java.time.temporal.TemporalAmount;

public interface JwtService {
    String generateVerificationAccessToken(@NonNull Long participantId);

    /**
     * Генерация Access токена
     */
    String generateAccessToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime);

    /**
     * Генерация Refresh токена
     */
    String generateRefreshToken(@NonNull CustomUserDetails userDetails);

    /**
     * Генерация Refresh токена с кастомным сроком жизни.
     */
    String generateRefreshToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime);

    String generateAgentToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime);

    String generateAgentRefreshToken(@NonNull CustomUserDetails userDetails, @NonNull TemporalAmount lifetime);

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

    /**
     * Получить роль по токену
     */
    ParticipantRole getRoleByAccessToken(@NonNull String accessToken);

    /**
     * Вернуть true если токен был выдан агенту (type=agent_access, issuedBy=admin)
     */
    boolean isAgentToken(@NonNull String accessToken);
}