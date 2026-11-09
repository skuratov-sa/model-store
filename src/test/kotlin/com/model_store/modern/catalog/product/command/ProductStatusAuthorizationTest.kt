package com.model_store.modern.catalog.product.command

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.ProductState
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.infrastructure.JdbcCurrentAdminLock
import com.model_store.modern.identity.participant.infrastructure.ParticipantEntity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.mockito.Mockito.mock
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionException
import org.springframework.aop.support.AopUtils
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.*
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.*
import java.util.concurrent.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
])
@ActiveProfiles("modern")
class ProductStatusAuthorizationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var commands: ProductCommands
    @Autowired lateinit var access: CurrentAdminLock
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var emf: EntityManagerFactory
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `stale ADMIN access token cannot change agent product status after role revoke`() {
        val admin = participant("ADMIN")
        val owner = participant(agent = true)
        val product = product(owner)
        val jwt = token(admin)
        jdbc.update("UPDATE participant SET role='USER' WHERE id=?", admin)
        val response = status(product, jwt)
        assertEquals(403, response.statusCode(), response.body())
        assertTrue(response.body().contains("ACCESS_DENIED"), response.body())
        assertEquals("ACTIVE", state(product))
    }

    @Test
    fun `real JWT rejects revoked status agent flag missing caller and wrong token kinds`() {
        val owner = participant()
        val product = product(owner)
        for (assignment in listOf("status='BLOCKED'", "status='DELETED'", "status='WAITING_VERIFY'", "is_agent=true")) {
            val admin = participant("ADMIN")
            val jwt = token(admin)
            jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
            val response = status(product, jwt)
            assertEquals(403, response.statusCode(), "$assignment ${response.body()}")
            assertTrue(response.body().contains("ACCESS_DENIED"))
            assertEquals("ACTIVE", state(product))
        }
        val admin = participant("ADMIN")
        for ((jwt, expected) in listOf(token(admin, "agent_access") to 403,
            token(admin, "refresh") to 401, token(admin, role = "USER") to 403,
            token(Long.MAX_VALUE) to 403)) {
            val response = status(product, jwt)
            assertEquals(expected, response.statusCode(), response.body())
            assertEquals("ACTIVE", state(product))
        }
    }

    @Test
    fun `absent invalid expired and incomplete JWT cannot reach status command`() {
        val admin = participant("ADMIN")
        val product = product(participant(agent = true))
        val before = jdbc.queryForMap("SELECT * FROM product WHERE id=?", product)
        val valid = token(admin)
        val segments = valid.split('.')
        val invalidSignature = segments.take(2).joinToString(".") + "." +
            (if (segments[2][0] == 'A') 'B' else 'A') + segments[2].drop(1)
        val missingId = Jwts.builder().claim("role", "ADMIN").claim("type", "access")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
        val missingRole = Jwts.builder().claim("id", admin).claim("type", "access")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
        val invalidAgent = Jwts.builder().claim("id", admin).claim("role", "ADMIN").claim("type", "agent_access")
            .expiration(Date.from(Instant.now().plusSeconds(3600))).signWith(key).compact()
        for (jwt in listOf(null, "invalid", invalidSignature, missingId, missingRole, invalidAgent,
            token(admin, expiration = Instant.now().minusSeconds(120)))) {
            val response = status(product, jwt)
            assertEquals(401, response.statusCode(), response.body())
            assertEquals(before, jdbc.queryForMap("SELECT * FROM product WHERE id=?", product))
        }
    }

    @Test
    fun `normal ACCESS admin manages ordinary and agent owners on either side of caller ID`() {
        for (agent in listOf(false, true)) {
            val lower = participant(agent = agent)
            val admin = participant("ADMIN")
            val higher = participant(agent = agent)
            for (owner in listOf(lower, higher, admin)) {
                val product = product(owner)
                // Target owner state is not a prerequisite of the legacy status operation.
                if (owner != admin) jdbc.update("UPDATE participant SET status='DELETED' WHERE id=?", owner)
                val response = status(product, token(admin))
                assertEquals(200, response.statusCode(), response.body())
                assertEquals("BLOCKED", state(product))
                assertEquals(owner, jdbc.queryForObject("SELECT participant_id FROM product WHERE id=?", Long::class.java, product))
            }
        }
    }

    @Test
    fun `status does not lock ordinary or agent owner in either ID order`() {
        for (agent in listOf(false, true)) {
            val lower = participant(agent = agent)
            val admin = participant("ADMIN")
            val higher = participant(agent = agent)
            for (owner in listOf(lower, higher)) {
                val product = product(owner)
                jdbc.dataSource!!.connection.use { connection ->
                    connection.autoCommit = false
                    connection.prepareStatement("SELECT id FROM participant WHERE id=? FOR UPDATE").use {
                        it.setLong(1, owner); it.executeQuery().close()
                    }
                    try {
                        val response = status(product, token(admin))
                        assertEquals(200, response.statusCode(), response.body())
                        assertEquals("BLOCKED", state(product))
                    } finally { connection.rollback() }
                }
            }
        }
    }

    @Test
    fun `status preserves expiry media categories and giveaway settings with established errors`() {
        val admin = participant("ADMIN")
        val product = product(participant())
        jdbc.update("UPDATE product SET expiration_date=now()-interval '1 day' WHERE id=?", product)
        val image = jdbc.queryForObject("""INSERT INTO image(filename,tag,status,entity_id,uploaded_by)
            VALUES ('status162.jpg','PRODUCT','ACTIVE',?,?) RETURNING id""", Long::class.java, product, admin)!!
        val category = jdbc.queryForObject("INSERT INTO category(name) VALUES (?) RETURNING id", Long::class.java, "status162-${UUID.randomUUID()}")!!
        jdbc.update("INSERT INTO product_category(product_id,category_id) VALUES (?,?)", product, category)
        val expired = jdbc.queryForObject("SELECT expiration_date FROM product WHERE id=?", java.sql.Timestamp::class.java, product)
        assertEquals(200, status(product, token(admin)).statusCode())
        assertEquals(expired, jdbc.queryForObject("SELECT expiration_date FROM product WHERE id=?", java.sql.Timestamp::class.java, product))
        assertEquals(200, status(product, token(admin), "ACTIVE").statusCode())
        assertTrue(jdbc.queryForObject("SELECT expiration_date > now() FROM product WHERE id=?", Boolean::class.java, product)!!)
        val renewed = jdbc.queryForObject("SELECT expiration_date FROM product WHERE id=?", java.sql.Timestamp::class.java, product)
        assertEquals(200, status(product, token(admin), "ACTIVE").statusCode())
        assertEquals(renewed, jdbc.queryForObject("SELECT expiration_date FROM product WHERE id=?", java.sql.Timestamp::class.java, product))
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status::text FROM image WHERE id=?", String::class.java, image))
        assertEquals(category, jdbc.queryForObject("SELECT category_id FROM product_category WHERE product_id=?", Long::class.java, product))
        val invalid = status(product, token(admin), "AWAITING_GIVEAWAY")
        assertEquals(400, invalid.statusCode())
        assertTrue(invalid.body().contains("INVALID_REQUEST"))
        val missing = status(Long.MAX_VALUE, token(admin))
        assertEquals(404, missing.statusCode())
        assertTrue(missing.body().contains("PRODUCT_NOT_FOUND"))
        jdbc.update("""UPDATE product SET availability='GIVEAWAY',giveaway_enabled=true,
            giveaway_telegram_url='https://t.me/status162',giveaway_start_at=now()-interval '1 hour',
            giveaway_end_at=now()+interval '1 day',giveaway_winners_count=2,giveaway_rules='rules',giveaway_home_text='home'
            WHERE id=?""", product)
        val settings = jdbc.queryForMap("""SELECT giveaway_telegram_url,giveaway_start_at,giveaway_end_at,
            giveaway_winners_count,giveaway_rules,giveaway_home_text FROM product WHERE id=?""", product)
        assertEquals(200, status(product, token(admin)).statusCode())
        assertEquals(false, jdbc.queryForObject("SELECT giveaway_enabled FROM product WHERE id=?", Boolean::class.java, product))
        assertEquals(settings, jdbc.queryForMap("""SELECT giveaway_telegram_url,giveaway_start_at,giveaway_end_at,
            giveaway_winners_count,giveaway_rules,giveaway_home_text FROM product WHERE id=?""", product))
        assertEquals(200, status(product, token(admin), "DELETED").statusCode())
        assertEquals(404, status(product, token(admin), "ACTIVE").statusCode())
    }

    @Test
    fun `owner port requires actual caller writable JPA transaction and expected resources`() {
        val admin = participant("ADMIN")
        val product = product(participant())
        assertTrue(AopUtils.isAopProxy(commands))
        assertTrue(AopUtils.isAopProxy(access))
        assertThrows(IllegalTransactionStateException::class.java) { access.lockActiveAdmin(admin) }
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            JdbcCurrentAdminLock(emf, jdbc.dataSource!!).lockActiveAdmin(admin)
        }
        assertThrows(IllegalTransactionStateException::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { access.lockActiveAdmin(admin) }
        }
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            TransactionTemplate(txManager).apply { isReadOnly = true }.executeWithoutResult { access.lockActiveAdmin(admin) }
        }
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            TransactionTemplate(txManager).apply { isReadOnly = true }.executeWithoutResult {
                commands.changeStatus(product, ProductState.BLOCKED, admin)
            }
        }
        assertThrows(TransactionException::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult {
                commands.changeStatus(product, ProductState.BLOCKED, admin)
            }
        }
        assertEquals("ACTIVE", state(product))
        TransactionTemplate(txManager).executeWithoutResult {
            access.lockActiveAdmin(admin)
            val foreignEmf = JdbcCurrentAdminLock(mock(EntityManagerFactory::class.java), jdbc.dataSource!!)
            assertThrows(CurrentAdminTransactionRequired::class.java) { foreignEmf.lockActiveAdmin(admin) }
            val foreignDs = JdbcCurrentAdminLock(emf, DriverManagerDataSource())
            assertThrows(CurrentAdminTransactionRequired::class.java) { foreignDs.lockActiveAdmin(admin) }
        }
        assertEquals(1, ProductCommands::class.java.methods.count { it.name == "changeStatus" })
        assertEquals(3, ProductCommands::class.java.methods.single { it.name == "changeStatus" }.parameterCount)
    }

    @Test
    fun `fresh database tuple wins over stale managed caller and external error rolls back status`() {
        val admin = participant("ADMIN")
        val product = product(participant())
        val denied = assertThrows(ProductCommandFailure::class.java) {
            TransactionTemplate(txManager).executeWithoutResult {
                assertEquals("ADMIN", entityManager.find(ParticipantEntity::class.java, admin).role.name)
                jdbc.dataSource!!.connection.use { connection ->
                    connection.prepareStatement("UPDATE participant SET role='USER' WHERE id=?").use { statement ->
                        statement.setLong(1, admin)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                // Exercise the actual command and mandatory-port proxies with an old managed entity.
                commands.changeStatus(product, ProductState.BLOCKED, admin)
            }
        }
        assertEquals(FailureKind.FORBIDDEN, denied.kind)
        assertEquals("ACCESS_DENIED", denied.code)
        assertEquals("ACTIVE", state(product))
        jdbc.update("UPDATE participant SET role='ADMIN' WHERE id=?", admin)
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(txManager).executeWithoutResult {
                commands.changeStatus(product, ProductState.BLOCKED, admin)
                assertEquals("BLOCKED", state(product))
                error("caller failure")
            }
        }
        assertEquals("ACTIVE", state(product))
        assertEquals(200, status(product, token(admin)).statusCode())
    }

    @Test
    fun `revoke first serializes real HTTP status after role status and agent changes`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val admin = participant("ADMIN")
                val product = product(participant(agent = true))
                jdbc.dataSource!!.connection.use { connection ->
                    connection.autoCommit = false
                    val pid = connection.createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) } }
                    connection.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
                        it.setLong(1, admin); it.executeUpdate()
                    }
                    val request = pool.submit(Callable { status(product, token(admin)) })
                    try {
                        awaitBlockedBy(pid, "FROM participant")
                        assertFalse(request.isDone)
                        assertEquals("ACTIVE", state(product))
                        connection.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(403, response.statusCode(), response.body())
                        assertTrue(response.body().contains("ACCESS_DENIED"))
                        assertEquals("ACTIVE", state(product))
                        assertEquals(true, jdbc.queryForObject("SELECT $assignment FROM participant WHERE id=?", Boolean::class.java, admin))
                    } finally { connection.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `status first retains caller lock while real HTTP waits for product and revoke waits for commit`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val admin = participant("ADMIN")
                val product = product(participant())
                jdbc.dataSource!!.connection.use { connection ->
                    connection.autoCommit = false
                    val pid = connection.createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) } }
                    connection.prepareStatement("SELECT id FROM product WHERE id=? FOR UPDATE").use {
                        it.setLong(1, product); it.executeQuery().close()
                    }
                    val request = pool.submit(Callable { status(product, token(admin)) })
                    try {
                        val statusPid = awaitBlockedBy(pid, "FROM product")
                        assertFalse(request.isDone)
                        val revokerPid = ArrayBlockingQueue<Int>(1)
                        val revoke = pool.submit(Callable {
                            jdbc.dataSource!!.connection.use { revoker ->
                                val backendPid = revoker.createStatement().use { statement ->
                                    statement.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
                                }
                                revokerPid.put(backendPid)
                                revoker.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
                                    it.setLong(1, admin); it.executeUpdate()
                                }
                            }
                        })
                        val expectedRevoker = requireNotNull(revokerPid.poll(10, TimeUnit.SECONDS))
                        assertEquals(expectedRevoker, awaitBlockedBy(statusPid, "UPDATE participant"))
                        assertFalse(revoke.isDone)
                        assertEquals("ACTIVE", state(product))
                        connection.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(200, response.statusCode(), response.body())
                        assertEquals(1, revoke.get(10, TimeUnit.SECONDS))
                        assertEquals(true, jdbc.queryForObject("SELECT $assignment FROM participant WHERE id=?", Boolean::class.java, admin))
                        assertEquals("BLOCKED", state(product))
                        assertEquals(403, status(product, token(admin), "ACTIVE").statusCode())
                        assertEquals("BLOCKED", state(product))
                    } finally { connection.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun awaitBlockedBy(pid: Int, expectedQuery: String): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForList("""SELECT pid FROM pg_stat_activity
                WHERE ? = ANY(pg_blocking_pids(pid)) AND query LIKE ?""", Int::class.java, pid, "%$expectedQuery%")
            if (blocked.isNotEmpty()) return requireNotNull(blocked.first())
            Thread.sleep(20)
        }
        throw AssertionError("No database session blocked by $pid")
    }

    private fun participant(role: String = "USER", agent: Boolean = false): Long {
        val login = "status162-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,'hash','ACTIVE',?::participant_role,?) RETURNING id""",
            Long::class.java, login, "$login@example.test", role, agent)!!
    }

    private fun product(owner: Long): Long = jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,availability)
        VALUES (?,10,'RUB',?,'EXTERNAL_PRODUCT') RETURNING id""", Long::class.java,
        "Status162-${UUID.randomUUID()}", owner)!!

    private fun state(product: Long) = jdbc.queryForObject("SELECT status::text FROM product WHERE id=?", String::class.java, product)

    private fun status(product: Long, jwt: String?, state: String = "BLOCKED"): HttpResponse<String> =
        HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/product/$product?productStatus=$state"))
                .timeout(java.time.Duration.ofSeconds(15))
                .apply { if (jwt != null) header("Authorization", "Bearer $jwt") }
                .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())
        }

    private fun token(id: Long, type: String = "access", role: String = "ADMIN",
                      expiration: Instant = Instant.now().plusSeconds(3600)): String = Jwts.builder()
        .claim("id", id).claim("login", "status162").claim("role", role).claim("type", type)
        .apply { if (type == "agent_access") claim("issuedBy", "admin") }
        .expiration(Date.from(expiration)).signWith(key).compact()

    companion object {
        private val postgres = EmbeddedPostgres.start()
        private val key = ClassPathResource("keys/test_private_key.pem").inputStream.bufferedReader().use { it.readText() }
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s+"), "")
            .let { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(it))) }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
        @JvmStatic @AfterAll fun close() = postgres.close()
    }
}
