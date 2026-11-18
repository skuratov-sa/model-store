package com.model_store.modern.catalog.category

import com.model_store.modern.shared.api.BootJarSmokeServices
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
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
class CategoryAdminBootJarTest {
    @TempDir lateinit var directory: Path
    private val evidenceDirectory = Path.of("build/migration-02-1/audit-validation")

    @Test
    fun `actual jar preserves legacy and denies revoked ADMIN in modern`() {
        val artifact = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(artifact), "Run bootJar first")
        val jar = Files.copy(artifact, directory.resolve("category021.jar"))
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(jar), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        println("Category021 bootJar SHA-256: $hash")
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
                        Files.createDirectories(evidenceDirectory)
                        val log = evidenceDirectory.resolve("category021-bootjar-$profile.log").toAbsolutePath()
                        val args = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                            "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
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
                            "--logging.level.org.springframework.web=WARN", "--logging.level.org.springframework.web.server=WARN",
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
                            val admin = participant(jdbc, "ADMIN", false)
                            val mail = jdbc.queryForObject("SELECT mail FROM participant WHERE id=?", String::class.java, admin)
                            val loggedIn = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/login"))
                                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
                                .POST(HttpRequest.BodyPublishers.ofString("""{"mail":"$mail","password":"category021-password"}"""))
                                .build(), HttpResponse.BodyHandlers.ofString())
                            assertEquals(200, loggedIn.statusCode(), "$profile login")
                            val jwt = ObjectMapper().readTree(loggedIn.body())["access_token"].asText()
                            assertTrue(jwt.isNotBlank())
                            val issued = ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt.split('.')[1]))
                            assertEquals(admin, issued["id"].asLong())
                            assertEquals("ADMIN", issued["role"].asText())
                            assertEquals("access", issued["type"].asText())
                            assertFalse(issued.has("issuedBy"))
                            fun action(method: String, id: Long, token: String = jwt): HttpResponse<String> = http.send(HttpRequest.newBuilder(
                                URI("http://127.0.0.1:$port/admin/actions/categories?name=jar021-new&categoryId=$id"))
                                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token")
                                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                            val created = action("POST", 0)
                            assertEquals(200, created.statusCode(), "$profile ${created.body()}")
                            val id = created.body().toLong()
                            jdbc.update("UPDATE category SET name='jar021-old' WHERE id=?", id)
                            assertEquals(200, action("PUT", id).statusCode())
                            assertEquals("jar021-new", jdbc.queryForObject("SELECT name FROM category WHERE id=?", String::class.java, id))
                            for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                                jdbc.update("UPDATE participant SET role='ADMIN',status='ACTIVE',is_agent=false WHERE id=?", admin)
                                jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
                                for (method in listOf("POST", "PUT")) {
                                    jdbc.update("UPDATE category SET name='jar021-old' WHERE id=?", id)
                                    val before = jdbc.queryForList("SELECT * FROM category ORDER BY id")
                                    val response = action(method, id)
                                    val denied = profile == "modern" || assignment == "is_agent=true"
                                    assertEquals(if (denied) 403 else 200, response.statusCode(), "$profile $method $assignment ${response.body()}")
                                    if (profile == "modern") assertTrue(response.body().contains("ACCESS_DENIED"))
                                    if (denied) assertEquals(before, jdbc.queryForList("SELECT * FROM category ORDER BY id"))
                                    else if (method == "POST") assertTrue(response.body().toLong() > 0)
                                    else assertEquals("jar021-new", jdbc.queryForObject("SELECT name FROM category WHERE id=?", String::class.java, id))
                                }
                            }
                            if (profile == "modern") {
                                jdbc.update("UPDATE participant SET role='ADMIN',status='ACTIVE',is_agent=false WHERE id=?", admin)
                                val synthetic = Jwts.builder().claim("id", admin).claim("login", "category021jar")
                                    .claim("role", "ADMIN").claim("type", "agent_access").claim("issuedBy", "admin")
                                    .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
                                val before = jdbc.queryForList("SELECT * FROM category ORDER BY id")
                                for (method in listOf("POST", "PUT")) assertEquals(403, action(method, id, synthetic).statusCode())
                                assertEquals(before, jdbc.queryForList("SELECT * FROM category ORDER BY id"))
                            }
                            val publicTree = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/categories"))
                                .GET().build(), HttpResponse.BodyHandlers.ofString())
                            assertEquals(200, publicTree.statusCode())
                            assertTrue(publicTree.body().contains("\"childs\""))
                            val logText = Files.readString(log)
                            assertFalse(Regex("""(?:access_token|refresh_token)[=:]\s*eyJ|Bearer\s+eyJ|eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
                                .containsMatchIn(logText), "$profile log contains a token or token fragment")
                            val history = jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
                            assertTrue(history.any { it["version"] == "1.29" && it["success"] == true })
                            Files.writeString(evidenceDirectory.resolve("jar-$profile.json"), ObjectMapper().writeValueAsString(
                                mapOf("profile" to profile, "sha256" to hash, "flyway" to history, "login" to loggedIn.statusCode(), "publicGet" to publicTree.statusCode())))
                        } finally {
                            process.destroy()
                            try {
                                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                            } finally {
                                if (process.isAlive) process.destroyForcibly()
                                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "$profile process did not stop")
                            }
                        }
                        println("Category021 $profile actual JAR passed")
                    }
                }
            }
        }
    }

    private fun participant(jdbc: JdbcTemplate, role: String, agent: Boolean): Long {
        val login = "jar021-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,?,'ACTIVE',?::participant_role,?) RETURNING id""", Long::class.java, login, "$login@example.test",
            BCryptPasswordEncoder().encode("category021-password"), role, agent)!!
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


