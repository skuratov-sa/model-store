package com.model_store.modern.catalog.category

import com.amazonaws.services.s3.AmazonS3
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import com.model_store.modern.catalog.category.application.*
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
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.security.oauth2.jwt.JwtDecoder
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
    "logging.level.org.springframework.web=WARN", "logging.level.org.springframework.web.server=WARN",
    "logging.level.com.model_store=WARN",
])
@ActiveProfiles("modern")
class CategoryAdminAuthorizationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var commands: AdminCategoryCommands
    @Autowired lateinit var core: CategoryUseCases
    @Autowired lateinit var decoder: JwtDecoder
    @Autowired lateinit var access: CurrentAdminLock
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var emf: EntityManagerFactory
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender

    @Test
    fun `both real ACCESS commands deny all current DB revocations without writes`() {
        val category = category()
        for (assignment in listOf("role='USER'", "status='BLOCKED'", "status='DELETED'", "status='WAITING_VERIFY'", "is_agent=true")) {
            val admin = participant("ADMIN")
            val jwt = login(admin)
            jdbc.update("UPDATE participant SET $assignment WHERE id=?", admin)
            for (method in listOf("POST", "PUT")) {
                val before = snapshot()
                val response = action(method, jwt, category)
                assertEquals(403, response.statusCode(), "$method $assignment ${response.body()}")
                assertTrue(response.body().contains("ACCESS_DENIED"), response.body())
                assertEquals(before, snapshot())
                // Current authorization also precedes core validation and unknown-ID no-op.
                val invalidOrUnknown = if (method == "POST")
                    action(method, jwt, category, "&parentId=${Long.MAX_VALUE}")
                else action(method, jwt, Long.MAX_VALUE)
                assertEquals(403, invalidOrUnknown.statusCode(), invalidOrUnknown.body())
                assertEquals("ACCESS_DENIED", ObjectMapper().readTree(invalidOrUnknown.body())["code"].asText())
                assertEquals(before, snapshot())
            }
        }
    }

    @Test
    fun `synthetic signed agent ADMIN and invalid principals cannot write or bind admin params`() {
        val admin = participant("ADMIN")
        val category = category()
        // issuedBy satisfies the real decoder: intentionally adversarial, not the ordinary agent issuer.
        for ((jwt, expected) in listOf(token(admin, "agent_access") to 403,
            token(admin, "refresh") to 401, token(admin, role="USER") to 403,
            token(0) to 401, token(-1) to 401, token(Long.MAX_VALUE) to 403,
            null to 401, "invalid" to 401, token(admin, expiration=Instant.now().minusSeconds(120)) to 401)) {
            for (method in listOf("POST", "PUT")) {
                val before = snapshot()
                assertEquals(expected, action(method, jwt, category).statusCode())
                assertEquals(before, snapshot())
            }
        }
        HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            for (method in listOf("POST", "PUT")) {
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/categories?categoryId=bad"))
                    .header("Authorization", "Bearer ${token(admin, "agent_access")}")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(403, response.statusCode(), response.body())
                assertTrue(response.body().contains("ACCESS_DENIED"))
            }
        }
    }

    @Test
    fun `allowed admin preserves public tree query params validation unknown rename and empty responses`() {
        val jwt = login(participant("ADMIN"))
        val created = action("POST", jwt, 0)
        assertEquals(200, created.statusCode(), created.body())
        val parent = created.body().toLong()
        val child = action("POST", jwt, 0, "&parentId=$parent")
        assertEquals(200, child.statusCode(), child.body())
        assertEquals(parent, jdbc.queryForObject("SELECT parent_id FROM category WHERE id=?", Long::class.java, child.body().toLong()))
        val renamed = action("PUT", jwt, parent)
        assertEquals(200, renamed.statusCode(), renamed.body())
        assertEquals("", renamed.body())
        assertEquals("renamed021", jdbc.queryForObject("SELECT name FROM category WHERE id=?", String::class.java, parent))
        val before = snapshot()
        assertEquals(200, action("PUT", jwt, Long.MAX_VALUE).statusCode())
        assertEquals(before, snapshot())
        val invalid = action("POST", jwt, 0, "&parentId=${Long.MAX_VALUE}")
        assertEquals(400, invalid.statusCode(), invalid.body())
        assertTrue(invalid.body().contains("INVALID_REFERENCE"))
        assertEquals(before, snapshot())
        HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            val tree = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/categories")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, tree.statusCode(), tree.body())
            assertTrue(tree.body().contains("\"childs\""))
            assertTrue(tree.body().contains("renamed021"))
            for (query in listOf("", "?name=x&categoryId=bad")) {
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/categories$query"))
                    .header("Authorization", "Bearer $jwt").PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(400, response.statusCode(), response.body())
                assertTrue(response.body().contains("BAD_REQUEST"))
            }
        }
    }

    @Test
    fun `owner port and facade require correct writable JPA transaction and rollback both writes`() {
        val admin = participant("ADMIN")
        val category = category()
        assertTrue(AopUtils.isAopProxy(commands))
        val unproxiedFacade = AdminCategoryCommands(access, core)
        assertThrowsExactly(IllegalTransactionStateException::class.java) { unproxiedFacade.create(admin, "no-tx", null) }
        assertThrowsExactly(IllegalTransactionStateException::class.java) { unproxiedFacade.rename(admin, category, "no-tx") }
        assertThrows(IllegalTransactionStateException::class.java) { access.lockActiveAdmin(admin) }
        assertThrows(CurrentAdminTransactionRequired::class.java) { JdbcCurrentAdminLock(emf, jdbc.dataSource!!).lockActiveAdmin(admin) }
        for (method in listOf("POST", "PUT")) {
            fun command() { if (method == "POST") commands.create(admin, "rollback021", null) else commands.rename(admin, category, "rollback021") }
            val before = snapshot()
            assertThrows(CurrentAdminTransactionRequired::class.java) {
                TransactionTemplate(txManager).apply { isReadOnly=true }.executeWithoutResult { command() }
            }
            assertThrowsExactly(CannotCreateTransactionException::class.java) {
                TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { command() }
            }
            assertThrows(IllegalStateException::class.java) {
                TransactionTemplate(txManager).executeWithoutResult {
                    command()
                    entityManager.flush()
                    assertTrue(jdbc.queryForObject("SELECT count(*) FROM category WHERE name='rollback021'", Int::class.java)!! > 0)
                    error("outer rollback")
                }
            }
            assertEquals(before, snapshot())
        }
        TransactionTemplate(txManager).executeWithoutResult {
            access.lockActiveAdmin(admin)
            assertThrows(CurrentAdminTransactionRequired::class.java) { JdbcCurrentAdminLock(mock(EntityManagerFactory::class.java), jdbc.dataSource!!).lockActiveAdmin(admin) }
            assertThrows(CurrentAdminTransactionRequired::class.java) { JdbcCurrentAdminLock(emf, DriverManagerDataSource()).lockActiveAdmin(admin) }
        }
        assertThrows(IllegalTransactionStateException::class.java) {
            TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { access.lockActiveAdmin(admin) }
        }
        for (id in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertThrows(CurrentAdminForbidden::class.java) { commands.create(id, "forbidden", null) }
            assertThrows(CurrentAdminForbidden::class.java) { commands.rename(id, category, "forbidden") }
        }
    }

    @Test
    fun `stale managed ADMIN cannot override current database tuple for either action`() {
        for (method in listOf("POST", "PUT")) {
            val admin = participant("ADMIN")
            val category = category()
            val before = snapshot()
            assertThrows(CurrentAdminForbidden::class.java) {
                TransactionTemplate(txManager).executeWithoutResult {
                    assertEquals("ADMIN", entityManager.find(ParticipantEntity::class.java, admin).role.name)
                    jdbc.dataSource!!.connection.use { c -> c.prepareStatement("UPDATE participant SET role='USER' WHERE id=?").use { s -> s.setLong(1, admin); s.executeUpdate() } }
                    if (method == "POST") commands.create(admin, "stale", null) else commands.rename(admin, category, "stale")
                }
            }
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun `revoke first blocks both HTTP commands then denies after fresh tuple`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (method in listOf("POST", "PUT")) for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val admin = participant("ADMIN")
                val jwt = login(admin)
                val category = category()
                val before = snapshot()
                jdbc.dataSource!!.connection.use { c ->
                    c.autoCommit=false
                    val pid = backendPid(c)
                    c.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use { it.setLong(1, admin); it.executeUpdate() }
                    val request = pool.submit(Callable { action(method, jwt, category) })
                    try {
                        awaitBlockedBy(pid, "FROM participant", "$method/$assignment")
                        assertFalse(request.isDone)
                        assertEquals(before, snapshot())
                        c.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(403, response.statusCode(), response.body())
                        assertTrue(response.body().contains("ACCESS_DENIED"))
                        assertEquals(before, snapshot())
                    } finally { c.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun `action first retains caller through category write and commit before revocation`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (method in listOf("POST", "PUT")) for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                val admin = participant("ADMIN")
                val jwt = login(admin)
                val category = category()
                jdbc.dataSource!!.connection.use { c ->
                    c.autoCommit=false
                    val pid = backendPid(c)
                    // INSERT and UPDATE both wait here, after locking the caller. Reads remain possible.
                    c.createStatement().use { it.execute("LOCK TABLE category IN SHARE MODE") }
                    val request = pool.submit(Callable { action(method, jwt, category) })
                    try {
                        val actionPid = awaitBlockedBy(pid, "category", "$method/$assignment")
                        val revokerPid = ArrayBlockingQueue<Int>(1)
                        val revoke = pool.submit(Callable {
                            jdbc.dataSource!!.connection.use { r ->
                                revokerPid.put(backendPid(r))
                                r.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use { it.setLong(1, admin); it.executeUpdate() }
                            }
                        })
                        assertEquals(requireNotNull(revokerPid.poll(10, TimeUnit.SECONDS)), awaitBlockedBy(actionPid, "UPDATE participant", "$method/$assignment"))
                        assertFalse(request.isDone)
                        assertFalse(revoke.isDone)
                        c.commit()
                        val response = request.get(10, TimeUnit.SECONDS)
                        assertEquals(200, response.statusCode(), response.body())
                        assertEquals(1, revoke.get(10, TimeUnit.SECONDS))
                        if (method == "POST") assertEquals("created021", jdbc.queryForObject("SELECT name FROM category WHERE id=?", String::class.java, response.body().toLong()))
                        else assertEquals("renamed021", jdbc.queryForObject("SELECT name FROM category WHERE id=?", String::class.java, category))
                        val before = snapshot()
                        assertEquals(403, action(method, jwt, category).statusCode())
                        assertEquals(before, snapshot())
                    } finally { c.rollback() }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun backendPid(c: java.sql.Connection) = c.createStatement().use { it.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) } }
    private fun awaitBlockedBy(pid: Int, expectedQuery: String, scenario: String): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForList("""SELECT pid,query,pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity
                WHERE ? = ANY(pg_blocking_pids(pid)) AND query LIKE ?""", pid, "%$expectedQuery%")
            if (blocked.isNotEmpty()) {
                assertEquals(1, blocked.size)
                val row = blocked.single()
                val sql = row.getValue("query").toString().lowercase().replace(Regex("\\s+"), " ").trim()
                val normalized = (1..4).fold(sql) { text, index -> text.replace("\$$index", "?") }
                when (expectedQuery) {
                    "FROM participant" -> assertEquals("select role::text, status::text, is_agent from participant where id=? for update", normalized)
                    "UPDATE participant" -> assertEquals("update participant set ${scenario.substringAfter('/')} where id=?".lowercase(), normalized)
                    "category" -> assertEquals(if (scenario.startsWith("POST/"))
                        "insert into category (display_order,name,parent_id) values (?,?,?) returning *"
                        else "update category set display_order=?,name=?,parent_id=? where id=?", normalized)
                    else -> error("Unexpected lock query")
                }
                assertEquals("{$pid}", row.getValue("blockers").toString())
                evidence("locks.jsonl", mapOf("scenario" to scenario, "expectedBlocker" to pid, "expectedQuery" to expectedQuery, "observed" to row))
                return (row.getValue("pid") as Number).toInt()
            }
            Thread.sleep(20)
        }
        throw AssertionError("No database session blocked by $pid")
    }

    private fun participant(role: String = "USER", agent: Boolean = false): Long {
        val login = "category021-${UUID.randomUUID()}"
        return jdbc.queryForObject("""INSERT INTO participant(login,mail,password,status,role,is_agent)
            VALUES (?,?,?,'ACTIVE',?::participant_role,?) RETURNING id""",
            Long::class.java, login, "$login@example.test", passwordHash, role, agent)!!
    }

    private fun login(id: Long): String = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
        val mail = jdbc.queryForObject("SELECT mail FROM participant WHERE id=?", String::class.java, id)
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/login"))
            .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString("""{"mail":"$mail","password":"category021-password"}"""))
            .build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        ObjectMapper().readTree(response.body())["access_token"].asText().also {
            assertTrue(it.isNotBlank())
            val issued = decoder.decode(it)
            assertEquals(id, (issued.claims.getValue("id") as Number).toLong())
            assertEquals("ADMIN", issued.claims["role"])
            assertEquals("access", issued.claims["type"])
            assertNull(issued.claims["issuedBy"])
        }
    }

    @Test
    fun `fresh schema evidence includes current migration and database constraints`() {
        val history = jdbc.queryForList("SELECT installed_rank,version,description,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
        assertTrue(history.any { it["version"] == "1.29" && it["success"] == true })
        val queries = mapOf(
            "flyway" to history,
            "columns" to jdbc.queryForList("SELECT table_name,column_name,data_type,udt_name,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position"),
            "constraints" to jdbc.queryForList("SELECT c.relname AS table_name,p.conname,pg_get_constraintdef(p.oid) AS definition FROM pg_constraint p JOIN pg_class c ON c.oid=p.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,p.conname"),
            "indexes" to jdbc.queryForList("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname"),
        )
        Files.createDirectories(evidenceDirectory)
        Files.writeString(evidenceDirectory.resolve("schema.json"), ObjectMapper().writeValueAsString(queries))
        println("Category021 current Flyway rows: ${history.size}; V1.29 verified")
    }

    @Synchronized
    private fun evidence(file: String, value: Any) {
        Files.createDirectories(evidenceDirectory)
        Files.writeString(evidenceDirectory.resolve(file), ObjectMapper().writeValueAsString(value) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun category(): Long = jdbc.queryForObject("INSERT INTO category(name) VALUES (?) RETURNING id",
        Long::class.java, "category021-${UUID.randomUUID()}")!!

    private fun snapshot() = jdbc.queryForList("SELECT * FROM category ORDER BY id")

    private fun action(method: String, jwt: String?, category: Long, suffix: String = ""): HttpResponse<String> =
        HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().use { client ->
            val query = if (method == "POST") "name=created021$suffix" else "categoryId=$category&name=renamed021$suffix"
            client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/admin/actions/categories?$query"))
                .timeout(java.time.Duration.ofSeconds(15))
                .apply { if (jwt != null) header("Authorization", "Bearer $jwt") }
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        }
    private fun token(id: Long, type: String = "access", role: String = "ADMIN",
                      expiration: Instant = Instant.now().plusSeconds(3600)): String = Jwts.builder()
        .claim("id", id).claim("login", "category021").claim("role", role).claim("type", type)
        .apply { if (type == "agent_access") claim("issuedBy", "admin") }
        .expiration(Date.from(expiration)).signWith(key).compact()

    companion object {
        private val evidenceDirectory = Path.of("build/migration-02-1/audit-validation")
        private val postgres = EmbeddedPostgres.start()
        private val passwordHash = BCryptPasswordEncoder().encode("category021-password")
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



