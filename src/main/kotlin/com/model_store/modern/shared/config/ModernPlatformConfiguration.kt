package com.model_store.modern.shared.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.core.io.ClassPathResource
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import com.model_store.modern.shared.domain.Actor

@Configuration(proxyBeanMethods = false)
@Profile("modern")
class ModernPlatformConfiguration {
    @Bean
    fun jwtDecoder(@Value("\${app.public-key-path}") publicKeyPath: String): JwtDecoder {
        val path = Path.of(publicKeyPath)
        val pem = when {
            Files.isRegularFile(path) -> Files.readString(path)
            ClassPathResource(publicKeyPath).exists() ->
                ClassPathResource(publicKeyPath).inputStream.bufferedReader().use { it.readText() }
            Files.isRegularFile(Path.of("src", "main", "resources").resolve(path)) ->
                Files.readString(Path.of("src", "main", "resources").resolve(path))
            else -> error("JWT public key not found: $publicKeyPath")
        }
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        val keyBytes = Base64.getDecoder().decode(pem)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes)) as RSAPublicKey
        val decoder = NimbusJwtDecoder.withPublicKey(publicKey).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> { jwt ->
                    if (jwt.getClaimAsString("type") == "refresh") {
                        OAuth2TokenValidatorResult.failure(
                            OAuth2Error("invalid_token", "Refresh token cannot be used for authorization", null),
                        )
                    } else {
                        OAuth2TokenValidatorResult.success()
                    }
                },
            ),
        )
        return decoder
    }

    @Bean
    fun actorAuthenticationConverter(): Converter<Jwt, AbstractAuthenticationToken> {
        val authorities = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("role")
        }
        return Converter { jwt ->
            val actor = Actor(
                participantId = (jwt.claims["id"] as? Number)?.toLong()
                    ?: throw IllegalArgumentException("JWT id claim must be numeric"),
                login = jwt.getClaim<String>("login"),
                role = jwt.getClaim<String>("role"),
            )
            val grantedAuthorities = authorities.convert(jwt).orEmpty()
            UsernamePasswordAuthenticationToken(actor, jwt, grantedAuthorities)
        }
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        decoder: JwtDecoder,
        converter: Converter<Jwt, AbstractAuthenticationToken>,
    ): SecurityFilterChain = http
        .csrf { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers("/modern/check/public", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/modern/check/admin").hasAuthority("SCOPE_ADMIN")
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter) } }
        .build()
}
