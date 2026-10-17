package com.model_store.modern.shared.api

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import io.jsonwebtoken.Jwts
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/** Run after bootJar with RUN_BOOT_JAR_SMOKE=true; both modes use this exact artifact and a private database. */
@EnabledIfEnvironmentVariable(named = "RUN_BOOT_JAR_SMOKE", matches = "true")
class BootJarDualModeSmokeTest {
    @Test
    fun `one bootJar starts legacy and modern against isolated PostgreSQL`() {
        val jar = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "Run bootJar before this smoke test")
        EmbeddedPostgres.start().use { postgres ->
            for (profile in listOf("legacy", "modern")) {
                val port = ServerSocket(0).use { it.localPort }
                val log = Files.createTempFile("migration-20-$profile-", ".log")
                log.toFile().deleteOnExit()
                val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
                val database = "jdbc:postgresql://localhost:${postgres.port}/postgres"
                val command = listOf(java, "-jar", jar.toString(),
                    "--spring.profiles.active=$profile", "--server.port=$port",
                    "--spring.datasource.url=$database", "--spring.datasource.username=postgres",
                    "--spring.datasource.password=", "--spring.flyway.url=$database",
                    "--spring.flyway.user=postgres", "--spring.flyway.password=",
                    "--spring.r2dbc.url=r2dbc:postgresql://localhost:${postgres.port}/postgres",
                    "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                    "--app.public-key-path=${fixture("test_public_key.pem")}",
                    "--app.private-key-path=${fixture("test_private_key.pem")}",
                    "--logging.level.root=WARN")
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                try {
                    val docs = awaitGet(port, "/v3/api-docs", process, log)
                    assertEquals(200, docs.statusCode(), "$profile: ${docs.body().take(300)}")
                    val check = get(port, "/modern/check/public")
                    assertEquals(if (profile == "modern") 200 else 401, check.statusCode(), profile)
                    for ((label, response) in listOf(
                        "malformed" to post(port, "/products/find", "{", "application/json"),
                        "verifyMalformed" to post(port, "/auth/verify-code", "{", "application/json"),
                        "enum" to post(port, "/products/find", """{"catalogFlags":["INVALID"]}""", "application/json"),
                        "missingFiles" to post(port, "/images?tag=PRODUCT", "--test-boundary--\r\n",
                            "multipart/form-data; boundary=test-boundary", token()),
                    )) {
                        assertEquals(400, response.statusCode(), "$profile $label ${response.body().take(300)}")
                        val body = ObjectMapper().readTree(response.body())
                        assertEquals(setOf("code", "message", "status", "timestamp", "details"),
                            body.fieldNames().asSequence().toSet(), "$profile $label ${response.body()}")
                        assertEquals("BAD_REQUEST", body["code"].asText(), "$profile $label")
                        assertEquals(400, body["status"].asInt(), "$profile $label")
                        assertEquals(if (label == "missingFiles")
                            "Required request part 'files' is not present." else "Failed to read HTTP message",
                            body["message"].asText(), "$profile $label")
                        assertTrue(body["details"].isNull, "$profile $label")
                    }
                    for ((label, path) in listOf("invalidPath" to "/product/not-a-number",
                        "missingQuery" to "/images")) {
                        val response = get(port, path)
                        assertEquals(400, response.statusCode(), "$profile $label ${response.body().take(300)}")
                        val body = ObjectMapper().readTree(response.body())
                        assertEquals(setOf("code", "message", "status", "timestamp", "details"),
                            body.fieldNames().asSequence().toSet(), "$profile $label ${response.body()}")
                        assertEquals("BAD_REQUEST", body["code"].asText(), "$profile $label")
                        assertEquals(400, body["status"].asInt(), "$profile $label")
                        assertEquals(if (label == "invalidPath") "Type mismatch."
                            else "Required query parameter 'ids' is not present.",
                            body["message"].asText(), "$profile $label")
                        assertTrue(body["details"].isNull, "$profile $label")
                    }
                    for (path in listOf("/auth/verification/resend", "/auth/password/reset")) {
                        val response = post(port, path, "", "application/x-www-form-urlencoded")
                        assertEquals(400, response.statusCode(), "$profile $path ${response.body().take(300)}")
                        val body = ObjectMapper().readTree(response.body())
                        assertEquals(setOf("code", "message", "status", "timestamp", "details"),
                            body.fieldNames().asSequence().toSet(), "$profile $path ${response.body()}")
                        assertEquals("BAD_REQUEST", body["code"].asText(), "$profile $path")
                        assertEquals("Required query parameter 'email' is not present.",
                            body["message"].asText(), "$profile $path")
                        assertTrue(body["details"].isNull, "$profile $path")
                    }
                } finally {
                    process.destroy()
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(10, TimeUnit.SECONDS)
                    }
                }
            }
        }
    }

    private fun fixture(name: String): String =
        Path.of("src", "test", "resources", "keys", name).toAbsolutePath().toString()

    private fun awaitGet(port: Int, path: String, process: Process, log: Path): HttpResponse<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline && process.isAlive) {
            runCatching { get(port, path) }.getOrNull()?.let { if (it.statusCode() == 200) return it }
            Thread.sleep(500)
        }
        error("bootJar did not serve $path: ${Files.readString(log).takeLast(3000)}")
    }

    private fun get(port: Int, path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(2)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(port: Int, path: String, body: String, contentType: String,
                     bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) request.header("Authorization", "Bearer $bearer")
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun token(): String {
        val pem = Files.readString(Path.of(fixture("test_private_key.pem")))
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        return Jwts.builder().claim("id", 1L).claim("login", "contract-user")
            .claim("role", "USER").claim("type", "access")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
    }
}
