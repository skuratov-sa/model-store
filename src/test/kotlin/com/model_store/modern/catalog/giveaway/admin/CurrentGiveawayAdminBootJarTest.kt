package com.model_store.modern.catalog.giveaway.admin

import com.model_store.modern.shared.api.BootJarSmokeServices
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.OutputStream
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/** One frozen JAR in default legacy and modern; generated keys and private PG/S3/SMTP only. */
@EnabledIfEnvironmentVariable(named = "RUN_BOOT_JAR_SMOKE", matches = "true")
class CurrentGiveawayAdminBootJarTest {
    @TempDir lateinit var directory: Path
    @Test fun `same actual jar preserves default legacy and closes current admin R03 in modern`() {
        val artifact = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(artifact), "Build bootJar from the current source first")
        val evidenceRoot = Path.of("build/migration-22-1").toAbsolutePath()
        Files.createDirectories(evidenceRoot)
        val frozenDirectory = Files.createTempDirectory(evidenceRoot, "frozen-")
        val jar = Files.copy(artifact, frozenDirectory.resolve("giveaway221.jar"))
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(jar), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        val jarHash = digest.digest().joinToString("") { "%02x".format(it) }
        Files.writeString(frozenDirectory.resolve("jar-sha256.txt"), jarHash)
        println("22.1 frozen bootJar=$jar SHA-256=$jarHash")
        Giveaway221Keys().use { keys ->
            BootJarSmokeServices().use { services ->
                EmbeddedPostgres.start().use { pg ->
                    val url = "jdbc:postgresql://127.0.0.1:${pg.port}/postgres"
                    val jdbc = JdbcTemplate(DriverManagerDataSource(url, "postgres", ""))
                    HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { http ->
                        for (mode in listOf("default-legacy", "modern")) {
                            val child = Files.createDirectory(directory.resolve(mode))
                            val log = frozenDirectory.resolve("$mode-raw.log")
                            val args = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                                "-Xmx512m", "-Dcom.amazonaws.sdk.disableEc2Metadata=true",
                                "-Dcom.amazonaws.regions.RegionUtils.disableRemote=true", "-Daws.region=us-east-1",
                                "-Duser.home=$child", "-Djava.io.tmpdir=$child", "-jar", jar.toString(),
                                "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                                "--server.address=127.0.0.1", "--server.port=0",
                                "--spring.datasource.url=$url", "--spring.datasource.username=postgres", "--spring.datasource.password=",
                                "--spring.flyway.url=$url", "--spring.flyway.user=postgres", "--spring.flyway.password=",
                                "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${pg.port}/postgres",
                                "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                                "--app.public-key-path=${keys.publicPath}", "--app.private-key-path=${keys.privatePath}",
                                "--logging.level.root=WARN", "--logging.level.com.model_store=WARN",
                                "--logging.level.org.springframework.security=WARN", "--logging.level.org.springframework.web=WARN",
                                "--logging.level.org.springframework.web.server=WARN", "--logging.level.org.springframework.http=WARN",
                                "--logging.level.reactor.netty=WARN", "--logging.level.io.netty=WARN",
                                "--logging.level.org.springframework.boot=INFO") +
                                (if (mode == "modern") listOf("--spring.profiles.active=modern") else emptyList()) + services.properties()
                            val builder = ProcessBuilder(args).directory(child.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                            val os = builder.environment().filterKeys { it.uppercase() in setOf("SYSTEMROOT", "WINDIR") }
                            builder.environment().clear(); builder.environment().putAll(os)
                            builder.environment().putAll(mapOf("TEMP" to child.toString(), "TMP" to child.toString(),
                                "AWS_EC2_METADATA_DISABLED" to "true", "JAVA_TOOL_OPTIONS" to
                                    "-Dapp.public-key-path=keys/test_public_key.pem -Dapp.private-key-path=keys/test_private_key.pem"))
                            val checkpoint = services.checkpoint()
                            val process = builder.start()
                            println("22.1 child mode=$mode pid=${process.pid()} raw-log=$log")
                            try {
                                val port = awaitPort(process, log, http)
                                services.assertStartup(if (mode == "modern") "modern" else "legacy", checkpoint)
                                CurrentGiveawayAdminContract(jdbc, port, keys).use { c ->
                                    c.schema("$mode before contract")
                                    if (mode == "modern") {
                                        c.revokedAccessMatrix()
                                        c.positiveAndTokenContract()
                                    } else {
                                        // Legacy claim-based role revocation behavior remains available as before.
                                        val admin = c.participant()
                                        val id = c.product(admin)
                                        val jwt = c.login(admin)
                                        c.ok(c.request("GET", "/admin/actions/giveaways/$id", jwt))
                                        jdbc.update("UPDATE participant SET role='USER' WHERE id=?", admin)
                                        c.ok(c.request("GET", "/admin/actions/giveaways/history", jwt))
                                        c.ok(c.request("GET", "/admin/actions/giveaways/$id", jwt))
                                        c.ok(c.request("PUT", "/admin/actions/giveaways/$id", jwt, """{"winnersCount":4}"""))
                                    }
                                    c.schema("$mode after contract")
                                }
                            } finally {
                                process.destroy()
                                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "$mode child did not stop")
                            }
                            println("22.1 actual JAR $mode contract completed")
                        }
                    }
                }
            }
        }
    }
    private fun awaitPort(process: Process, log: Path, http: HttpClient): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (process.isAlive && System.nanoTime() < deadline) {
            val port = Regex("(?:Netty|Tomcat) started on port (\\d+)\\b").find(Files.readString(log))?.groupValues?.get(1)?.toInt()
            if (port != null && runCatching {
                http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v3/api-docs"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
            }.getOrDefault(false)) return port
            Thread.sleep(250)
        }
        error("22.1 JAR startup failed; inspect $log")
    }
}
