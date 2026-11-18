package com.model_store.modern.identity.participant

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.identity.participant.domain.*
import com.model_store.modern.shared.domain.*
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.infrastructure.JdbcAdminParticipantStatusLock
import com.model_store.modern.identity.participant.infrastructure.ParticipantEntity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import io.jsonwebtoken.Jwts
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ClassPathResource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.CannotCreateTransactionException
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
import javax.sql.DataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "app.public-key-path=keys/test_public_key.pem", "app.private-key-path=keys/test_private_key.pem",
    "logging.level.org.springframework.web=WARN",
    "logging.level.org.springframework.http=WARN", "logging.level.org.springframework.security=WARN",
])
@ActiveProfiles("modern")
class ParticipantStatusAuthorizationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var commands: BlockParticipant
    @Autowired lateinit var access: AdminParticipantStatusLock
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var decoder: JwtDecoder
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Autowired lateinit var store: ParticipantStore

    @Test
    fun `real HTTP login issued ACCESS is denied unchanged after each current right is revoked`() {
        for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
            val admin = participant("ADMIN")
            val access = issuedAccess(admin)
            val issuedClaims = decoder.decode(access)
            assertEquals(admin, (issuedClaims.claims["id"] as Number).toLong())
            assertEquals("access", issuedClaims.getClaimAsString("type"))
            assertEquals("ADMIN", issuedClaims.getClaimAsString("role"))
            val initialTarget = participant()
            assertEquals(200, status(initialTarget, access).statusCode())
            assertEquals("BLOCKED", state(initialTarget))

            val lowerTarget = participant()
            val higherCaller = participant("ADMIN")
            // This second real issuer token also exercises caller > target after revoke.
            val higherAccess = issuedAccess(higherCaller)
            val target = participant()
            val before = row(target)
            jdbc.update("UPDATE participant SET $assignment WHERE id IN (?,?)", admin, higherCaller)
            for ((caller, jwt, foreign) in listOf(Triple(admin, access, target), Triple(higherCaller, higherAccess, lowerTarget))) {
                for (id in listOf(foreign, caller, Long.MAX_VALUE)) {
                    val response = status(id, jwt) // Same immutable string returned by /auth/login.
                    assertEquals(403, response.statusCode(), "$assignment ${response.body()}")
                    assertTrue(response.body().contains("ACCESS_DENIED"))
                }
            }
            assertEquals(before, row(target))
            assertEquals("ACTIVE", state(lowerTarget))
            assertEquals("ADMIN", decoder.decode(access).getClaimAsString("role"))
        }
    }

    @Test
    fun `synthetically signed stale claims and agent delegation cannot authorize status`() {
        for (assignment in listOf("role='USER'", "status='BLOCKED'", "status='DELETED'", "status='WAITING_VERIFY'", "is_agent=true")) {
            val admin = participant("ADMIN")
            val target = participant()
            val jwt = token(admin) // Signed while database caller is still ADMIN.
            val before = jdbc.queryForMap("SELECT * FROM participant WHERE id=?", target)
            jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
            for (id in listOf(target, admin, Long.MAX_VALUE)) {
                val response = status(id, jwt)
                assertEquals(403, response.statusCode(), "$assignment ${response.body()}")
                assertTrue(response.body().contains("ACCESS_DENIED"))
            }
            assertEquals(before, jdbc.queryForMap("SELECT * FROM participant WHERE id=?", target))
            assertThrows(ParticipantAdminForbidden::class.java) { commands.execute(Actor(admin, "admin", "ADMIN"), target) }
        }
        val admin = participant("ADMIN")
        val target = participant()
        for ((jwt, expected) in listOf(token(admin, "agent_access") to 403, token(admin, "refresh") to 401,
            token(admin, role = "USER") to 403, token(Long.MAX_VALUE) to 403, null to 401, "invalid" to 401)) {
            val response = status(target, jwt)
            assertEquals(expected, response.statusCode(), response.body())
            assertEquals("ACTIVE", state(target))
        }
        for (actor in listOf(Actor(admin, "admin", "ADMIN", TokenType.AGENT_ACCESS),
            Actor(null, "admin", "ADMIN"), Actor(0, "admin", "ADMIN"), Actor(-1, "admin", "ADMIN"), Actor(admin, "admin", "USER"))) {
            assertThrows(ParticipantAdminForbidden::class.java) { commands.execute(actor, target) }
        }
    }

    @Test
    fun `active targets including agent and self preserve status contract`() {
        val lower = participant()
        val admin = participant("ADMIN")
        val higher = participant(agent = true)
        for (target in listOf(lower, higher, admin)) {
            val before = row(target)
            val response = status(target, token(admin))
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("", response.body())
            assertEquals(before + ("status" to "BLOCKED"), row(target))
        }
        val caller = participant("ADMIN")
        for (targetStatus in listOf("BLOCKED", "DELETED", "WAITING_VERIFY")) {
            val target = participant()
            jdbc.update("UPDATE participant SET status=?::participant_status WHERE id=?", targetStatus, target)
            val response = status(target, token(caller))
            assertEquals(404, response.statusCode(), response.body())
            assertTrue(response.body().contains("NOT_FOUND"))
            assertEquals(targetStatus, state(target))
        }
        assertEquals(404, status(Long.MAX_VALUE, token(caller)).statusCode())
    }

    @Test
    fun `fresh managed target and caller and full outer rollback`() {
        val admin = participant("ADMIN")
        val target = participant()
        assertThrows(ParticipantAdminForbidden::class.java) {
            TransactionTemplate(txManager).executeWithoutResult {
                entityManager.find(ParticipantEntity::class.java, admin)
                independentUpdate(admin, "role='USER'")
                commands.execute(Actor(admin, "admin", "ADMIN"), target)
            }
        }
        jdbc.update("UPDATE participant SET role='ADMIN' WHERE id=?", admin)
        assertThrows(ParticipantStatusUnavailable::class.java) {
            TransactionTemplate(txManager).executeWithoutResult {
                entityManager.find(ParticipantEntity::class.java, target)
                independentUpdate(target, "status='DELETED'")
                commands.execute(Actor(admin, "admin", "ADMIN"), target)
            }
        }
        jdbc.update("UPDATE participant SET status='ACTIVE' WHERE id=?", target)
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(txManager).executeWithoutResult {
                commands.execute(Actor(admin, "admin", "ADMIN"), target)
                jdbc.update("UPDATE participant SET full_name='rollback' WHERE id=?", admin)
                error("outer failure")
            }
        }
        assertEquals("ACTIVE", state(target))
        assertNull(jdbc.queryForObject("SELECT full_name FROM participant WHERE id=?", String::class.java, admin))
        assertEquals(200, status(target, token(admin)).statusCode())
    }

    @Test
    fun `command and mandatory port require writable expected JPA resources`() {
        val target = participant() // Resource guards must precede even the LOWER target's SQL.
        val admin = participant("ADMIN")
        val actor = Actor(admin, "admin", "ADMIN")
        assertTrue(AopUtils.isAopProxy(commands))
        assertTrue(AopUtils.isAopProxy(access))
        assertThrows(IllegalTransactionStateException::class.java) { access.lockActiveAdminAndTarget(admin, target) }
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            BlockParticipant(store, JdbcAdminParticipantStatusLock(emf, jdbc.dataSource!!)).execute(actor, target)
        }
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            TransactionTemplate(txManager).apply { isReadOnly = true }.executeWithoutResult { commands.execute(actor, target) }
        }
        val wrongManager = assertThrows(CannotCreateTransactionException::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { commands.execute(actor, target) }
        }
        assertInstanceOf(IllegalStateException::class.java, wrongManager.cause)
        // Non-proxied invocation cannot evade the resource guard, including JDBC-only tx.
        val untouchedStore = mock(ParticipantStore::class.java)
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult {
                BlockParticipant(untouchedStore, JdbcAdminParticipantStatusLock(emf, jdbc.dataSource!!)).execute(actor, target)
            }
        }
        verifyNoInteractions(untouchedStore)
        val untouchedDs = mock(DataSource::class.java)
        assertThrows(CurrentAdminTransactionRequired::class.java) {
            JdbcAdminParticipantStatusLock(emf, untouchedDs).lockActiveAdminAndTarget(admin, target)
        }
        verifyNoInteractions(untouchedDs)
        TransactionTemplate(txManager).executeWithoutResult {
            for (foreign in listOf(JdbcAdminParticipantStatusLock(mock(EntityManagerFactory::class.java), jdbc.dataSource!!),
                JdbcAdminParticipantStatusLock(emf, DriverManagerDataSource()))) {
                assertThrows(CurrentAdminTransactionRequired::class.java) { BlockParticipant(store, foreign).execute(actor, target) }
            }
        }
        assertEquals("ACTIVE", state(target))
    }

    @Test
    fun `resource guard precedes SQL even when lower target would block`() {
        val target = participant()
        val admin = participant("ADMIN")
        val pool = Executors.newSingleThreadExecutor()
        try {
            jdbc.dataSource!!.connection.use { gate ->
                gate.autoCommit = false
                update(gate, target, "full_name='held'")
                try {
                    val result = pool.submit(Callable {
                        assertThrows(CurrentAdminTransactionRequired::class.java) {
                            TransactionTemplate(txManager).apply { isReadOnly = true }.executeWithoutResult {
                                commands.execute(Actor(admin, "admin", "ADMIN"), target)
                            }
                        }
                    })
                    result.get(5, TimeUnit.SECONDS)
                    assertEquals("ACTIVE", state(target))
                } finally { gate.rollback() }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `authorization precedes absent and unavailable target errors in either ID order`() {
        val lower = participant()
        val admin = participant("ADMIN")
        val higher = participant()
        jdbc.update("UPDATE participant SET status='DELETED' WHERE id IN (?,?)", lower, higher)
        val jwt = token(admin)
        jdbc.update("UPDATE participant SET role='USER' WHERE id=?", admin)
        for (target in listOf(lower, higher, 0L, Long.MAX_VALUE, admin)) {
            val response = status(target, jwt)
            assertEquals(403, response.statusCode(), response.body())
            assertTrue(response.body().contains("ACCESS_DENIED"))
        }
        jdbc.update("UPDATE participant SET role='ADMIN' WHERE id=?", admin)
        for (target in listOf(lower, higher, 0L, Long.MAX_VALUE)) {
            val response = status(target, jwt)
            assertEquals(404, response.statusCode(), response.body())
            assertTrue(response.body().contains("NOT_FOUND"))
        }
    }

    @Test
    fun `runtime schema evidence includes current channel agent migration`() {
        assertEquals(1, jdbc.queryForObject("""SELECT count(*) FROM flyway_schema_history
            WHERE version='1.29' AND success""", Int::class.java))
        Files.createDirectories(Path.of("build/status046"))
        Files.writeString(Path.of("build/status046/schema-current.txt"),
            jdbc.queryForList("SELECT installed_rank,version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank").joinToString("\n") +
                "\nparticipant columns:\n" + jdbc.queryForList("""SELECT column_name,data_type,udt_name FROM information_schema.columns
                    WHERE table_schema='public' AND table_name='participant' ORDER BY ordinal_position""").joinToString("\n"))
    }

    @Test
    fun `revoke first real HTTP waits for actual blocker in both ID orders`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (order in listOf("caller-first", "target-first", "self")) for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val first = participant("ADMIN")
                val second = participant("ADMIN")
                val admin = if (order == "target-first") second else first
                val target = when (order) { "caller-first" -> second; "target-first" -> first; else -> admin }
                val jwt = token(admin)
                evidence("revoke-first order=$order assignment=$assignment caller=$admin target=$target")
                jdbc.dataSource!!.connection.use { connection ->
                    connection.autoCommit = false
                    val blocker = pid(connection)
                    update(connection, admin, assignment)
                    val request = pool.submit(Callable { status(target, jwt) })
                    try {
                        val worker = awaitBlockedBy(blocker, "participant")
                        assertTrue(worker != blocker)
                        assertFalse(request.isDone)
                        connection.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(403, response.statusCode(), response.body())
                        assertTrue(response.body().contains("ACCESS_DENIED"))
                        assertEquals(if (order == "self" && assignment == "status='BLOCKED'") "BLOCKED" else "ACTIVE", state(target))
                    } finally { connection.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `mutation first real HTTP retains caller and target until commit in both ID orders`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (order in listOf("caller-first", "target-first", "self")) for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val first = participant("ADMIN")
                val second = participant("ADMIN")
                val admin = if (order == "target-first") second else first
                val target = when (order) { "caller-first" -> second; "target-first" -> first; else -> admin }
                val jwt = token(admin)
                evidence("action-first order=$order assignment=$assignment caller=$admin target=$target")
                // Isolated test-only trigger pauses the actual owner write after both row locks.
                jdbc.execute("""CREATE OR REPLACE FUNCTION status046_gate() RETURNS trigger AS ${'$'}${'$'}
                    BEGIN IF NEW.id=$target THEN PERFORM pg_advisory_xact_lock(904600000+NEW.id); END IF;
                    RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql""")
                jdbc.execute("CREATE TRIGGER status046_gate BEFORE UPDATE ON participant FOR EACH ROW EXECUTE FUNCTION status046_gate()")
                try {
                    jdbc.dataSource!!.connection.use { gate ->
                        gate.autoCommit = false
                        gate.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(${904600000 + target})") }
                        val request = pool.submit(Callable { status(target, jwt) })
                        try {
                            val worker = awaitBlockedBy(pid(gate), "update participant")
                            val revokerPid = ArrayBlockingQueue<Int>(1)
                            val revoke = pool.submit(Callable {
                                jdbc.dataSource!!.connection.use { connection ->
                                    revokerPid.put(pid(connection))
                                    update(connection, admin, assignment)
                                }
                            })
                            assertEquals(requireNotNull(revokerPid.poll(10, TimeUnit.SECONDS)), awaitBlockedBy(worker, "UPDATE participant"))
                            assertFalse(request.isDone)
                            assertFalse(revoke.isDone)
                            gate.commit()
                            val response = request.get(10, TimeUnit.SECONDS)
                            assertEquals(200, response.statusCode(), response.body())
                            assertEquals(1, revoke.get(10, TimeUnit.SECONDS))
                            assertEquals("BLOCKED", state(target))
                            assertEquals(403, status(participant(), jwt).statusCode())
                        } finally { gate.rollback() }
                    }
                } finally {
                    jdbc.execute("DROP TRIGGER status046_gate ON participant")
                    jdbc.execute("DROP FUNCTION status046_gate()")
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `target state after real lock wait determines unavailable result in both ID orders`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (callerFirst in listOf(true, false)) for (assignment in listOf("status='DELETED'", "status='BLOCKED'", "status='WAITING_VERIFY'")) {
                val first = participant("ADMIN")
                val second = participant("ADMIN")
                val admin = if (callerFirst) first else second
                val target = if (callerFirst) second else first
                evidence("target-first callerFirst=$callerFirst assignment=$assignment caller=$admin target=$target")
                jdbc.dataSource!!.connection.use { connection ->
                    connection.autoCommit = false
                    update(connection, target, assignment)
                    val request = pool.submit(Callable { status(target, token(admin)) })
                    try {
                        awaitBlockedBy(pid(connection), "participant")
                        connection.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(404, response.statusCode(), response.body())
                        assertTrue(response.body().contains("NOT_FOUND"))
                        assertEquals(true, jdbc.queryForObject("SELECT $assignment FROM participant WHERE id=?", Boolean::class.java, target))
                    } finally { connection.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun pid(connection: java.sql.Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
    }
    private fun update(connection: java.sql.Connection, id: Long, assignment: String) =
        connection.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
            it.setLong(1, id); it.executeUpdate()
        }
    private fun awaitBlockedBy(blocker: Int, query: String): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForList("""SELECT pid,query,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity
                WHERE ? = ANY(pg_blocking_pids(pid)) AND query LIKE ?""", blocker, "%$query%")
            if (blocked.isNotEmpty()) {
                evidence("blocker=$blocker worker=${blocked.first()}")
                return (blocked.first().getValue("pid") as Number).toInt()
            }
            Thread.onSpinWait()
        }
        throw AssertionError("No worker blocked by actual backend $blocker with SQL $query")
    }

    @Test
    fun `opposing admin requests share ascending frontier without deadlock`() {
        val lower = participant("ADMIN")
        val higher = participant("ADMIN")
        val pool = Executors.newFixedThreadPool(2)
        try {
            jdbc.dataSource!!.connection.use { gate ->
                gate.autoCommit = false
                gate.prepareStatement("SELECT id FROM participant WHERE id=? FOR UPDATE").use {
                    it.setLong(1, lower); it.executeQuery().close()
                }
                val a = pool.submit(Callable { status(higher, token(lower)) })
                val b = pool.submit(Callable { status(lower, token(higher)) })
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var workers = emptyList<Map<String, Any?>>()
                    while (System.nanoTime() < deadline && workers.size != 2) {
                        // Row-lock wait queues can put the second worker behind the first waiter.
                        workers = jdbc.queryForList("""WITH RECURSIVE waiting(pid) AS (
                            SELECT pid FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))
                            UNION SELECT a.pid FROM pg_stat_activity a JOIN waiting w
                                ON w.pid=ANY(pg_blocking_pids(a.pid)))
                            SELECT a.pid,a.query,pg_blocking_pids(a.pid)::text AS blockers
                            FROM pg_stat_activity a JOIN waiting w ON a.pid=w.pid
                            WHERE a.query ILIKE '%participant%'""", pid(gate))
                    }
                    assertEquals(2, workers.size, "Both actual HTTP workers must join the lower participant wait chain")
                    evidence("opposing blocker=${pid(gate)} workers=$workers")
                    gate.commit()
                    val responses = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
                    assertEquals(listOf(200, 403), responses.map { it.statusCode() }.sorted(), responses.map { it.body() }.toString())
                    assertEquals(listOf("ACTIVE", "BLOCKED"), listOf(requireNotNull(state(lower)), requireNotNull(state(higher))).sorted())
                } finally { gate.rollback() }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun independentUpdate(id: Long, assignment: String) {
        jdbc.dataSource!!.connection.use { connection ->
            connection.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
                it.setLong(1, id); assertEquals(1, it.executeUpdate())
            }
        }
    }
    private fun evidence(line: String) {
        Files.createDirectories(Path.of("build/status046"))
        Files.writeString(Path.of("build/status046/concurrency.txt"), "$line\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    private fun row(id: Long) = jdbc.queryForMap("SELECT * FROM participant WHERE id=?", id)
        .mapValues { (k,v) -> if(k == "status") v.toString() else v }
    private fun issuedAccess(id: Long): String {
        val password = "status046-test-password"
        jdbc.update("UPDATE participant SET password=? WHERE id=?", BCryptPasswordEncoder().encode(password), id)
        val mail = jdbc.queryForObject("SELECT mail FROM participant WHERE id=?", String::class.java, id)!!
        return HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/login"))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("mail" to mail, "password" to password))))
                .build(), HttpResponse.BodyHandlers.ofString())
            // Never include login response bodies: they contain the actual issued tokens.
            assertEquals(200, response.statusCode(), "Fixture /auth/login failed")
            val issued = json.readTree(response.body())
            assertEquals(setOf("access_token", "refresh_token"), issued.fieldNames().asSequence().toSet())
            val access = issued.path("access_token").asText()
            assertTrue(access.isNotBlank(), "Issuer returned no ACCESS token")
            access
        }
    }
    private fun state(id: Long) = jdbc.queryForObject("SELECT status::text FROM participant WHERE id=?", String::class.java, id)
    private fun status(id: Long, jwt: String?): HttpResponse<String> =
        HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/participants/$id/status"))
                .timeout(java.time.Duration.ofSeconds(15))
                .apply { if (jwt != null) header("Authorization", "Bearer $jwt") }
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
    private fun participant(role: String = "USER", agent: Boolean = false): Long {
        val login = "status046-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,'hash','ACTIVE',?::participant_role,?) RETURNING id""",
            Long::class.java, login, "$login@example.test", role, agent)!!
    }

    private fun token(id: Long, type: String = "access", role: String = "ADMIN",
                      expiration: Instant = Instant.now().plusSeconds(3600)): String = Jwts.builder()
        .claim("id", id).claim("login", "status046").claim("role", role).claim("type", type)
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
