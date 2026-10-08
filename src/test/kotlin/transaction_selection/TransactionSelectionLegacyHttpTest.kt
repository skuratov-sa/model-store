package transaction_selection

import com.amazonaws.services.s3.AmazonS3
import com.model_store.ModelStoreApplication
import com.model_store.service.ParticipantService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.aop.support.AopUtils
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.reactive.TransactionSynchronizationManager as ReactiveSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationManager as ThreadSynchronizationManager
import jakarta.persistence.EntityManager
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties
import java.util.UUID

@SpringBootTest(classes = [ModelStoreApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["spring.main.web-application-type=reactive"])
@ActiveProfiles("test")
@Import(TransactionSelectionLegacyHttpTest.Probes::class)
class TransactionSelectionLegacyHttpTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var mail: JavaMailSender
    @Autowired lateinit var participantService: ParticipantService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var reactiveProbe: ReactiveProbe
    @Autowired lateinit var jpaProbe: JpaProbe
    @Autowired lateinit var attributes: TransactionAttributeSource
    @Autowired lateinit var context: ApplicationContext
    @Autowired lateinit var client: DatabaseClient
    @Value("\${local.server.port}") private var port: Int = 0

    @BeforeEach
    fun mockMail() {
        `when`(mail.createMimeMessage()).thenAnswer { MimeMessage(Session.getInstance(Properties())) }
        jdbc.execute("CREATE TABLE IF NOT EXISTS transaction_selection_probe (marker text NOT NULL)")
    }

    @Test
    fun `legacy registration reaches transactional proxy over HTTP`() {
        assertTrue(AopUtils.isAopProxy(participantService))
        val email = "tx-${UUID.randomUUID()}@example.test"
        val response = registration(email)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().toLong() > 0)
        assertEquals(1, count("participant", "mail", email))

        val repeat = registration(email)
        assertEquals(404, repeat.statusCode(), repeat.body())
        assertEquals(1, count("participant", "mail", email))
    }

    @Test
    fun `reactive transactions commit and roll back through their proxy`() {
        assertTrue(AopUtils.isAopProxy(reactiveProbe))
        assertTrue(AopUtils.isCglibProxy(reactiveProbe))
        assertEquals(true to false, reactiveProbe.contextKinds().block())
        val commit = "r2dbc-commit-${UUID.randomUUID()}"
        val rollback = "r2dbc-rollback-${UUID.randomUUID()}"
        reactiveProbe.commit(commit).block()
        assertEquals(1, count("transaction_selection_probe", "marker", commit))
        assertThrows(IllegalStateException::class.java) { reactiveProbe.rollback(rollback).block() }
        assertEquals(0, count("transaction_selection_probe", "marker", rollback))
        val fluxCommit = "flux-commit-${UUID.randomUUID()}"
        val fluxRollback = "flux-rollback-${UUID.randomUUID()}"
        assertEquals(listOf(1L), reactiveProbe.fluxCommit(fluxCommit).collectList().block())
        assertEquals(1, count("transaction_selection_probe", "marker", fluxCommit))
        assertThrows(IllegalStateException::class.java) { reactiveProbe.fluxRollback(fluxRollback).collectList().block() }
        assertEquals(0, count("transaction_selection_probe", "marker", fluxRollback))
        val noRollback = "r2dbc-no-rollback-${UUID.randomUUID()}"
        assertThrows(IllegalArgumentException::class.java) { reactiveProbe.noRollback(noRollback).block() }
        assertEquals(1, count("transaction_selection_probe", "marker", noRollback))
    }

    @Test
    fun `synchronous JPA transactions commit and roll back through their proxy`() {
        assertTrue(AopUtils.isAopProxy(jpaProbe))
        assertTrue(AopUtils.isCglibProxy(jpaProbe))
        val commit = "jpa-commit-${UUID.randomUUID()}"
        val rollback = "jpa-rollback-${UUID.randomUUID()}"
        jpaProbe.commit(commit)
        assertEquals(1, count("transaction_selection_probe", "marker", commit))
        assertThrows(IllegalStateException::class.java) { jpaProbe.rollback(rollback) }
        assertEquals(0, count("transaction_selection_probe", "marker", rollback))
        val noRollback = "jpa-no-rollback-${UUID.randomUUID()}"
        assertThrows(IllegalArgumentException::class.java) { jpaProbe.noRollback(noRollback) }
        assertEquals(1, count("transaction_selection_probe", "marker", noRollback))
    }

    @Test
    fun `unqualified annotations resolve to distinct manager names`() {
        assertTrue(context.getBean("connectionFactoryTransactionManager") is R2dbcTransactionManager)
        assertTrue(context.getBean("transactionManager") is JpaTransactionManager)
        val advisors = context.getBeansOfType(BeanFactoryTransactionAttributeSourceAdvisor::class.java)
        assertEquals(1, advisors.size)
        assertSame(attributes, context.getBean(TransactionInterceptor::class.java).transactionAttributeSource)
        val legacy = attributes.getTransactionAttribute(
            com.model_store.service.impl.ParticipantServiceImpl::class.java.getMethod(
                "createParticipant", com.model_store.model.CreateParticipantRequest::class.java,
            ), com.model_store.service.impl.ParticipantServiceImpl::class.java,
        )
        val modern = attributes.getTransactionAttribute(
            com.model_store.modern.seller.transfer.application.TransferUseCases::class.java.getMethod(
                "create", Long::class.javaPrimitiveType,
                com.model_store.modern.seller.transfer.domain.TransferFields::class.java,
            ), com.model_store.modern.seller.transfer.application.TransferUseCases::class.java,
        )
        assertEquals("connectionFactoryTransactionManager", legacy?.qualifier)
        assertEquals("transactionManager", modern?.qualifier)

        val interfaceMethod = ReactiveContract::class.java.getMethod("write", String::class.java, Boolean::class.javaPrimitiveType)
        assertEquals("connectionFactoryTransactionManager",
            attributes.getTransactionAttribute(interfaceMethod, ReactiveContractImpl::class.java)?.qualifier)
        val inheritedMethod = InheritedJpaProbe::class.java.getMethod("write")
        assertEquals("transactionManager",
            attributes.getTransactionAttribute(inheritedMethod, InheritedJpaProbe::class.java)?.qualifier)
        val explicitMethod = com.model_store.modern.identity.participant.application.RegisterParticipant::class.java.getMethod(
            "execute", String::class.java, String::class.java, Int::class.javaObjectType,
        )
        assertEquals("transactionManager",
            attributes.getTransactionAttribute(explicitMethod,
                com.model_store.modern.identity.participant.application.RegisterParticipant::class.java)?.qualifier)
    }

    @Test
    fun `JDK interface proxy keeps reactive transaction advice`() {
        val advisor = context.getBean("org.springframework.transaction.config.internalTransactionAdvisor",
            BeanFactoryTransactionAttributeSourceAdvisor::class.java)
        val proxy = ProxyFactory().apply {
            setTarget(ReactiveContractImpl(client))
            setInterfaces(ReactiveContract::class.java)
            addAdvisor(advisor)
        }.proxy as ReactiveContract
        assertTrue(AopUtils.isJdkDynamicProxy(proxy))
        val commit = "jdk-commit-${UUID.randomUUID()}"
        val rollback = "jdk-rollback-${UUID.randomUUID()}"
        proxy.write(commit, false).block()
        assertEquals(1, count("transaction_selection_probe", "marker", commit))
        assertThrows(IllegalStateException::class.java) { proxy.write(rollback, true).block() }
        assertEquals(0, count("transaction_selection_probe", "marker", rollback))
    }

    private fun registration(email: String): HttpResponse<String> = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/participant"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"mail":"$email","password":"secret","age":21}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun count(table: String, column: String, marker: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Int::class.java, marker)!!

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("!modern")
    class Probes {
        @Bean fun reactiveProbe(client: DatabaseClient) = ReactiveProbe(client)
        @Bean fun jpaProbe(entityManager: EntityManager) = JpaProbe(entityManager)
    }

    open class ReactiveProbe(private val client: DatabaseClient) {
        @Transactional
        open fun contextKinds(): Mono<Pair<Boolean, Boolean>> = ReactiveSynchronizationManager.forCurrentTransaction()
            .map { it.isActualTransactionActive to ThreadSynchronizationManager.isActualTransactionActive() }

        @Transactional
        open fun commit(marker: String): Mono<Void> = insert(marker)

        @Transactional
        open fun rollback(marker: String): Mono<Void> =
            insert(marker).then(Mono.error(IllegalStateException("rollback")))

        @Transactional
        open fun fluxCommit(marker: String): Flux<Long> = insert(marker).thenMany(Flux.just(1L))

        @Transactional
        open fun fluxRollback(marker: String): Flux<Long> =
            insert(marker).thenMany(Flux.error(IllegalStateException("rollback")))

        @Transactional(noRollbackFor = [IllegalArgumentException::class])
        open fun noRollback(marker: String): Mono<Void> =
            insert(marker).then(Mono.error(IllegalArgumentException("commit despite error")))

        private fun insert(marker: String): Mono<Void> = client.sql("INSERT INTO transaction_selection_probe(marker) VALUES (:marker)")
            .bind("marker", marker).fetch().rowsUpdated().then()
    }

    open class JpaProbe(private val entityManager: EntityManager) {
        @Transactional
        open fun commit(marker: String) {
            insert(marker)
        }

        @Transactional
        open fun rollback(marker: String) {
            insert(marker)
            throw IllegalStateException("rollback")
        }

        @Transactional(noRollbackFor = [IllegalArgumentException::class])
        open fun noRollback(marker: String) {
            insert(marker)
            throw IllegalArgumentException("commit despite error")
        }

        private fun insert(marker: String) {
            entityManager.createNativeQuery("INSERT INTO transaction_selection_probe(marker) VALUES (:marker)")
                .setParameter("marker", marker).executeUpdate()
        }
    }

    interface ReactiveContract {
        @Transactional
        fun write(marker: String, fail: Boolean): Mono<Void>
    }

    class ReactiveContractImpl(private val client: DatabaseClient) : ReactiveContract {
        override fun write(marker: String, fail: Boolean): Mono<Void> =
            client.sql("INSERT INTO transaction_selection_probe(marker) VALUES (:marker)")
                .bind("marker", marker).fetch().rowsUpdated().then(
                    if (fail) Mono.error(IllegalStateException("rollback")) else Mono.empty(),
                )
    }

    open class BaseJpaProbe {
        @Transactional
        open fun write() {}
    }

    class InheritedJpaProbe : BaseJpaProbe()

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val port = postgres.port
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "" }
            registry.add("spring.datasource.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { "jdbc:postgresql://localhost:$port/postgres" }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
    }
}
