package com.model_store.modern.shared.config

import com.model_store.modern.shared.domain.Actor
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import jakarta.servlet.Filter
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringJUnitConfig
@WebAppConfiguration
@ActiveProfiles("modern", "security-test")
@TestPropertySource(properties = ["app.public-key-path=keys/test_public_key.pem"])
@ContextConfiguration(classes = [ModernMvcSecurityTest.SecurityTestConfiguration::class])
class ModernMvcSecurityTest {
    @Autowired lateinit var context: WebApplicationContext
    @Autowired @Qualifier("springSecurityFilterChain") lateinit var securityFilter: Filter

    private val mvc: MockMvc by lazy {
        val builder = MockMvcBuilders.webAppContextSetup(context)
        builder.addFilters<DefaultMockMvcBuilder>(securityFilter)
        builder.build()
    }

    @Test
    fun `only listed public method and path combinations admit guests`() {
        val public = listOf(
            HttpMethod.GET to "/categories", HttpMethod.GET to "/dictionary",
            HttpMethod.GET to "/images", HttpMethod.GET to "/images/default",
            HttpMethod.GET to "/images/metadata",
            HttpMethod.POST to "/participant", HttpMethod.POST to "/participants/find",
            HttpMethod.POST to "/auth/verification/resend", HttpMethod.POST to "/auth/password/reset",
            HttpMethod.POST to "/auth/verify-code", HttpMethod.POST to "/products/find",
            HttpMethod.POST to "/products/names/find",
        )
        public.forEach { (method, path) -> assertEquals(200, call(method, path).status, "$method $path") }
        val protected = listOf(
            HttpMethod.GET to "/participant", HttpMethod.PUT to "/participant",
            HttpMethod.POST to "/images", HttpMethod.DELETE to "/images",
            HttpMethod.POST to "/categories", HttpMethod.PUT to "/categories",
            HttpMethod.GET to "/products/find", HttpMethod.GET to "/products/names/find",
            HttpMethod.POST to "/products/find/extra", HttpMethod.GET to "/categories/",
            HttpMethod.GET to "/images/default/extra",
            HttpMethod.GET to "/address/regions", HttpMethod.GET to "/address",
            HttpMethod.POST to "/auth/refresh", HttpMethod.POST to "/admin/actions/categories",
        )
        protected.forEach { (method, path) -> assertEquals(401, call(method, path).status, "$method $path") }
    }

    @Test
    fun `verified user admin and agent tokens supply typed actors`() {
        val user = token("access", "USER")
        val admin = token("access", "ADMIN")
        val agent = token("agent_access", "USER", issuedBy = "admin")
        assertEquals(200, call(HttpMethod.GET, "/participant", user).status)
        assertEquals(403, call(HttpMethod.GET, "/modern/check/admin", user).status)
        assertEquals(200, call(HttpMethod.GET, "/modern/check/admin", admin).status)
        assertEquals(403, call(HttpMethod.POST, "/admin/actions/categories", user).status)
        assertEquals(403, call(HttpMethod.PUT, "/admin/actions/participants/42/status", user).status)
        assertEquals(200, call(HttpMethod.POST, "/admin/actions/categories", admin).status)
        assertEquals(200, call(HttpMethod.PUT, "/admin/actions/participants/42/status", admin).status)
        assertTrue(call(HttpMethod.POST, "/products/find", user).body.contains("ACCESS"))
        assertTrue(call(HttpMethod.POST, "/products/find", agent).body.contains("AGENT_ACCESS"))
        assertTrue(call(HttpMethod.GET, "/participant", agent).body.contains("AGENT_ACCESS"))
        assertEquals(403, call(HttpMethod.POST, "/admin/actions/categories", agent).status)
        assertEquals(401, call(HttpMethod.GET, "/participant", token("agent_access", "USER")).status)
    }

    @Test
    fun `refresh verification forged and expired tokens cannot grant access`() {
        val invalid = listOf(
            token("refresh", "ADMIN"), token("verify", "ADMIN"), token("agent_access", "USER"),
            token("access", "ADMIN", expiresAt = Instant.now().minusSeconds(60)),
            token("access", "ADMIN", signer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private),
            token("access", "ADMIN", id = 42.5), token("access", 7),
            "not-a-jwt",
        )
        invalid.forEach { bearer ->
            assertEquals(401, call(HttpMethod.GET, "/participant", bearer).status)
            assertEquals(401, call(HttpMethod.GET, "/modern/check/admin", bearer).status)
            assertEquals(200, call(HttpMethod.POST, "/products/find", bearer).status)
            assertTrue(call(HttpMethod.POST, "/products/find", bearer).body.contains("guest"))
            assertEquals(401, call(HttpMethod.POST, "/products/names/find", bearer).status)
            assertEquals(401, call(HttpMethod.GET, "/categories", bearer).status)
        }
        assertTrue(call(HttpMethod.POST, "/products/find").body.contains("guest"))
    }

    private fun call(method: HttpMethod, path: String, bearer: String? = null): Result {
        val builder = request(method, path)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val response = mvc.perform(builder).andReturn().response
        return Result(response.status, response.contentAsString)
    }

    private data class Result(val status: Int, val body: String)

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("security-test")
    @EnableWebMvc
    @EnableWebSecurity
    @Import(ModernPlatformConfiguration::class, ProbeController::class)
    class SecurityTestConfiguration

    @RestController
    @TestComponent
    @Profile("security-test")
    class ProbeController {
        @RequestMapping("/**")
        fun route(@AuthenticationPrincipal actor: Actor?): String =
            actor?.let { "${it.participantId}:${it.role}:${it.tokenType}:${it.isAgentAccess}" } ?: "guest"
    }

    companion object {
        private val privateKey: PrivateKey by lazy {
            val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        }

        private fun token(
            type: String, role: Any, issuedBy: String? = null,
            expiresAt: Instant = Instant.now().plusSeconds(3600), signer: PrivateKey = privateKey,
            id: Any = 42L,
        ): String {
            val builder = Jwts.builder().claim("id", id).claim("login", "pilot")
                .claim("role", role).claim("type", type).expiration(Date.from(expiresAt))
            if (issuedBy != null) builder.claim("issuedBy", issuedBy)
            return builder.signWith(signer).compact()
        }
    }
}
