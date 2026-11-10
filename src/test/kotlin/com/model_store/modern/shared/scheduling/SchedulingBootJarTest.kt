package com.model_store.modern.shared.scheduling

import com.model_store.modern.shared.api.BootJarSmokeServices
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "RUN_BOOT_JAR_SMOKE", matches = "true")
class SchedulingBootJarTest {
    @TempDir lateinit var directory: Path

    @Test fun `same frozen jar registers exactly one scheduler set in each runtime`() {
        val source = Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath()
        val jar = Files.copy(source, directory.resolve("scheduling30.jar"))
        val hash = sha(jar)
        println("Scheduling30 bootJar SHA-256: $hash")
        BootJarSmokeServices().use { services ->
            HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build().use { http ->
                for (profile in listOf("legacy", "modern")) {
                    EmbeddedPostgres.start().use { postgres ->
                        val child = Files.createDirectory(directory.resolve(profile))
                        val log = Path.of("build/migration-30/bootjar-$profile.log").toAbsolutePath()
                        Files.createDirectories(log.parent)
                        val url = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
                        val args = listOf(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                            "-Dcom.amazonaws.sdk.disableEc2Metadata=true", "-Dcom.amazonaws.regions.RegionUtils.disableRemote=true",
                            "-Daws.region=us-east-1", "-Duser.home=$child", "-Djava.io.tmpdir=$child", "-jar", jar.toString(),
                            "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                            "--spring.profiles.active=$profile", "--server.address=127.0.0.1", "--server.port=0",
                            "--spring.datasource.url=$url", "--spring.datasource.username=postgres", "--spring.datasource.password=",
                            "--spring.flyway.url=$url", "--spring.flyway.user=postgres", "--spring.flyway.password=",
                            "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${postgres.port}/postgres",
                            "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                            "--app.public-key-path=${fixture("test_public_key.pem")}",
                            "--app.private-key-path=${fixture("test_private_key.pem")}",
                            "--logging.level.root=WARN", "--logging.level.com.model_store=WARN",
                            "--logging.level.org.springframework.boot=INFO",
                            "--logging.level.org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor=TRACE") + services.properties()
                        val builder = ProcessBuilder(args).directory(child.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                        val os = builder.environment().filterKeys { it.uppercase() in setOf("SYSTEMROOT", "WINDIR") }
                        builder.environment().clear(); builder.environment().putAll(os)
                        builder.environment().putAll(mapOf("TEMP" to child.toString(), "TMP" to child.toString(), "AWS_EC2_METADATA_DISABLED" to "true"))
                        val checkpoint = services.checkpoint()
                        val process = builder.start()
                        try {
                            awaitReady(process, log, http)
                            services.assertStartup(profile, checkpoint)
                            val registrations = Files.readAllLines(log).filter { it.contains("@Scheduled methods processed on bean") }
                            if (profile == "legacy") {
                                assertEquals(3, registrations.size, registrations.joinToString("\n"))
                                for (bean in listOf("productUpdateScheduler", "imageCleanupScheduler", "productCleanupScheduler"))
                                    assertEquals(1, registrations.count { it.contains("1 @Scheduled methods processed on bean '$bean'") })
                            } else {
                                assertEquals(1, registrations.size, registrations.joinToString("\n"))
                                assertTrue(registrations.single().contains("3 @Scheduled methods processed on bean 'maintenanceSchedulers'"))
                            }
                            assertEquals(hash, sha(jar))
                        } finally {
                            process.destroy()
                            try { if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly() }
                            finally { if (process.isAlive) process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)) }
                            println("Scheduling30 $profile child exit=${process.exitValue()}")
                        }
                    }
                }
            }
        }
    }

    private fun awaitReady(process: Process, log: Path, http: HttpClient) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (process.isAlive && System.nanoTime() < deadline) {
            val port = Regex("(?:Netty|Tomcat) started on port (\\d+)\\b").find(Files.readString(log))?.groupValues?.get(1)
            if (port != null && runCatching {
                    http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v3/api-docs"))
                        .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
                }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("JAR did not become ready: ${Files.readString(log).takeLast(3000)}")
    }
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun fixture(name: String) = Path.of("src/test/resources/keys", name).toAbsolutePath().toString()
}
