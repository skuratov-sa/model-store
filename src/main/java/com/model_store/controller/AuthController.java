package com.model_store.controller;

import com.model_store.model.CustomUserDetails;
import com.model_store.model.dto.LoginRequest;
import com.model_store.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final ReactiveAuthenticationManager authenticationManager;
    private final ReactiveUserDetailsService userDetailsService;
    private final JwtService jwtService;

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, String>>> login(@RequestBody Mono<LoginRequest> request) {
        return request.flatMap(req ->
                authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getLogin(), req.getPassword()))
                        .flatMap(auth -> userDetailsService.findByUsername(req.getLogin()))
                        .map(userDetails -> {
                            CustomUserDetails customUserDetails = (CustomUserDetails) userDetails;
                            String accessToken = jwtService.generateAccessToken(customUserDetails);
                            String refreshToken = jwtService.generateRefreshToken(customUserDetails);

                            Map<String, String> tokens = new HashMap<>();
                            tokens.put("access_token", accessToken);
                            tokens.put("refresh_token", refreshToken);
                            return ResponseEntity.ok(tokens);
                        })
        );
    }


    // Обновление токена с использованием refresh token
    @PostMapping("/refresh")
    public Mono<ResponseEntity<String>> refreshToken(@RequestHeader("X-Refresh-Token") String refreshToken) {
        String token = refreshToken.replace("Bearer ", ""); // Если ты всё ещё передаёшь с "Bearer" префиксом

        return jwtService.refreshAccessToken(token)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid or expired refresh token"));
    }

    @GetMapping("/profile")
    public Mono<ResponseEntity<Map<String, Object>>> getCurrentUser(@RequestHeader("Authorization") String token) {
        return Mono.justOrEmpty(token)
                .filter(t -> t.startsWith("Bearer "))  // Проверяем, что токен начинается с "Bearer "
                .map(t -> t.substring(7))  // Убираем "Bearer " из токена
                .map(jwtService::parseAccessToken)  // Парсим токен и получаем claims
                .map(claims -> {
                    // Возвращаем все claims в Map, чтобы Spring сам сериализовал в JSON
                    Map<String, Object> claimsMap = new HashMap<>(claims);
                    return ResponseEntity.ok(claimsMap);
                })
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"))));
    }

}