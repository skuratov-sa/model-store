package com.model_store.modern.shared.config

import com.model_store.modern.shared.domain.Actor
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.security.web.util.matcher.RequestMatcher
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
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.mock.web.MockHttpServletRequest
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
    @Autowired lateinit var decoder: JwtDecoder
    @Autowired lateinit var converter: Converter<Jwt, AbstractAuthenticationToken>

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
            HttpMethod.POST to "/auth/login", HttpMethod.POST to "/auth/refresh",
            HttpMethod.GET to "/product/42", HttpMethod.GET to "/giveaways/active",
            HttpMethod.GET to "/giveaways/products/42",
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
            HttpMethod.GET to "/auth/login", HttpMethod.GET to "/auth/refresh",
            HttpMethod.POST to "/auth/profile", HttpMethod.GET to "/auth/login/extra",
            HttpMethod.POST to "/auth/refresh/extra", HttpMethod.GET to "/auth/other",
            HttpMethod.GET to "/product", HttpMethod.GET to "/product/",
            HttpMethod.GET to "/product/42/",
            HttpMethod.GET to "/product/42/extra", HttpMethod.GET to "/products/42",
            HttpMethod.POST to "/product/42", HttpMethod.PUT to "/product/42",
            HttpMethod.DELETE to "/product/42", HttpMethod.HEAD to "/product/42",
            HttpMethod.PUT to "/auth/login", HttpMethod.DELETE to "/auth/refresh",
            HttpMethod.GET to "/giveaways", HttpMethod.GET to "/giveaways/active/",
            HttpMethod.GET to "/giveaways/active/extra", HttpMethod.GET to "/giveaways/history",
            HttpMethod.GET to "/giveaways/products", HttpMethod.GET to "/giveaways/products/42/",
            HttpMethod.GET to "/giveaways/products/42/extra", HttpMethod.GET to "/giveaways/admin",
            HttpMethod.POST to "/giveaways/active", HttpMethod.PUT to "/giveaways/active",
            HttpMethod.DELETE to "/giveaways/products/42", HttpMethod.HEAD to "/giveaways/products/42",
            HttpMethod.POST to "/admin/actions/categories",
        )
        protected.forEach { (method, path) ->
            val result = call(method, path)
            assertEquals(401, result.status, "$method $path: ${result.body}")
        }
        listOf("/product//42", "/product/42%2Fextra", "/product/42%5Cextra", "/product/42%252Fextra").forEach { path ->
            assertTrue(call(HttpMethod.GET, path).status in setOf(400, 401), path)
        }
        listOf("/giveaways/products//42", "/giveaways/products/42%2Fextra",
            "/giveaways/products/42%5Cextra", "/giveaways/products/42%252Fextra").forEach { path ->
            assertTrue(call(HttpMethod.GET, path).status in setOf(400, 401), path)
        }
        assertEquals(400, call(HttpMethod.GET, "/giveaways/products/not-a-number").status)
        assertEquals(400, call(HttpMethod.GET, "/product/not-a-number").status)
    }

    @Test
    fun `product route stays limited to one segment under a context path`() {
        fun underContext(method: HttpMethod, path: String): Int = mvc.perform(
            request(method, "/store$path").contextPath("/store")
                .with { it.requestURI = "/store$path"; it },
        ).andReturn().response.status

        assertEquals(200, underContext(HttpMethod.GET, "/product/42"))
        assertEquals(400, underContext(HttpMethod.GET, "/product/letters"))
        assertEquals(401, underContext(HttpMethod.GET, "/product/42/extra"))
        assertEquals(401, underContext(HttpMethod.GET, "/product/42/"))
        assertTrue(underContext(HttpMethod.GET, "/product//42") in setOf(400, 401))
        assertEquals(401, underContext(HttpMethod.PUT, "/product/42"))
        assertEquals(200, underContext(HttpMethod.GET, "/giveaways/active"))
        assertEquals(200, underContext(HttpMethod.GET, "/giveaways/products/42"))
        assertEquals(401, underContext(HttpMethod.GET, "/giveaways/products/42/extra"))
        assertEquals(401, underContext(HttpMethod.POST, "/giveaways/active"))
    }

    @Test
    fun `raw giveaway matcher rejects encoded separators independently of the firewall`() {
        val method = ModernPlatformConfiguration::class.java.getDeclaredMethod("singleGiveawayProductSegment")
        method.isAccessible = true
        val matcher = method.invoke(ModernPlatformConfiguration()) as RequestMatcher
        fun matches(path: String, verb: String = "GET", contextPath: String = ""): Boolean {
            val request = MockHttpServletRequest(verb, "$contextPath$path")
            request.contextPath = contextPath
            return matcher.matches(request)
        }
        assertTrue(matches("/giveaways/products/42"))
        assertTrue(matches("/giveaways/products/42", contextPath = "/store"))
        assertTrue(matches("/giveaways/products/not-a-number")) // MVC supplies the legacy 400.
        listOf("/giveaways/products/", "/giveaways/products//42", "/giveaways/products/42/",
            "/giveaways/products/42/extra", "/giveaways/products/42\\extra",
            "/giveaways/products/42;mode=admin",
            "/giveaways/products/42%2Fextra", "/giveaways/products/42%5cextra",
            "/giveaways/products/42%252Fextra", "/giveaways/products/42%255cextra").forEach {
            assertFalse(matches(it), it)
        }
        assertFalse(matches("/giveaways/products/42", verb = "POST"))
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
        assertTrue(call(HttpMethod.GET, "/auth/profile", user).body.contains("pilot@example.test:Test Pilot"))
        assertTrue(call(HttpMethod.GET, "/product/42", agent).body.contains("42"))
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `refresh verification forged and expired tokens cannot grant access`(output: CapturedOutput) {
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
            assertEquals(401, call(HttpMethod.GET, "/auth/profile", bearer).status)
            assertEquals(401, call(HttpMethod.GET, "/product/42", bearer).status)
            assertEquals(401, call(HttpMethod.GET, "/giveaways/active", bearer).status)
            assertEquals(401, call(HttpMethod.GET, "/giveaways/products/42", bearer).status)
            assertEquals(401, call(HttpMethod.POST, "/auth/login", bearer).status)
            assertEquals(401, call(HttpMethod.POST, "/auth/refresh", bearer).status)
        }
        assertTrue(call(HttpMethod.POST, "/products/find").body.contains("guest"))
        invalid.forEach { assertFalse(output.all.contains(it), "Bearer token appeared in logs") }
        assertFalse(output.all.contains("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `authentication manager retains the verified full JWT after credential erasure`(output: CapturedOutput) {
        val raw = token("access", "USER")
        val provider = JwtAuthenticationProvider(decoder).apply { setJwtAuthenticationConverter(converter) }
        val manager = ProviderManager(provider)
        val authentication = manager.authenticate(BearerTokenAuthenticationToken(raw))
        val actor = authentication.principal as Actor
        val jwt = authentication.credentials as Jwt
        assertEquals(42L, actor.participantId)
        assertEquals("pilot@example.test", jwt.claims["email"])
        assertEquals("Test Pilot", jwt.claims["fullName"])
        assertEquals("USER", jwt.claims["role"])
        assertFalse(authentication.toString().contains(raw))
        (authentication as AbstractAuthenticationToken).eraseCredentials()
        assertEquals(jwt, authentication.credentials)
        assertFalse(call(HttpMethod.GET, "/auth/profile", raw).body.contains(raw))
        assertFalse(output.all.contains(raw))
        assertFalse(output.all.contains("-----BEGIN PRIVATE KEY-----"))
    }

    private fun call(method: HttpMethod, path: String, bearer: String? = null): Result {
        val builder = request(method, path).with { it.requestURI = path; it }
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
        @GetMapping("/product/{id}")
        fun product(@PathVariable id: Long): String = "product:$id"

        @GetMapping("/giveaways/products/{productId}")
        fun giveawayProduct(@PathVariable productId: Long): String = "giveaway:$productId"

        @GetMapping("/auth/profile")
        fun profile(authentication: Authentication): String {
            val actor = authentication.principal as Actor
            val jwt = authentication.credentials as Jwt
            return "${actor.participantId}:${jwt.claims["email"]}:${jwt.claims["fullName"]}"
        }

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
                .claim("email", "pilot@example.test").claim("fullName", "Test Pilot")
                .claim("role", role).claim("type", type).expiration(Date.from(expiresAt))
            if (issuedBy != null) builder.claim("issuedBy", issuedBy)
            return builder.signWith(signer).compact()
        }
    }
}
