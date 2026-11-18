package com.model_store.modern.catalog.giveaway.admin

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.catalog.giveaway.admin.application.*
import com.model_store.modern.catalog.giveaway.admin.domain.*
import com.model_store.modern.catalog.product.command.application.*
import com.model_store.modern.catalog.product.command.domain.*
import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.infrastructure.JdbcCurrentAdminLock
import com.model_store.modern.identity.participant.infrastructure.ParticipantEntity
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.address=127.0.0.1", "spring.config.import=",
        "logging.level.root=WARN", "logging.level.com.model_store=WARN",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.web=WARN",
        "logging.level.org.springframework.web.server=WARN", "logging.level.org.springframework.http=WARN",
        "logging.level.reactor.netty=WARN", "logging.level.io.netty=WARN"])
@ActiveProfiles("modern")
class CurrentGiveawayAdminAuthorizationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired lateinit var entityManager: EntityManager
    @MockitoSpyBean lateinit var managedOwner: CurrentAdminLock
    @Autowired lateinit var giveaways: AdminGiveaways
    @Autowired lateinit var create: CreateGiveaway
    @Autowired lateinit var update: UpdateGiveawayProduct
    @Autowired lateinit var products: ProductCommands
    @MockitoBean lateinit var s3: AmazonS3
    @MockitoBean lateinit var mail: JavaMailSender
    private val expectedOwnerPids = ConcurrentHashMap<Long, ArrayBlockingQueue<Int>>()

    @BeforeEach fun schemaBefore() {
        // Observe the real managed owner port's transaction before its locking SQL. Never replace
        // authorization or discover the worker by searching pg_stat_activity for a matching query.
        val ownerTarget = AopTestUtils.getUltimateTargetObject<CurrentAdminLock>(managedOwner)
        doAnswer { invocation ->
            expectedOwnerPids[(invocation.arguments[0] as Number).toLong()]?.put(
                jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
            invocation.callRealMethod()
        }.`when`(ownerTarget).lockActiveAdmin(anyLong())
        contract().use { it.schema("before test") }
    }
    @AfterEach fun schemaAfter() { contract().use { it.schema("after test") } }

    @Test fun `actual login ACCESS cannot retain revoked admin privileges across all entry points`() {
        contract().use { it.revokedAccessMatrix() }
    }
    @Test fun `positive transitions owner visibility issued agent USER and synthetic agent ADMIN`() {
        contract().use { it.positiveAndTokenContract() }
    }

    @Test fun `caller scalar contract refuses invalid admin claims before invoking identity`() {
        val lock = mock(CurrentAdminLock::class.java)
        val access = GiveawayAdminAccess(lock)
        for (caller in listOf(GiveawayCaller(null, "ADMIN", false), GiveawayCaller(0, "ADMIN", false),
            GiveawayCaller(-1, "ADMIN", false), GiveawayCaller(1, "USER", false), GiveawayCaller(1, "ADMIN", true))) {
            val failure = assertThrows(GiveawayFailure::class.java) { access.lock(caller) }
            assertEquals(GiveawayFailureKind.FORBIDDEN, failure.kind)
            assertEquals("ACCESS_DENIED", failure.code)
        }
        verifyNoInteractions(lock)
        doThrow(CurrentAdminTransactionRequired()).`when`(lock).lockActiveAdmin(1)
        assertThrows(CurrentAdminTransactionRequired::class.java) { access.lock(GiveawayCaller(1, "ADMIN", false)) }
    }

    @Test fun `late category and image failure rolls back settings product and claimed media`() {
        contract().use { c ->
            val admin = c.participant()
            c.ready(admin)
            val jwt = c.login(admin)
            val id = c.product(admin)
            val image = jdbc.queryForObject("""INSERT INTO image(tag,status,uploaded_by)
                VALUES ('PRODUCT','TEMPORARY',?) RETURNING id""", Long::class.java, admin)!!
            val before = c.snapshot(id)
            val imageBefore = jdbc.queryForMap("SELECT * FROM image WHERE id=?", image)
            val count = jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?", Long::class.java, admin)
            val badCategory = ",\"imageIds\":[$image],\"categoryIds\":[${Long.MAX_VALUE}]"
            for (path in listOf("/products", "/product/$id")) {
                val response = c.request(if (path == "/products") "POST" else "PUT", path, jwt, c.giveawayBody(badCategory))
                assertEquals(400, response.statusCode(), response.body())
                assertEquals("INVALID_REFERENCE", c.json.readTree(response.body())["code"].asText())
                assertEquals(before, c.snapshot(id))
                assertEquals(imageBefore, jdbc.queryForMap("SELECT * FROM image WHERE id=?", image))
                assertEquals(count, jdbc.queryForObject("SELECT count(*) FROM product WHERE participant_id=?", Long::class.java, admin))
            }
            val other = c.participant("USER")
            val wrongImage = jdbc.queryForObject("""INSERT INTO image(tag,status,uploaded_by)
                VALUES ('PRODUCT','TEMPORARY',?) RETURNING id""", Long::class.java, other)!!
            val response = c.request("PUT", "/product/$id", jwt, c.giveawayBody(",\"imageIds\":[$wrongImage]"))
            assertEquals(403, response.statusCode(), response.body())
            assertEquals(before, c.snapshot(id))
            assertNull(jdbc.queryForObject("SELECT entity_id FROM image WHERE id=?", Long::class.java, wrongImage))
        }
    }

    @Test fun `every admin operation rejects read only and JDBC only transactions before writes`() {
        contract().use { c ->
            val admin = c.participant()
            val id = c.product(admin)
            val before = catalogState(admin)
            val caller = GiveawayCaller(admin, "ADMIN", false)
            val changes = GiveawayCreate(null, null, null, null, null, null, null, null, null, null, null, null)
            val operations = listOf<() -> Unit>(
                { giveaways.history(caller, 0, 10) }, { giveaways.details(id, caller) },
                { giveaways.update(id, caller, GiveawaySettings(winnersCount = 2)) },
                { create.create(caller, changes) }, { update.update(id, caller, null, changes) },
                { products.create(admin, "ADMIN", ProductChanges()) },
                { products.update(id, admin, "ADMIN", ProductChanges()) },
            )
            for (action in operations) {
                assertThrows(CurrentAdminTransactionRequired::class.java) {
                    TransactionTemplate(manager).apply { isReadOnly = true }.executeWithoutResult { action() }
                }
                val jdbcFailure = assertThrowsExactly(CannotCreateTransactionException::class.java) {
                    TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).executeWithoutResult { action() }
                }
                assertEquals("Could not open JPA EntityManager for transaction", jdbcFailure.message)
                val bindingFailure = assertInstanceOf(IllegalStateException::class.java, jdbcFailure.cause)
                assertTrue(bindingFailure.message.orEmpty().startsWith("Already value [org.springframework.jdbc.datasource.ConnectionHolder@"))
                assertTrue(bindingFailure.message.orEmpty().endsWith("bound to thread"))
                assertNull(bindingFailure.cause)
                assertEquals(before, catalogState(admin))
            }
        }
    }

    @Test fun `unwrapped operations require expected EMF and datasource before touching catalog`() {
        contract().use { c ->
            val admin = c.participant()
            val giveawayStore = mock(AdminGiveawayStore::class.java)
            val productStore = mock(ProductCommandStore::class.java)
            val seller = mock(SellerEligibilityPort::class.java)
            val images = mock(ProductImageLink::class.java)
            fun operations(lock: CurrentAdminLock): List<() -> Unit> {
                val access = GiveawayAdminAccess(lock)
                val caller = GiveawayCaller(admin, "ADMIN", false)
                val changes = GiveawayCreate(null, null, null, null, null, null, null, null, null, null, null, null)
                val read = AdminGiveaways(giveawayStore, access)
                val create = CreateGiveaway(giveawayStore, productStore, images, 30, access)
                val update = UpdateGiveawayProduct(giveawayStore, productStore, seller, images, 30, access)
                val commands = ProductCommands(productStore, seller, images, 30, lock)
                return listOf({ read.history(caller, 0, 10); Unit }, { read.details(1, caller); Unit },
                    { read.update(1, caller, null) }, { create.create(caller, changes); Unit },
                    { update.update(1, caller, null, changes) },
                    { commands.create(admin, "ADMIN", ProductChanges()); Unit },
                    { commands.update(1, admin, "ADMIN", ProductChanges()) })
            }
            for (action in operations(JdbcCurrentAdminLock(emf, jdbc.dataSource!!))) {
                assertThrowsExactly(CurrentAdminTransactionRequired::class.java) { action() }
            }
            for (action in operations(managedOwner)) {
                val failure = assertThrowsExactly(IllegalTransactionStateException::class.java) { action() }
                assertTrue(failure.message.orEmpty().contains("propagation 'mandatory'"))
                assertNull(failure.cause)
            }
            for (lock in listOf(JdbcCurrentAdminLock(mock(EntityManagerFactory::class.java), jdbc.dataSource!!),
                JdbcCurrentAdminLock(emf, DriverManagerDataSource()))) {
                for (action in operations(lock)) {
                    assertThrows(CurrentAdminTransactionRequired::class.java) {
                        TransactionTemplate(manager).executeWithoutResult { action() }
                    }
                }
            }
            val foreignEmf = LocalContainerEntityManagerFactoryBean().apply {
                dataSource = jdbc.dataSource!!
                setPackagesToScan("com.model_store.modern.identity.participant.infrastructure")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
                afterPropertiesSet()
            }
            try {
                val foreignManager = JpaTransactionManager(foreignEmf.`object`!!).apply {
                    dataSource = jdbc.dataSource!!; afterPropertiesSet()
                }
                for (action in operations(JdbcCurrentAdminLock(emf, jdbc.dataSource!!))) {
                    assertThrowsExactly(CurrentAdminTransactionRequired::class.java) {
                        TransactionTemplate(foreignManager).executeWithoutResult { action() }
                    }
                }
            } finally { foreignEmf.destroy() }
            verifyNoInteractions(giveawayStore, productStore, seller, images)

            val id = c.product(admin)
            val before = catalogState(admin)
            val failure = assertThrowsExactly(GiveawayFailure::class.java) {
                TransactionTemplate(manager).executeWithoutResult {
                    assertEquals("ADMIN", entityManager.find(ParticipantEntity::class.java, admin).role.name)
                    jdbc.dataSource!!.connection.use { revoker ->
                        revoker.prepareStatement("UPDATE participant SET role='USER' WHERE id=?").use {
                            it.setLong(1, admin); assertEquals(1, it.executeUpdate())
                        }
                    }
                    giveaways.details(id, GiveawayCaller(admin, "ADMIN", false))
                }
            }
            assertEquals(GiveawayFailureKind.FORBIDDEN, failure.kind)
            assertEquals("ACCESS_DENIED", failure.code)
            assertEquals(before, catalogState(admin))
        }
    }

    @Test fun `revoked first real HTTP waits for participant then refuses every operation`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            contract().use { c ->
                for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                    for (operation in listOf("history", "details", "settings", "create", "transition", "generic", "generic-create")) {
                        val admin = c.participant()
                        val id = c.product(admin, operation != "generic")
                        val jwt = c.login(admin)
                        val before = c.snapshot(id)
                        val actionPids = ArrayBlockingQueue<Int>(1)
                        expectedOwnerPids[admin] = actionPids
                        jdbc.dataSource!!.connection.use { holder ->
                            holder.autoCommit = false
                            val blocker = pid(holder)
                            holder.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
                                it.setLong(1, admin); it.executeUpdate()
                            }
                            val action = pool.submit(Callable {
                                when (operation) {
                                    "history" -> c.request("GET", "/admin/actions/giveaways/history", jwt)
                                    "details" -> c.request("GET", "/admin/actions/giveaways/$id", jwt)
                                    "settings" -> c.request("PUT", "/admin/actions/giveaways/$id", jwt, """{"winnersCount":4}""")
                                    "create" -> c.request("POST", "/products", jwt, c.giveawayBody())
                                    "transition" -> c.request("PUT", "/product/$id", jwt, """{"availability":"PURCHASABLE","price":10}""")
                                    "generic-create" -> c.request("POST", "/products", jwt,
                                        """{"availability":"EXTERNAL_PRODUCT","price":10,"currency":"RUB","externalUrl":"https://example.test"}""")
                                    else -> c.request("PUT", "/product/$id", jwt, """{"name":"generic221"}""")
                                }
                            })
                            try {
                                val expectedActionPid = requireNotNull(actionPids.poll(10, TimeUnit.SECONDS))
                                awaitBlocked(expectedActionPid, blocker, PARTICIPANT_LOCK_SQL)
                                assertFalse(action.isDone)
                                assertEquals(before, c.snapshot(id))
                                holder.commit()
                                c.denied(action.get(15, TimeUnit.SECONDS))
                                assertEquals(before, c.snapshot(id))
                            } finally {
                                holder.rollback(); expectedOwnerPids.remove(admin)
                                action.get(15, TimeUnit.SECONDS)
                            }
                        }
                    }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `history and details retain caller lock until enclosing writable transaction completes`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            contract().use { c ->
                for (details in listOf(false, true)) {
                    val admin = c.participant()
                    val id = c.product(admin)
                    val caller = GiveawayCaller(admin, "ADMIN", false)
                    lateinit var revoke: Future<Int>
                    TransactionTemplate(manager).executeWithoutResult {
                        if (details) giveaways.details(id, caller) else giveaways.history(caller, 0, 10)
                        val blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                        val revokerPid = ArrayBlockingQueue<Int>(1)
                        revoke = pool.submit(Callable {
                            jdbc.dataSource!!.connection.use { connection ->
                                revokerPid.put(pid(connection))
                                connection.prepareStatement("UPDATE participant SET role='USER' WHERE id=?").use {
                                    it.setLong(1, admin); it.executeUpdate()
                                }
                            }
                        })
                        awaitBlocked(requireNotNull(revokerPid.poll(10, TimeUnit.SECONDS)), blocker,
                            "UPDATE participant SET role='USER' WHERE id=\$1")
                        assertFalse(revoke.isDone)
                    }
                    assertEquals(1, revoke.get(15, TimeUnit.SECONDS))
                    val failure = assertThrows(GiveawayFailure::class.java) { giveaways.details(id, caller) }
                    assertEquals(GiveawayFailureKind.FORBIDDEN, failure.kind)
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `action first keeps participant lock across dictionary then product and product only waits`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            contract().use { c ->
                for (frontier in listOf("dictionary", "giveaway-product", "generic-product")) {
                    for (assignment in listOf("role='USER'", "status='BLOCKED'", "is_agent=true")) {
                        val admin = c.participant()
                        val id = c.product(admin, frontier != "generic-product")
                        val jwt = c.login(admin)
                        val actionPids = ArrayBlockingQueue<Int>(1)
                        expectedOwnerPids[admin] = actionPids
                        jdbc.dataSource!!.connection.use { holder ->
                            holder.autoCommit = false
                            val blocker = pid(holder)
                            if (frontier == "dictionary") holder.createStatement().use {
                                it.executeQuery("SELECT value FROM dictionary WHERE type='PRODUCT_AVAILABILITY' AND value='GIVEAWAY' FOR UPDATE").close()
                            } else holder.prepareStatement("SELECT id FROM product WHERE id=? FOR UPDATE").use {
                                it.setLong(1, id); it.executeQuery().close()
                            }
                            val action = pool.submit(Callable {
                                if (frontier == "generic-product") c.request("PUT", "/product/$id", jwt, """{"name":"changed221"}""")
                                else c.request("PUT", "/admin/actions/giveaways/$id", jwt, """{"winnersCount":4}""")
                            })
                            try {
                                val actionPid = requireNotNull(actionPids.poll(10, TimeUnit.SECONDS))
                                awaitBlocked(actionPid, blocker, when (frontier) {
                                    "dictionary" -> DICTIONARY_LOCK_SQL
                                    "giveaway-product" -> GIVEAWAY_PRODUCT_LOCK_SQL
                                    else -> GENERIC_PRODUCT_LOCK_SQL
                                })
                                val revokePid = ArrayBlockingQueue<Int>(1)
                                val revoke = pool.submit(Callable {
                                    jdbc.dataSource!!.connection.use { revoker ->
                                        revokePid.put(pid(revoker))
                                        revoker.prepareStatement("UPDATE participant SET $assignment WHERE id=?").use {
                                            it.setLong(1, admin); it.executeUpdate()
                                        }
                                    }
                                })
                                val exact = requireNotNull(revokePid.poll(10, TimeUnit.SECONDS))
                                awaitBlocked(exact, actionPid, "UPDATE participant SET $assignment WHERE id=\$1")
                                assertFalse(action.isDone); assertFalse(revoke.isDone)
                                holder.commit()
                                c.ok(action.get(15, TimeUnit.SECONDS))
                                assertEquals(1, revoke.get(15, TimeUnit.SECONDS))
                                expectedOwnerPids.remove(admin)
                                c.denied(c.request("GET", "/admin/actions/giveaways/history", jwt))
                                if (frontier != "generic-product") assertEquals(4,
                                    jdbc.queryForObject("SELECT giveaway_winners_count FROM product WHERE id=?", Int::class.java, id))
                            } finally { holder.rollback(); expectedOwnerPids.remove(admin) }
                        }
                    }
                }
            }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    private fun awaitBlocked(expectedPid: Int, blocker: Int, expectedSql: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val rows = jdbc.queryForList("""SELECT pid,query,wait_event_type,
                pg_blocking_pids(pid)::text AS blockers FROM pg_stat_activity
                WHERE pid=? AND ?=ANY(pg_blocking_pids(pid))""", expectedPid, blocker)
            if (rows.size == 1 && normalizeSql(rows.single()["query"].toString()) == normalizeSql(expectedSql)) {
                val row = rows.single()
                assertEquals(expectedPid, (row["pid"] as Number).toInt())
                assertEquals(normalizeSql(expectedSql), normalizeSql(row["query"].toString()))
                assertEquals("Lock", row["wait_event_type"])
                println("22.1 exact PostgreSQL blocker=$blocker expected-waiter=$expectedPid SQL=${row["query"]} blockers=${row["blockers"]}")
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Expected PostgreSQL PID=$expectedPid did not block on PID=$blocker SQL=$expectedSql")
    }
    private fun normalizeSql(sql: String) = sql.trim().replace(Regex("\\s+"), " ")
    private fun catalogState(owner: Long) = listOf(
        jdbc.queryForList("SELECT * FROM product WHERE participant_id=? ORDER BY id", owner),
        jdbc.queryForList("SELECT * FROM image WHERE uploaded_by=? ORDER BY id", owner),
        jdbc.queryForList("SELECT pc.* FROM product_category pc JOIN product p ON p.id=pc.product_id WHERE p.participant_id=? ORDER BY pc.id", owner),
    )
    private fun pid(connection: java.sql.Connection): Int = connection.createStatement().use {
        it.executeQuery("SELECT pg_backend_pid()").use { row -> row.next(); row.getInt(1) }
    }
    private fun contract() = CurrentGiveawayAdminContract(jdbc, port, keys)
    companion object {
        private const val PARTICIPANT_LOCK_SQL = "SELECT role::text, status::text, is_agent FROM participant WHERE id=\$1 FOR UPDATE"
        private const val DICTIONARY_LOCK_SQL = "SELECT value FROM dictionary WHERE type='PRODUCT_AVAILABILITY' AND value='GIVEAWAY' FOR UPDATE"
        private val GIVEAWAY_PRODUCT_LOCK_SQL = """SELECT p.id, p.name, p.description, p.count, p.price, p.prepayment_amount,
            p.currency::text AS currency, p.originality, p.participant_id, p.status::text AS status,
            p.expiration_date, p.availability::text AS availability, p.used, p.external_url,
            p.giveaway_enabled, p.giveaway_telegram_url, p.giveaway_start_at, p.giveaway_end_at,
            p.giveaway_winners_count, p.giveaway_rules, p.giveaway_home_text, p.created_at
            FROM product p JOIN participant owner ON owner.id=p.participant_id
            WHERE p.id=${'$'}1 AND p.status <> 'DELETED' AND p.giveaway_end_at IS NOT NULL
            AND (p.participant_id=${'$'}2 OR owner.is_agent) FOR UPDATE OF p"""
        private val GENERIC_PRODUCT_LOCK_SQL = """SELECT id, participant_id, name, description, price, prepayment_amount, count,
            currency::text AS currency, originality, status::text AS status, expiration_date,
            availability::text AS availability, used, external_url, giveaway_enabled
            FROM product WHERE id = ${'$'}1 AND status <> 'DELETED' FOR UPDATE"""
        private val keys = Giveaway221Keys()
        private val postgres = EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
            registry.add("app.public-key-path") { keys.publicPath.toString() }
            registry.add("app.private-key-path") { keys.privatePath.toString() }
        }
        @JvmStatic @AfterAll fun close() { postgres.close(); keys.close() }
    }
}
