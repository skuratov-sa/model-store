package com.model_store.modern.shared.api

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import io.jsonwebtoken.Jwts
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/** Run after bootJar with RUN_BOOT_JAR_SMOKE=true; both modes use this exact artifact and a private database. */
@EnabledIfEnvironmentVariable(named = "RUN_BOOT_JAR_SMOKE", matches = "true")
class BootJarDualModeSmokeTest {
    @TempDir
    lateinit var workingDirectory: Path
    private var cleanupInterrupted = false
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .proxy(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }).build()

    @AfterEach
    fun closeHttpClient() {
        try {
            http.shutdownNow()
            assertTrue(awaitBounded(5) { http.awaitTermination(Duration.ofNanos(it)) }, "HTTP client did not stop")
        } finally {
            if (cleanupInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `one bootJar starts legacy and modern against isolated PostgreSQL`() {
        val artifact = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(artifact), "Run bootJar before this smoke test")
        // Freeze the artifact once: another build cannot replace it between the two JVM launches.
        val jar = Files.copy(artifact, workingDirectory.resolve("model-store-smoke.jar"))
        val jarHash = sha256(jar)
        println("bootJar snapshot SHA-256: $jarHash")
        BootJarSmokeServices().use { services ->
            EmbeddedPostgres.builder().setPGStartupWait(Duration.ofSeconds(30)).start().use { postgres ->
                for (profile in listOf("legacy", "modern")) {
                    assertEquals(jarHash, sha256(jar), "$profile jar changed")
                    val childDirectory = Files.createDirectory(workingDirectory.resolve(profile))
                    val log = Path.of("build", "migration-20-bootjar-$profile.log").toAbsolutePath()
                    val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
                    val database = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
                    val command = listOf(java, "-Dcom.amazonaws.sdk.disableEc2Metadata=true",
                        "-Dcom.amazonaws.regions.RegionUtils.disableRemote=true", "-Daws.region=us-east-1",
                        "-Duser.home=$childDirectory", "-Djava.io.tmpdir=$childDirectory", "-jar", jar.toString(),
                        "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                        "--spring.profiles.active=$profile", "--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=$database", "--spring.datasource.username=postgres",
                        "--spring.datasource.password=", "--spring.flyway.url=$database",
                        "--spring.flyway.user=postgres", "--spring.flyway.password=",
                        "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${postgres.port}/postgres",
                        "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                        "--app.public-key-path=${fixture("test_public_key.pem")}",
                        "--app.private-key-path=${fixture("test_private_key.pem")}",
                        "--logging.level.root=WARN", "--logging.level.com.model_store=WARN",
                        "--logging.level.org.springframework.web=WARN",
                        "--logging.level.org.springframework.boot=INFO") + services.properties()
                    val builder = ProcessBuilder(command).directory(childDirectory.toFile())
                        .redirectErrorStream(true).redirectOutput(log.toFile())
                    // Never inherit credentials, JVM flags, proxy settings or Spring config.
                    val osEnvironment = builder.environment().filterKeys {
                        it.uppercase() in setOf("SYSTEMROOT", "WINDIR")
                    }
                    builder.environment().clear()
                    builder.environment().putAll(osEnvironment)
                    builder.environment().putAll(mapOf("TEMP" to childDirectory.toString(),
                        "TMP" to childDirectory.toString(), "AWS_EC2_METADATA_DISABLED" to "true"))
                    val checkpoint = services.checkpoint()
                    RunningJar(builder.start(), profile).use { child ->
                        val process = child.process
                        val (port, docs) = awaitGet("/v3/api-docs", process, log)
                        assertEquals(200, docs.statusCode(), "$profile: ${docs.body().take(300)}")
                        services.assertStartup(profile, checkpoint)
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
                        assertTrue(process.isAlive, "$profile JVM exited during HTTP checks")
                        assertEquals(jarHash, sha256(jar), "$profile jar changed during execution")
                        println("bootJar $profile: S3/SMTP startup, OpenAPI/security and all eight negative JSON cases passed")
                    }
                }
            }
        }
    }

    private fun fixture(name: String): String =
        Path.of("src", "test", "resources", "keys", name).toAbsolutePath().toString()

    private fun awaitGet(path: String, process: Process, log: Path): Pair<Int, HttpResponse<String>> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline && process.isAlive) {
            val port = Regex("(?:Netty|Tomcat) started on port (\\d+)\\b")
                .find(Files.readString(log))?.groupValues?.get(1)?.toInt()
            if (port != null) runCatching { get(port, path) }.getOrNull()?.let {
                if (it.statusCode() == 200) return port to it
            }
            Thread.sleep(500)
        }
        error("bootJar did not serve $path: ${Files.readString(log).takeLast(3000)}")
    }

    private fun get(port: Int, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(2)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(port: Int, path: String, body: String, contentType: String,
                     bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) request.header("Authorization", "Bearer $bearer")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private inner class RunningJar(val process: Process, private val profile: String) : AutoCloseable {
        override fun close() {
            try {
                process.destroy()
                awaitBounded(10) { process.waitFor(it, TimeUnit.NANOSECONDS) }
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    assertTrue(awaitBounded(10) { process.waitFor(it, TimeUnit.NANOSECONDS) }, "$profile JVM did not stop")
                }
            }
            println("bootJar $profile JVM ${process.pid()} stopped")
        }
    }

    private fun awaitBounded(seconds: Long, await: (Long) -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try { return await(remaining) } catch (_: InterruptedException) { cleanupInterrupted = true }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
