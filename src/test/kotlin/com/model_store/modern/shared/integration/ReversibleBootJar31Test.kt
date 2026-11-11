package com.model_store.modern.shared.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.shared.api.BootJarSmokeServices
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One actual frozen artifact, one existing Flyway schema, three strictly sequential JVMs. */
@EnabledIfEnvironmentVariable(named="RUN_BOOT_JAR_SMOKE",matches="true")
class ReversibleBootJar31Test {
    @TempDir lateinit var directory: Path
    private val json=ObjectMapper()
    @Test fun `same actual jar legacy to modern to default legacy retains data and can continue both APIs`() {
        val output=Path.of("build/migration-31/reversible").toAbsolutePath(); Files.createDirectories(output)
        val jar=Files.copy(Path.of("build/libs/model-store-0.0.1-SNAPSHOT.jar").toAbsolutePath(),directory.resolve("frozen31.jar"))
        val hash=sha(jar); Files.writeString(output.resolve("jar.sha256"),hash)
        println("31 reversible bootJar SHA-256=$hash")
        BootJarSmokeServices().use { services -> EmbeddedPostgres.start().use { pg ->
            val database="jdbc:postgresql://127.0.0.1:${pg.port}/postgres"
            val jdbc=JdbcTemplate(DriverManagerDataSource(database,"postgres",""))
            val http=HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3)).build()
            try {
                var buyer=0L; var seller=0L; var admin=0L; var address=0L; var transfer=0L
                var oldProduct=0L; var oldOrder=0L; var modernProduct=0L; var modernOrder=0L; var modernPending=0L; var case=0L; var review=0L
                var proof=0L; var oldProof=0L; var continuationProof=0L; var modernView: JsonNode?=null; var flyway: List<Map<String,Any?>>?=null
                var previous: Process?=null
                for ((stage,profile) in listOf("legacy-initial" to "", "modern" to "modern", "legacy-return" to "")) {
                    assertFalse(previous?.isAlive ?: false,"Previous JVM/schedulers must be stopped before next startup")
                    assertEquals(hash,sha(jar),"Frozen artifact changed before $stage startup")
                    val child=Files.createDirectory(directory.resolve(stage)); val log=output.resolve("$stage.log")
                    val args=listOf(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
                        "-Dcom.amazonaws.sdk.disableEc2Metadata=true","-Dcom.amazonaws.regions.RegionUtils.disableRemote=true","-Daws.region=us-east-1",
                        "-Duser.home=$child","-Djava.io.tmpdir=$child","-jar",jar.toString(),
                        "--spring.config.location=classpath:/application.yml","--spring.config.import=",
                        "--server.address=127.0.0.1","--server.port=0",
                        "--spring.datasource.url=$database","--spring.datasource.username=postgres","--spring.datasource.password=",
                        "--spring.flyway.url=$database","--spring.flyway.user=postgres","--spring.flyway.password=",
                        "--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:${pg.port}/postgres","--spring.r2dbc.username=postgres","--spring.r2dbc.password=",
                        "--app.public-key-path=${fixture("test_public_key.pem")}","--app.private-key-path=${fixture("test_private_key.pem")}",
                        "--logging.level.root=WARN","--logging.level.com.model_store=WARN","--logging.level.com.model_store.ModelStoreApplication=INFO","--logging.level.org.springframework.web=WARN",
                        "--logging.level.org.springframework.boot=INFO",
                        "--logging.level.org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor=TRACE")+
                        (if(profile.isEmpty()) emptyList() else listOf("--spring.profiles.active=$profile"))+services.properties()
                    val builder=ProcessBuilder(args).directory(child.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                    val os=builder.environment().filterKeys {it.uppercase() in setOf("SYSTEMROOT","WINDIR")}
                    builder.environment().clear(); builder.environment().putAll(os)
                    builder.environment().putAll(mapOf("TEMP" to child.toString(),"TMP" to child.toString(),"AWS_EC2_METADATA_DISABLED" to "true"))
                    val checkpoint=services.checkpoint(); val process=builder.start(); previous=process
                    var boundPort: Int?=null
                    var scenarioFailure: Throwable?=null
                    try {
                        val port=awaitReady(http,process,log)
                        boundPort=port
                        if(profile.isEmpty()) {
                            assertTrue(Files.readString(log).contains("No active profile set,"),"No hidden active legacy profile may be supplied")
                            assertTrue(Files.readString(log).contains("Netty started on port $port"),"Default mode must actually start the reactive server")
                        }
                        services.assertStartup(if(profile=="modern") "modern" else "legacy",checkpoint)
                        assertSchedulers(log,profile)
                        fun call(method: String,path: String,id: Long?=null,body: String?=null,role: String="USER"): JsonNode {
                            val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
                            if(id!=null) request.header("Authorization","Bearer ${Integration31Fixture.token(id,role)}")
                            if(body!=null) request.header("Content-Type","application/json")
                            val r=http.send(request.method(method,body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
                            assertEquals(200,r.statusCode(),"$stage $method $path ${r.body()}")
                            return if(r.body().isEmpty()) json.nullNode() else json.readTree(r.body())
                        }
                        fun book(product: Long)=call("POST","/order/BOOKED",buyer,
                            """[{"productId":$product,"count":1,"addressId":$address,"transferId":$transfer}]""").single().asLong()
                        val schema=jdbc.queryForList("SELECT installed_rank,version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
                        if(stage=="legacy-initial") {
                            flyway=schema
                            fun actor(role: String): Long { val name=UUID.randomUUID().toString()
                                return jdbc.queryForObject("INSERT INTO participant(login,mail,full_name,password,status,role,deadline_sending,deadline_payment) VALUES (?,?,'Jar reviewer','fixture','ACTIVE',?::participant_role,3,7) RETURNING id",Long::class.java,name,"$name@example.test",role)!! }
                            buyer=actor("USER"); seller=actor("USER"); admin=actor("ADMIN")
                            address=jdbc.queryForObject("INSERT INTO address(country,city) VALUES ('RU','Moscow') RETURNING id",Long::class.java)!!
                            jdbc.update("INSERT INTO participant_address(participant_id,address_id) VALUES (?,?)",buyer,address)
                            transfer=jdbc.queryForObject("INSERT INTO transfer(sending,price,currency,participant_id) VALUES ('PRODUCT_PICKUP',0,'RUB',?) RETURNING id",Long::class.java,seller)!!
                            jdbc.update("INSERT INTO social_network(type,login,participant_id) VALUES ('TELEGRAM','jar31',?)",seller)
                            oldProduct=jdbc.queryForObject("""INSERT INTO product(name,price,currency,participant_id,status,availability,count,used,expiration_date)
                                VALUES ('legacy31',20,'RUB',?,'ACTIVE','PURCHASABLE',4,false,now()+interval '30 days') RETURNING id""",Long::class.java,seller)!!
                            fun image()=jdbc.queryForObject("INSERT INTO image(filename,tag,status,uploaded_by) VALUES (?,'ORDER','TEMPORARY',?) RETURNING id",Long::class.java,"${UUID.randomUUID()}.jpg",buyer)!!
                            // Stored object fixtures are initially present; lifecycle claims them through actual commands.
                            proof=image(); oldProof=image(); continuationProof=image()
                            oldOrder=book(oldProduct)
                            assertEquals("BOOKED",jdbc.queryForObject("SELECT status::text FROM \"order\" WHERE id=?",String::class.java,oldOrder))
                        } else {
                            assertEquals(flyway,schema,"No rollback/reapply/mutation of the existing migration schema")
                            val old=call("GET","/order/customer",buyer).single {it["orderId"].asLong()==oldOrder}
                            assertEquals(if(stage=="modern") "BOOKED" else "AWAITING_PAYMENT",old["actualStatus"].asText())
                            if(stage=="modern") {
                                call("POST","/order/$oldOrder/AWAITING_PREPAYMENT",seller)
                                modernProduct=call("POST","/products",seller,"""{"name":"modern31","price":30,"currency":"RUB","availability":"PURCHASABLE","count":4,"used":false}""").asLong()
                                modernOrder=book(modernProduct)
                                call("POST","/order/$modernOrder/AWAITING_PREPAYMENT",seller)
                                call("POST","/order/$modernOrder/ASSEMBLING?imageId=$proof",buyer)
                                call("POST","/order/$modernOrder/ON_THE_WAY?deliveryUrl=https%3A%2F%2Fexample.test%2Fmodern",seller)
                                case=call("POST","/order/orders/$modernOrder/DISPUTED?comment=delivery",buyer).asLong()
                                call("POST","/admin/actions/order-cases/$case/resolve?outcome=SELLER&comment=delivered",admin,role="ADMIN")
                                review=call("POST","/reviews",buyer,"""{"orderId":$modernOrder,"rating":5,"comment":"persisted-modern31"}""").asLong()
                                modernView=call("GET","/order/customer",buyer).single {it["orderId"].asLong()==modernOrder}
                                Files.writeString(output.resolve("modern-order.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(modernView))
                                modernPending=book(modernProduct)
                                call("POST","/order/$modernPending/AWAITING_PREPAYMENT",seller)
                            } else {
                                val back=call("GET","/order/customer",buyer).single {it["orderId"].asLong()==modernOrder}
                                assertEquals(modernView,back,"Legacy full DTO must read the persisted modern scenario")
                                Files.writeString(output.resolve("returned-legacy-order.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(back))
                                assertEquals("RESOLVED",call("GET","/admin/actions/order-cases/$case",admin,role="ADMIN")["orderCase"]["state"].asText())
                                assertTrue(call("GET","/reviews/seller/$seller",buyer).any {it["id"].asLong()==review && it["comment"].asText()=="persisted-modern31"})
                                // Continue data created in legacy and advanced in modern after switching back.
                                call("POST","/order/$oldOrder/ASSEMBLING?imageId=$oldProof",buyer)
                                call("POST","/order/$oldOrder/ON_THE_WAY?deliveryUrl=https%3A%2F%2Fexample.test%2Flegacy",seller)
                                call("POST","/order/$oldOrder/COMPLETED",buyer)
                                call("POST","/reviews",buyer,"""{"orderId":$oldOrder,"rating":4}""")
                                // Also continue an order CREATED in modern after the return, not only old legacy data.
                                call("POST","/order/$modernPending/ASSEMBLING?imageId=$continuationProof",buyer)
                                call("POST","/order/$modernPending/ON_THE_WAY?deliveryUrl=https%3A%2F%2Fexample.test%2Fcontinued",seller)
                                call("POST","/order/$modernPending/COMPLETED",buyer)
                                call("POST","/reviews",buyer,"""{"orderId":$modernPending,"rating":3}""")
                                assertEquals("COMPLETED",jdbc.queryForObject("SELECT status::text FROM \"order\" WHERE id=?",String::class.java,modernPending))
                                assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?",Int::class.java,modernPending))
                                assertEquals(3,jdbc.queryForObject("SELECT total_reviews FROM seller_rating WHERE seller_id=?",Int::class.java,seller))
                                assertEquals(2,jdbc.queryForObject("SELECT count FROM product WHERE id=?",Int::class.java,modernProduct))
                                assertEquals(3,jdbc.queryForObject("SELECT count FROM product WHERE id=?",Int::class.java,oldProduct))
                                assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?",Int::class.java,modernOrder))
                                assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?",Int::class.java,oldOrder))
                                assertEquals(flyway,jdbc.queryForList("SELECT installed_rank,version,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank"))
                            }
                        }
                        assertEquals(hash,sha(jar)); assertTrue(process.isAlive)
                        println("31 $stage completed PID=${process.pid()} schemaMigrations=${schema.size} oldOrder=$oldOrder modernOrder=$modernOrder modernPending=$modernPending")
                    } catch(failure: Throwable) { scenarioFailure=failure; throw failure }
                    finally {
                        try {
                            process.destroy()
                            try {if(!process.waitFor(10,TimeUnit.SECONDS)) process.destroyForcibly()}
                            finally { if(process.isAlive) process.destroyForcibly(); assertTrue(process.waitFor(10,TimeUnit.SECONDS)) }
                            assertFalse(process.isAlive)
                            boundPort?.let(::awaitPortClosed)
                            println("31 $stage PID=${process.pid()} stopped exit=${process.exitValue()} portClosed=$boundPort")
                        } catch(teardown: Throwable) {
                            if(scenarioFailure==null) throw teardown else scenarioFailure.addSuppressed(teardown)
                        }
                    }
                }
                assertEquals(hash,sha(jar)); assertFalse(previous!!.isAlive)
            } finally {http.shutdownNow(); assertTrue(http.awaitTermination(Duration.ofSeconds(5)),"JAR HTTP client did not terminate")}
        }}
    }
    private fun awaitPortClosed(port: Int) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<end) {
            try {java.net.Socket().use {it.connect(java.net.InetSocketAddress("127.0.0.1",port),200)}}
            catch(closed: java.io.IOException) {return}
            Thread.sleep(25)
        }
        error("Child server port $port remained open after its JVM stopped")
    }
    private fun awaitReady(http: HttpClient,process: Process,log: Path): Int {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90)
        while(process.isAlive && System.nanoTime()<end) {
            val port=Regex("(?:Netty|Tomcat) started on port (\\d+)\\b").find(Files.readString(log))?.groupValues?.get(1)?.toInt()
            if(port!=null && runCatching { http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v3/api-docs"))
                .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200 }.getOrDefault(false)) return port
            Thread.sleep(100)
        }
        error("Reversible JAR did not become ready: ${Files.readString(log).takeLast(3000)}")
    }
    private fun assertSchedulers(log: Path,profile: String) {
        val registrations=Files.readAllLines(log).filter {it.contains("@Scheduled methods processed on bean")}
        if(profile=="modern") { assertEquals(1,registrations.size); assertTrue(registrations.single().contains("3 @Scheduled methods processed on bean 'maintenanceSchedulers'")) }
        else { assertEquals(3,registrations.size); for(bean in listOf("productUpdateScheduler","imageCleanupScheduler","productCleanupScheduler"))
            assertEquals(1,registrations.count {it.contains("1 @Scheduled methods processed on bean '$bean'")}) }
    }
    private fun fixture(name: String)=Path.of("src/test/resources/keys",name).toAbsolutePath().toString()
    private fun sha(path: Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {"%02x".format(it)}
}
