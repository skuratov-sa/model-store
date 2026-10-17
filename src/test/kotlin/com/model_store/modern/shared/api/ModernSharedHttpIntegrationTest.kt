package com.model_store.modern.shared.api

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
])
@ActiveProfiles("modern")
class ModernSharedHttpIntegrationTest {
    @Value("\${local.server.port}") var port: Int = 0
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = ObjectMapper()

    @Test
    fun `real servlet CORS preflight and API docs preserve configured public HTTP surface`() {
        val preflight = request("OPTIONS", "/images", origin = "http://localhost:3000",
            headers = mapOf("Access-Control-Request-Method" to "POST",
                "Access-Control-Request-Headers" to "Authorization, Content-Type"))
        assertEquals(200, preflight.statusCode(), preflight.body())
        assertEquals("http://localhost:3000", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals("true", preflight.headers().firstValue("Access-Control-Allow-Credentials").orElse(null))
        val rejected = request("OPTIONS", "/images", origin = "https://unlisted.example",
            headers = mapOf("Access-Control-Request-Method" to "POST"))
        assertEquals(403, rejected.statusCode())
        assertFalse(rejected.headers().firstValue("Access-Control-Allow-Origin").isPresent)
        val status = request("GET", "/modern/check/public", origin = "http://localhost:5173")
        assertEquals(200, status.statusCode())
        assertEquals("http://localhost:5173", status.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals("modern", json.readTree(status.body())["mode"].asText())

        val docs = request("GET", "/v3/api-docs")
        assertEquals(200, docs.statusCode(), docs.body().take(500))
        assertEquals("http", json.readTree(docs.body())["components"]["securitySchemes"]["bearerAuth"]["type"].asText())
        assertEquals(401, request("POST", "/v3/api-docs").statusCode())
    }

    @Test
    fun `real servlet rejects malformed JSON and missing multipart without invoking storage`() {
        assertBadRequest(request("POST", "/products/find", "{", "application/json"),
            "Failed to read HTTP message")
        assertBadRequest(request("POST", "/auth/verify-code", "{", "application/json"),
            "Failed to read HTTP message")
        assertBadRequest(request("POST", "/products/find", """{"pageable":{"sortBy":"INVALID"}}""",
            "application/json"), "Failed to read HTTP message")
        assertEquals(200, request("POST", "/products/find", """{"pageable":{"size":0}}""",
            "application/json").statusCode())
        val boundary = "contract-boundary"
        assertEquals(401, request("POST", "/images?tag=PRODUCT", "--$boundary--\r\n",
            "multipart/form-data; boundary=$boundary").statusCode())
        val missingFiles = request("POST", "/images?tag=PRODUCT", "--$boundary--\r\n",
            "multipart/form-data; boundary=$boundary", token())
        assertBadRequest(missingFiles, "Required request part 'files' is not present.")
        assertBadRequest(request("GET", "/product/not-a-number"), "Type mismatch.")
        assertBadRequest(request("GET", "/images"), "Required query parameter 'ids' is not present.")
        assertBadRequest(request("POST", "/auth/verification/resend", "", "application/x-www-form-urlencoded"),
            "Required query parameter 'email' is not present.")
        assertBadRequest(request("POST", "/auth/password/reset", "", "application/x-www-form-urlencoded"),
            "Required query parameter 'email' is not present.")
        val oversized = "--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"large.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n" + "x".repeat(10 * 1024 * 1024) +
            "\r\n--$boundary--\r\n"
        val limit = request("POST", "/images?tag=PRODUCT", oversized,
            "multipart/form-data; boundary=$boundary", token())
        assertEquals(413, limit.statusCode(), limit.body().take(300))
    }

    private fun assertBadRequest(response: HttpResponse<String>, message: String) {
        assertEquals(400, response.statusCode(), response.body())
        val body = json.readTree(response.body())
        assertEquals(setOf("code", "message", "status", "timestamp", "details"),
            body.fieldNames().asSequence().toSet())
        assertEquals("BAD_REQUEST", body["code"].asText())
        assertEquals(message, body["message"].asText())
        assertEquals(400, body["status"].asInt())
        assertTrue(body["details"].isNull)
    }

    private fun request(method: String, path: String, body: String = "", contentType: String? = null,
                        bearer: String? = null, origin: String? = null,
                        headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (contentType != null) builder.header("Content-Type", contentType)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (origin != null) builder.header("Origin", origin)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun token(): String {
        val pem = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        return Jwts.builder().claim("id", 1L).claim("login", "contract-user").claim("role", "USER")
            .claim("type", "access").expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(key).compact()
    }

    companion object {
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val jdbc = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { jdbc }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { jdbc }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
