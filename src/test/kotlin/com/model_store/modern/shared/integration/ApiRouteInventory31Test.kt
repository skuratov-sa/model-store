package com.model_store.modern.shared.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.ModelStoreApplication
import com.model_store.modern.shared.api.BootJarSmokeServices
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.bind.annotation.*
import java.nio.file.Files
import java.nio.file.Path

/** Actual handler registries, not a regex inventory of source annotations. */
class ApiRouteInventory31Test {
    private val output = Path.of("build/migration-31/inventory")
    private val json = ObjectMapper().writerWithDefaultPrettyPrinter()
    private data class Route(val verb: String, val path: String, val handler: String, val signature: String,
        val conditions: String, val parameters: List<String>) {
        fun key() = "$verb ${path.replace(Regex("\\{[^}]+}"), "{}") }"
        fun business(): Boolean {
            val docs = setOf("/swagger-ui.html", "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config")
            if (verb == "GET" && path in docs && handler.startsWith("org.springdoc.")) return false
            if (verb == "ANY" && path == "/error" && handler in setOf(
                "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController#error",
                "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController#errorHtml")) return false
            val probes = mapOf("/modern/check/public" to "publicCheck", "/modern/check/actor" to "actor", "/modern/check/admin" to "adminCheck")
            if (verb == "GET" && probes[path]?.let { handler == "com.model_store.modern.shared.api.PlatformCheckController#$it" } == true) return false
            // Everything not explicitly identified as infrastructure must enter the business comparison.
            check(handler.startsWith("com.model_store.")) { "Unclassified framework/foreign handler: $verb $path $handler" }
            return true
        }
        fun row() = mapOf("verb" to verb, "path" to path, "handler" to handler,
            "signature" to signature, "conditions" to conditions, "parameters" to parameters)
    }

    @Test fun `actual default legacy and modern business API registries and concrete call graphs`() {
        Files.createDirectories(output)
        val inventories = linkedMapOf<String, List<Route>>()
        BootJarSmokeServices().use { services ->
            for (profile in listOf("", "modern")) {
                val mode = if (profile.isEmpty()) "legacy-default" else profile
                EmbeddedPostgres.start().use { pg ->
                    val jdbc = "jdbc:postgresql://127.0.0.1:${pg.port}/postgres"
                    val args = listOf("--spring.config.location=classpath:/application.yml", "--spring.config.import=",
                        "--server.address=127.0.0.1", "--server.port=0",
                        "--spring.datasource.url=$jdbc", "--spring.datasource.username=postgres", "--spring.datasource.password=",
                        "--spring.flyway.url=$jdbc", "--spring.flyway.user=postgres", "--spring.flyway.password=",
                        "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${pg.port}/postgres",
                        "--spring.r2dbc.username=postgres", "--spring.r2dbc.password=",
                        "--app.public-key-path=keys/test_public_key.pem", "--app.private-key-path=keys/test_private_key.pem",
                        "--logging.level.root=WARN", "--logging.level.com.model_store=WARN") +
                        (if(profile.isEmpty()) emptyList() else listOf("--spring.profiles.active=$profile")) + services.properties()
                    val checkpoint = services.checkpoint()
                    SpringApplicationBuilder(ModelStoreApplication::class.java).run(*args.toTypedArray()).use { ctx ->
                        if (profile.isEmpty()) {
                            assertTrue(ctx.environment.activeProfiles.isEmpty())
                            assertEquals("legacy",ctx.environment.getProperty("spring.profiles.default"))
                            assertFalse(ctx.environment.matchesProfiles("modern"))
                            Files.writeString(output.resolve("default-profile-selection.txt"),
                                "active=${ctx.environment.activeProfiles.toList()}; diagnosticDefaults=${ctx.environment.defaultProfiles.toList()}; configuredDefault=${ctx.environment.getProperty("spring.profiles.default")}; context=${ctx.javaClass.name}")
                            assertTrue(ctx.javaClass.name.contains("ReactiveWebServerApplicationContext"),ctx.javaClass.name)
                        } else assertTrue(ctx.javaClass.name.contains("ServletWebServerApplicationContext"),ctx.javaClass.name)
                        services.assertStartup(if (profile.isEmpty()) "legacy" else "modern", checkpoint)
                        val routes = routes(ctx, profile == "modern").sortedWith(compareBy({it.path}, {it.verb}))
                        assertDeclaredControllers(ctx,routes,profile == "modern",mode)
                        inventories[mode] = routes
                        Files.writeString(output.resolve("$mode.json"), json.writeValueAsString(routes.map {it.row()}))
                        val graph = ctx.beanDefinitionNames.mapNotNull { name -> ctx.getType(name)?.let {
                            if (it.name.startsWith("com.model_store.")) "$name: ${it.name}" else null
                        }}.sorted()
                        Files.write(output.resolve("$mode-beans.txt"), graph)
                        if (profile == "modern") {
                            assertTrue(ctx.getBeansOfType(io.r2dbc.spi.ConnectionFactory::class.java).isEmpty())
                            for (prefix in listOf("com.model_store.controller.", "com.model_store.service.impl.",
                                "com.model_store.repository.", "com.model_store.scheduler.")) {
                                assertFalse(graph.any {it.substringAfter(": ").startsWith(prefix)}, graph.joinToString("\n"))
                            }
                            assertTrue(routes.filter {it.business()}.all {it.handler.startsWith("com.model_store.modern.")})
                        } else {
                            assertFalse(ctx.getBeansOfType(io.r2dbc.spi.ConnectionFactory::class.java).isEmpty())
                            assertConcrete(ctx, "emailServiceImpl", "participantService", "com.model_store.service.impl.ParticipantServiceImpl")
                            assertConcrete(ctx, "adminController", "orderCaseService", "com.model_store.service.impl.OrderCaseService")
                            assertConcrete(ctx, "adminAgentController", "agents", "com.model_store.service.impl.AdminAgentService")
                            assertTrue(routes.filter {it.business()}.all {it.handler.startsWith("com.model_store.controller.")})
                        }
                    }
                }
            }
        }
        val old = inventories.getValue("legacy-default").filter {it.business()}
        val modern = inventories.getValue("modern").filter {it.business()}
        val missing = old.filter {before -> modern.none {it.key() == before.key()}}
        val additions = modern.filter {after -> old.none {it.key() == after.key()}}
        assertEquals(old.size,old.map {it.key()}.toSet().size,"Legacy normalized route keys must be unique")
        assertEquals(modern.size,modern.map {it.key()}.toSet().size,"Modern normalized route keys must be unique")
        old.forEach { before -> modern.singleOrNull {it.key()==before.key()}?.let {after ->
            assertEquals(before.conditions,after.conditions,"Mapping conditions changed at ${before.key()}")
        }}
        Files.writeString(output.resolve("comparison.json"), json.writeValueAsString(mapOf(
            "legacyBusinessRoutes" to old.size, "modernBusinessRoutes" to modern.size,
            "missing" to missing.map {it.row()}, "additions" to additions.map {it.row()})))
        Files.writeString(output.resolve("routes.md"), buildString {
            appendLine("| Legacy method/path | Legacy handler | Legacy parameters | Modern handler / parameters |")
            appendLine("| --- | --- | --- | --- |")
            old.forEach { r -> appendLine("| `${r.verb} ${r.path}` | `${r.handler}` | ${r.parameters.joinToString("; ").replace("|", "\\|")} | ${modern.filter {it.key()==r.key()}.joinToString {"`${it.handler}`: ${it.parameters.joinToString("; ")}"}.ifEmpty {"MISSING"}} |") }
        })
        assertTrue(missing.isEmpty(), "Unaccounted legacy routes: ${missing.map {it.key()}}")
        assertTrue(additions.isEmpty(), "Unaccounted modern business aliases: ${additions.map {it.key()}}")
    }

    private fun assertConcrete(ctx: ConfigurableApplicationContext, bean: String, field: String, type: String) {
        val instance = ctx.getBean(bean)
        val target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<Any>(instance)
        val f = target.javaClass.getDeclaredField(field).apply {isAccessible = true}
        assertEquals(type, AopUtils.getTargetClass(f.get(target)).name)
    }

    private fun assertDeclaredControllers(ctx: ConfigurableApplicationContext,routes: List<Route>,modern: Boolean,mode: String) {
        val scanner=org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false,ctx.environment)
        scanner.addIncludeFilter(org.springframework.core.type.filter.AnnotationTypeFilter(RestController::class.java))
        val declared=scanner.findCandidateComponents("com.model_store").map {Class.forName(it.beanClassName!!)}
            .filter {it.name.startsWith("com.model_store.modern.")==modern}
        val signatures=declared.flatMap {type -> type.methods.filter {
            org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(it,RequestMapping::class.java)
        }.map {it.toGenericString()} }.toSet()
        Files.write(output.resolve("$mode-declared-controller-methods.txt"),signatures.sorted())
        assertEquals(signatures,routes.filter {it.handler.startsWith("com.model_store.")}.map {it.signature}.toSet(),
            "Every profile-eligible declared controller method must be present in the actual handler registry")
        if(modern) {
            // Inspect actual types as well as exported names: a JDK proxy must not hide an old service/repository.
            val oldScanner=object: org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false) {
                override fun isCandidateComponent(beanDefinition: org.springframework.beans.factory.annotation.AnnotatedBeanDefinition) = beanDefinition.metadata.isIndependent
            }
            oldScanner.addIncludeFilter(org.springframework.core.type.filter.AnnotationTypeFilter(org.springframework.stereotype.Component::class.java))
            oldScanner.findCandidateComponents("com.model_store").map {it.beanClassName!!}
                .filter {!it.startsWith("com.model_store.modern.") && it != ModelStoreApplication::class.java.name}.forEach {name ->
                    assertTrue(ctx.getBeanNamesForType(Class.forName(name)).isEmpty(),"Legacy bean type remained: $name")
                }
        }
    }

    private fun routes(ctx: ConfigurableApplicationContext, modern: Boolean): List<Route> {
        fun record(paths: Set<String>, verbs: Set<RequestMethod>, conditions: String, method: HandlerMethod) =
            paths.flatMap {path -> (verbs.map {it.name}.ifEmpty {listOf("ANY")}).map {verb ->
                Route(verb, path, "${method.beanType.name}#${method.method.name}", method.method.toGenericString(), conditions,
                    method.method.parameters.map { p ->
                        val binding = p.getAnnotation(RequestParam::class.java)?.let {
                            "query ${it.name.ifEmpty {it.value}.ifEmpty {p.name}} required=${it.required}" +
                                if (it.defaultValue == ValueConstants.DEFAULT_NONE) "" else " default=${it.defaultValue}"
                        } ?: p.getAnnotation(PathVariable::class.java)?.let {"path ${it.name.ifEmpty {it.value}.ifEmpty {p.name}}"}
                          ?: p.getAnnotation(RequestHeader::class.java)?.let {"header ${it.name.ifEmpty {it.value}.ifEmpty {p.name}} required=${it.required}"}
                          ?: p.getAnnotation(RequestBody::class.java)?.let {"body required=${it.required}"}
                          ?: p.getAnnotation(RequestPart::class.java)?.let {"part ${it.name.ifEmpty {it.value}.ifEmpty {p.name}} required=${it.required}"}
                          ?: "implicit/principal ${p.name}"
                        "$binding: ${p.parameterizedType.typeName}"
                    })
            }}
        return if (modern) {
            ctx.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping::class.java)
                .handlerMethods.flatMap {(m,h) -> record(m.patternValues, m.methodsCondition.methods,
                    "params=${m.paramsCondition};headers=${m.headersCondition};consumes=${m.consumesCondition};produces=${m.producesCondition}", h)}
        } else {
            ctx.getBean(org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping::class.java)
                .handlerMethods.flatMap {(m,h) -> record(m.patternsCondition.patterns.map {it.patternString}.toSet(), m.methodsCondition.methods,
                    "params=${m.paramsCondition};headers=${m.headersCondition};consumes=${m.consumesCondition};produces=${m.producesCondition}", h)}
        }
    }
}
