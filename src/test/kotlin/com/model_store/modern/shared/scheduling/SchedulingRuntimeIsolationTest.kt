package com.model_store.modern.shared.scheduling

import com.model_store.ModelStoreApplication
import com.model_store.modern.shared.api.BootJarSmokeServices
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import java.time.Duration

/** Real application graphs and private fixture services; no cron wait or business calls. */
class SchedulingRuntimeIsolationTest {
    @Test
    fun `default and explicit legacy retain all old jobs while modern excludes them`() {
        val legacyClasses = setOf(
            "com.model_store.scheduler.ProductUpdateScheduler",
            "com.model_store.scheduler.ImageCleanupScheduler",
            "com.model_store.scheduler.ProductCleanupScheduler",
        )
        BootJarSmokeServices().use { services ->
            for (profile in listOf("", "legacy", "modern")) {
                EmbeddedPostgres.builder().setPGStartupWait(Duration.ofSeconds(30)).start().use { postgres ->
                    val url = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
                    val args = listOf(
                        "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                        "--spring.profiles.active=$profile", "--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=$url", "--spring.datasource.username=postgres", "--spring.datasource.password=",
                        "--spring.flyway.url=$url", "--spring.flyway.user=postgres", "--spring.flyway.password=",
                        "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${postgres.port}/postgres",
                        "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                        "--app.public-key-path=keys/test_public_key.pem", "--app.private-key-path=keys/test_private_key.pem",
                        "--logging.level.root=WARN", "--logging.level.com.model_store=WARN",
                    ) + services.properties()
                    val checkpoint = services.checkpoint()
                    SpringApplicationBuilder(ModelStoreApplication::class.java).run(*args.toTypedArray()).use { context ->
                        services.assertStartup(if (profile == "modern") "modern" else "legacy", checkpoint)
                        val processor = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java)
                        val jobs = processor.scheduledTasks.map { task ->
                            task.task.runnable.toString().substringBeforeLast('.')
                        }
                        if (profile == "modern") {
                            assertTrue(jobs.none { it in legacyClasses }, jobs.toString())
                            legacyClasses.forEach { assertTrue(context.getBeansOfType(Class.forName(it)).isEmpty(), it) }
                            assertTrue(context.containsBean("modernSchedulingConfiguration"))
                            assertEquals(List(3) { "com.model_store.modern.shared.scheduling.infrastructure.MaintenanceSchedulers" }, jobs)
                            assertFalse(context.containsBean("legacyRuntimeConfiguration"))
                        } else {
                            assertEquals(legacyClasses, jobs.toSet())
                            assertEquals(3, jobs.size, "No duplicate registration in $profile")
                            assertFalse(context.containsBean("modernSchedulingConfiguration"))
                            assertTrue(context.containsBean("legacyRuntimeConfiguration"))
                        }
                    }
                }
            }
        }
    }
}
