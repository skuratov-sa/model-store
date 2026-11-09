package com.model_store.modern.seller.agent.product

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties=["app.public-key-path=keys/test_public_key.pem","app.private-key-path=keys/test_private_key.pem"])
@ActiveProfiles("legacy")
class AgentProductLegacyHttpTest : AgentProductHttpContract() {
    override val modern=false
    companion object {
        private val pg=EmbeddedPostgres.start()
        @JvmStatic @DynamicPropertySource fun database(r:DynamicPropertyRegistry)=database(r,pg)
        @JvmStatic @AfterAll fun close()=pg.close()
    }
}
