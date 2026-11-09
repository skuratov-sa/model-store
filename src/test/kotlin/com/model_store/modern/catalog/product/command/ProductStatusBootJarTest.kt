package com.model_store.modern.catalog.product.command

import com.model_store.modern.shared.api.BootJarSmokeServices
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

/** Same frozen artifact, real TCP, private PostgreSQL/S3/SMTP, fixture RSA only. */
@EnabledIfEnvironmentVariable(named = "RUN_BOOT_JAR_SMOKE", matches = "true")
class ProductStatusBootJarTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual jar preserves legacy and denies revoked ADMIN in modern`() {
        val artifact = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(artifact), "Run bootJar first")
        val jar = Files.copy(artifact, directory.resolve("status162.jar"))
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(jar), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        println("Status162 bootJar SHA-256: $hash")
        val key = Files.readString(Path.of(fixture("test_private_key.pem")))
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
            .let { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(it))) }
        BootJarSmokeServices().use { services ->
            EmbeddedPostgres.start().use { postgres ->
                val url = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
                val jdbc = JdbcTemplate(DriverManagerDataSource(url, "postgres", ""))
                HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build().use { http ->
                    for (profile in listOf("legacy", "modern")) {
                        val childDirectory = Files.createDirectory(directory.resolve(profile))
                        val log = Path.of("build/status162-bootjar-$profile.log").toAbsolutePath()
                        val args = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                            "-Dcom.amazonaws.sdk.disableEc2Metadata=true", "-Dcom.amazonaws.regions.RegionUtils.disableRemote=true", "-Daws.region=us-east-1",
                            "-Duser.home=$childDirectory", "-Djava.io.tmpdir=$childDirectory", "-jar", jar.toString(),
                            "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                            "--spring.profiles.active=$profile", "--server.address=127.0.0.1", "--server.port=0",
                            "--spring.datasource.url=$url", "--spring.datasource.username=postgres", "--spring.datasource.password=",
                            "--spring.flyway.url=$url", "--spring.flyway.user=postgres", "--spring.flyway.password=",
                            "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${postgres.port}/postgres",
                            "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                            "--app.public-key-path=${fixture("test_public_key.pem")}", "--app.private-key-path=${fixture("test_private_key.pem")}",
                            "--logging.level.root=WARN", "--logging.level.com.model_store=WARN",
                            "--logging.level.org.springframework.boot=INFO") + services.properties()
                        val builder = ProcessBuilder(args).directory(childDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                        val os = builder.environment().filterKeys { it.uppercase() in setOf("SYSTEMROOT", "WINDIR") }
                        builder.environment().clear()
                        builder.environment().putAll(os)
                        builder.environment().putAll(mapOf("TEMP" to childDirectory.toString(), "TMP" to childDirectory.toString(), "AWS_EC2_METADATA_DISABLED" to "true"))
                        val checkpoint = services.checkpoint()
                        val process = builder.start()
                        try {
                            val port = awaitPort(process, log, http)
                            services.assertStartup(profile, checkpoint)
                            val owner = participant(jdbc, "USER", true)
                            val admin = participant(jdbc, "ADMIN", false)
                            val jwt = Jwts.builder().claim("id", admin).claim("login", "status162jar")
                                .claim("role", "ADMIN").claim("type", "access")
                                .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
                            val id = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,availability)
                                VALUES (?,10,'RUB',?,'EXTERNAL_PRODUCT') RETURNING id""", Long::class.java, "jar162-${UUID.randomUUID()}", owner)!!
                            fun status(): HttpResponse<String> = http.send(HttpRequest.newBuilder(
                                URI("http://127.0.0.1:$port/admin/actions/product/$id?productStatus=BLOCKED"))
                                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $jwt")
                                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                            assertEquals(200, status().statusCode(), "$profile normal admin")
                            for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                                jdbc.update("UPDATE product SET status='ACTIVE' WHERE id=?", id)
                                jdbc.update("UPDATE participant SET role='ADMIN',status='ACTIVE',is_agent=false WHERE id=?", admin)
                                jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
                                val response = status()
                                // Legacy's authentication layer already rejects a normal token for
                                // a participant changed to an agent; role/status revocations still pass.
                                val denied = profile == "modern" || assignment == "is_agent=true"
                                assertEquals(if (denied) 403 else 200, response.statusCode(), "$profile $assignment ${response.body()}")
                                if (profile == "modern") assertTrue(response.body().contains("ACCESS_DENIED"))
                                assertEquals(if (denied) "ACTIVE" else "BLOCKED",
                                    jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, id))
                            }
                        } finally {
                            process.destroy()
                            try {
                                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                            } finally {
                                if (process.isAlive) process.destroyForcibly()
                                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "$profile process did not stop")
                            }
                        }
                        println("Status162 $profile actual JAR passed")
                    }
                }
            }
        }
    }

    private fun participant(jdbc: JdbcTemplate, role: String, agent: Boolean): Long {
        val login = "jar162-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,'hash','ACTIVE',?::participant_role,?) RETURNING id""", Long::class.java, login, "$login@example.test", role, agent)!!
    }

    private fun awaitPort(process: Process, log: Path, http: HttpClient): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (process.isAlive && System.nanoTime() < deadline) {
            val port = Regex("(?:Netty|Tomcat) started on port (\\d+)\\b").find(Files.readString(log))?.groupValues?.get(1)?.toInt()
            if (port != null) {
                val ready = runCatching { http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v3/api-docs"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }.getOrDefault(false)
                if (ready) return port
            }
            Thread.sleep(250)
        }
        error("JAR startup failed: ${Files.readString(log).takeLast(3000)}")
    }

    private fun fixture(name: String) = Path.of("src/test/resources/keys", name).toAbsolutePath().toString()
}
