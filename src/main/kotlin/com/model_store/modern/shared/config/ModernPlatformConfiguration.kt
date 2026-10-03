package com.model_store.modern.shared.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.http.HttpMethod
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.core.io.ClassPathResource
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.domain.TokenType

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
                    val type = TokenType.fromClaim(jwt.claims["type"] as? String)
                    val role = jwt.claims["role"] as? String
                    val login = jwt.claims["login"]
                    if (type == null || (type == TokenType.AGENT_ACCESS && jwt.claims["issuedBy"] != "admin") ||
                        participantId(jwt) == null || role.isNullOrBlank() || (login != null && login !is String)) {
                        OAuth2TokenValidatorResult.failure(
                            OAuth2Error("invalid_token", "Token type cannot be used for API authorization", null),
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
                participantId = participantId(jwt)
                    ?: throw IllegalArgumentException("JWT id claim must be numeric"),
                login = jwt.claims["login"] as? String,
                role = jwt.claims["role"] as? String,
                tokenType = requireNotNull(TokenType.fromClaim(jwt.claims["type"] as? String)),
            )
            val grantedAuthorities = authorities.convert(jwt).orEmpty()
            UsernamePasswordAuthenticationToken(actor, jwt, grantedAuthorities)
        }
    }

    @Bean
    @org.springframework.core.annotation.Order(0)
    fun publicProductSearchFilterChain(
        http: HttpSecurity,
        decoder: JwtDecoder,
        converter: Converter<Jwt, AbstractAuthenticationToken>,
    ): SecurityFilterChain = http
        .securityMatcher(exact(HttpMethod.POST, "/products/find"))
        .csrf { it.disable() }
        .addFilterBefore(OptionalSearchActorFilter(decoder, converter), AnonymousAuthenticationFilter::class.java)
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    @Bean
    @org.springframework.core.annotation.Order(1)
    fun securityFilterChain(
        http: HttpSecurity,
        decoder: JwtDecoder,
        converter: Converter<Jwt, AbstractAuthenticationToken>,
    ): SecurityFilterChain = http
        .csrf { it.disable() }
        .authorizeHttpRequests {
            it.requestMatchers(exact(HttpMethod.GET, "/modern/check/public")).permitAll()
                .requestMatchers(exact(HttpMethod.GET, "/categories"), exact(HttpMethod.GET, "/dictionary"),
                    exact(HttpMethod.GET, "/images"), exact(HttpMethod.GET, "/images/default"),
                    exact(HttpMethod.GET, "/images/metadata")).permitAll()
                .requestMatchers(exact(HttpMethod.POST, "/participant"), exact(HttpMethod.POST, "/participants/find"),
                    exact(HttpMethod.POST, "/auth/verification/resend"), exact(HttpMethod.POST, "/auth/password/reset"),
                    exact(HttpMethod.POST, "/auth/verify-code"), exact(HttpMethod.POST, "/products/names/find")).permitAll()
                .requestMatchers(exact(HttpMethod.GET, "/modern/check/admin")).hasAuthority("SCOPE_ADMIN")
                .requestMatchers(adminActions()).hasAuthority("SCOPE_ADMIN")
                .anyRequest().authenticated()
        }
        .oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter) } }
        .build()

    private fun exact(method: HttpMethod, path: String): RequestMatcher = RequestMatcher { request ->
        request.method == method.name() && request.requestURI.removePrefix(request.contextPath) == path
    }

    private fun participantId(jwt: Jwt): Long? =
        (jwt.claims["id"] as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 }

    private fun adminActions(): RequestMatcher = RequestMatcher { request ->
        val path = request.requestURI.removePrefix(request.contextPath)
        path == "/admin/actions" || path.startsWith("/admin/actions/")
    }

    /** Legacy search treats an unusable optional Bearer as a guest, only on this exact route. */
    private class OptionalSearchActorFilter(
        private val decoder: JwtDecoder,
        private val converter: Converter<Jwt, AbstractAuthenticationToken>,
    ) : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val authorization = request.getHeader("Authorization")
            if (authorization?.startsWith("Bearer ") == true) {
                try {
                    val authentication = converter.convert(decoder.decode(authorization.substring(7)))
                    val context = SecurityContextHolder.createEmptyContext()
                    context.authentication = authentication
                    SecurityContextHolder.setContext(context)
                } catch (_: JwtException) {
                    // An invalid optional token has the same identity as an unauthenticated search.
                } catch (_: IllegalArgumentException) {
                    // A signed token with unusable identity claims cannot supply an Actor.
                }
            }
            chain.doFilter(request, response)
        }
    }
}
