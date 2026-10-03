package com.model_store.modern.seller.address

import com.amazonaws.services.s3.AmazonS3
import com.model_store.modern.seller.address.application.AddressUseCases
import com.model_store.modern.seller.address.application.DeliveryAddressReadPort
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.address.domain.AddressNotFound
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import jakarta.servlet.Filter
import org.mockito.Mockito

@SpringBootTest
@ActiveProfiles("modern")
class AddressIntegrationTest {
    @field:MockitoBean lateinit var s3: AmazonS3
    @field:MockitoBean lateinit var jwtDecoder: JwtDecoder
    @Autowired lateinit var cases: AddressUseCases
    @Autowired lateinit var read: DeliveryAddressReadPort
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var webContext: WebApplicationContext

    @Test
    @Transactional
    fun `crud keeps other participants addresses hidden and soft deletes owned address`() {
        val owner = participant()
        val other = participant()
        val fields = AddressFields("RU", "Moscow", "Main", "12", "3", 123456)
        val id = cases.create(owner, fields)
        assertEquals(id, cases.owned(owner).single().id)
        assertTrue(cases.owned(other).isEmpty())
        assertNull(read.findActiveOwned(other, id))
        assertEquals("Moscow", read.findActiveOwned(owner, id)?.city)
        assertThrows(AddressNotFound::class.java) { cases.update(other, id, fields.copy(city = "Stolen")) }
        assertThrows(AddressNotFound::class.java) { cases.delete(other, id) }
        assertEquals("Moscow", read.findActiveOwned(owner, id)?.city)
        assertEquals("Kazan", cases.update(owner, id, fields.copy(city = "Kazan")).city)
        cases.delete(owner, id)
        assertNull(read.findActiveOwned(owner, id))
        assertTrue(cases.owned(owner).isEmpty())
        assertEquals("DELETED", jdbc.queryForObject("SELECT status::text FROM address WHERE id = ?", String::class.java, id))
        assertThrows(AddressNotFound::class.java) { cases.update(owner, id, fields) }
    }

    @Test
    @Transactional
    fun `regions reflect distinct stored countries and invalid fields do not create records`() {
        val owner = participant()
        val id = cases.create(owner, AddressFields("TestRegion", null, null, null, null, null))
        assertTrue(cases.regions().contains("TestRegion"))
        assertThrows(IllegalArgumentException::class.java) {
            cases.create(owner, AddressFields("x".repeat(256), null, null, null, null, null))
        }
        assertEquals(listOf(id), cases.owned(owner).map { it.id })
    }

    @Test
    @Transactional
    fun `HTTP routes require a token and hide foreign addresses`() {
        val owner = participant()
        val other = participant()
        fun token(id: Long): String {
            val value = "address-user-$id"
            Mockito.`when`(jwtDecoder.decode(value)).thenReturn(
                Jwt.withTokenValue(value).header("alg", "RS256")
                    .claim("id", id).claim("role", "USER").claim("login", "user$id").build(),
            )
            return "Bearer $value"
        }
        val ownerToken = token(owner)
        val otherToken = token(other)
        val builder = MockMvcBuilders.webAppContextSetup(webContext)
        builder.addFilters<DefaultMockMvcBuilder>(webContext.getBean("springSecurityFilterChain") as Filter)
        val mvc = builder.build()
        assertEquals(401, mvc.perform(get("/address/regions")).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/address/regions").header("Authorization", ownerToken)).andReturn().response.status)
        val id = mvc.perform(post("/address").header("Authorization", ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"country":"RU","city":"Moscow","street":"Main","houseNumber":"1","apartmentNumber":"2","index":123456}"""))
            .andReturn().response.also { assertEquals(200, it.status) }.contentAsString.toLong()
        val owned = mvc.perform(get("/address").header("Authorization", ownerToken)).andReturn().response
        assertEquals(200, owned.status)
        assertTrue(owned.contentAsString.contains("\"id\":$id"))
        assertTrue(owned.contentAsString.contains("\"fullAddress\""))
        val foreign = mvc.perform(get("/address").header("Authorization", otherToken)).andReturn().response
        assertEquals("[]", foreign.contentAsString)
        val invalid = mvc.perform(put("/address/$id").header("Authorization", otherToken)
            .contentType(MediaType.APPLICATION_JSON).content("""{"city":"Stolen"}"""))
            .andReturn().response
        assertEquals(404, invalid.status)
        assertTrue(invalid.contentAsString.contains("ADDRESS_NOT_FOUND"))
        assertEquals(404, mvc.perform(delete("/address/$id").header("Authorization", otherToken)).andReturn().response.status)
        assertEquals(200, mvc.perform(delete("/address/$id").header("Authorization", ownerToken)).andReturn().response.status)
    }

    private fun participant(): Long = jdbc.queryForObject(
        "INSERT INTO participant(password, status) VALUES ('test', 'ACTIVE') RETURNING id", Long::class.java,
    )!!

    companion object {
        private val postgres = EmbeddedPostgres.start()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = "jdbc:postgresql://localhost:${postgres.port}/postgres"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { "" }
            registry.add("spring.flyway.url") { url }
            registry.add("spring.flyway.user") { "postgres" }
            registry.add("spring.flyway.password") { "" }
        }
    }
}
