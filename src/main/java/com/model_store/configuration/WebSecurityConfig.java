package com.model_store.configuration;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.model.CustomUserDetails;
import com.model_store.repository.ParticipantRepository;
import com.model_store.service.impl.KeyLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;


@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class WebSecurityConfig {
    private final ParticipantRepository participantRepository;
    private final ApplicationProperties applicationProperties;

    @Bean
    public ReactiveUserDetailsService userDetailsService() {
        return login -> participantRepository.findByLogin(login)
                .map(participant ->
                        CustomUserDetails.builder()
                                .id(participant.getId())
                                .login(participant.getLogin())
                                .email(participant.getMail())
                                .fullName(participant.getFullName())
                                .role(participant.getRole().name())
                                .password(participant.getPassword())
                                .build()
                );
    }

    @Bean
    public NimbusReactiveJwtDecoder jwtDecoder() throws Exception {
        String publicKeyString = KeyLoader.loadKey(applicationProperties.getPublicKeyPath());

        // Убираем начальные и конечные разделители и пробелы для публичного ключа
        publicKeyString = publicKeyString.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");

        // Декодируем публичный ключ
        byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyString);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();

        // Добавляем проверку, чтобы токен с type="refresh" не прошёл валидацию
        decoder.setJwtValidator(jwt -> {
            String tokenType = jwt.getClaimAsString("type");
            if ("refresh".equals(tokenType)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Refresh token cannot be used for authorization", null));
            }
            return OAuth2TokenValidatorResult.success();
        });

        return decoder;
    }


    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, NimbusReactiveJwtDecoder jwtDecoder) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/auth/login", "/auth/refresh", "/webjars/swagger-ui/**", "/v3/api-docs/**").permitAll() // Эти пути не требуют токен
                        .pathMatchers(HttpMethod.POST, "/participant").permitAll()
                        .pathMatchers("/admin/actions/**").hasAuthority("SCOPE_ADMIN")
                        .anyExchange().authenticated() // Все остальные требуют токен
                ).oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService userDetailsService) {
        UserDetailsRepositoryReactiveAuthenticationManager authenticationManager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        authenticationManager.setPasswordEncoder(passwordEncoder());
        return authenticationManager;
    }

    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

        // Используем JwtGrantedAuthoritiesConverter для обработки ролей из JWT
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role"); // Указываем имя claim, где хранятся роли
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        // Используем адаптер для работы с Mono
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
