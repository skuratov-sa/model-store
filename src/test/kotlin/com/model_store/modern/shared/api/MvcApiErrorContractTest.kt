package com.model_store.modern.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.model_store.modern.catalog.category.api.CategoryErrorHandler
import com.model_store.modern.catalog.product.command.api.ProductCommandErrorHandler
import com.model_store.modern.identity.auth.api.AuthErrorHandler
import com.model_store.modern.media.image.command.api.ImageCommandErrorHandler
import com.model_store.modern.media.image.command.domain.ImageCommandDenied
import com.model_store.modern.seller.account.api.AccountErrorHandler
import com.model_store.modern.seller.transfer.api.TransferErrorHandler
import com.model_store.modern.seller.transfer.domain.TransferAlreadyExists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

class MvcApiErrorContractTest {
    private val json = ObjectMapper()
    private val fields = setOf("code", "message", "status", "timestamp", "details")

    @Test
    fun `shared five field form retains route codes statuses null and date`() {
        val cases = listOf(
            Triple(AuthErrorHandler().credentials(), 401, "BAD_CREDENTIALS"),
            Triple(TransferErrorHandler().duplicate(TransferAlreadyExists()), 409, "TRANSFER_NOT_FOUND"),
            Triple(mvcApiError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Ошибка валидации",
                mapOf("errors" to listOf("email: must not be blank"))), 400, "VALIDATION_ERROR"),
        )
        cases.forEach { (response, status, code) ->
            assertEquals(status, response.statusCode.value())
            val body = json.readTree(json.writeValueAsString(response.body))
            assertEquals(fields, body.fieldNames().asSequence().toSet())
            assertEquals(code, body["code"].asText())
            assertEquals(status, body["status"].asInt())
            assertTrue(body["timestamp"].asText().contains("T"))
            if (code != "VALIDATION_ERROR") assertTrue(body["details"].isNull)
        }
    }

    @Test
    fun `constraint errors retain status and code without database text`() {
        val databaseText = "secret table and foreign key constraint"
        val error = DataIntegrityViolationException("write failed", IllegalArgumentException(databaseText))
        val responses = listOf(CategoryErrorHandler().databaseConstraint(error),
            ProductCommandErrorHandler().integrity(error))
        responses.forEach { response ->
            assertEquals(400, response.statusCode.value())
            val body = json.readTree(json.writeValueAsString(response.body))
            assertEquals("INVALID_REFERENCE", body["code"].asText())
            assertTrue(body["details"].isNull)
            assertFalse(body.toString().contains(databaseText))
        }
    }

    @Test
    fun `four field image and empty account denial remain distinct`() {
        val image = ImageCommandErrorHandler().denied(ImageCommandDenied())
        assertEquals(403, image.statusCode.value())
        val body = json.readTree(json.writeValueAsString(image.body))
        assertEquals(setOf("code", "message", "status", "timestamp"), body.fieldNames().asSequence().toSet())
        assertFalse(body.has("details"))
        val account = AccountErrorHandler().agentDenied()
        assertEquals(403, account.statusCode.value())
        assertEquals(null, account.body)
    }
}
