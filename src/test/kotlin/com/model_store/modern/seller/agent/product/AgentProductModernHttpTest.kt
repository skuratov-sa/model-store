package com.model_store.modern.seller.agent.product

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties=["app.public-key-path=keys/test_public_key.pem","app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("modern")
class AgentProductModernHttpTest : AgentProductHttpContract() {
    override val modern=true

    @Test fun `singular status route rejects stale admin JWT without changing agent product`() {
        val administrator=person("ADMIN");val access=token(administrator,"ADMIN")
        val owner=person(agent=true);val id=product(owner)
        val before=jdbc.queryForMap("SELECT * FROM product WHERE id=?",id)
        jdbc.update("UPDATE participant SET role='USER' WHERE id=?",administrator)
        error(call("PUT",root(owner,id),access,"{}"),403,"ACCESS_DENIED")
        error(call("POST",root(owner,id)+"/extend",access),403,"ACCESS_DENIED")
        error(call("PUT","/admin/actions/product/$id?productStatus=BLOCKED",access),403,"ACCESS_DENIED")
        assertEquals(before,jdbc.queryForMap("SELECT * FROM product WHERE id=?",id))
    }

    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(r:DynamicPropertyRegistry)=database(r,pg)
        @JvmStatic @AfterAll fun close()=pg.close()
    }
}
