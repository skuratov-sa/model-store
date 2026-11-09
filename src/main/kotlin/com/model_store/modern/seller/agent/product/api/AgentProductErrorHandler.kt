package com.model_store.modern.seller.agent.product.api

import com.model_store.modern.catalog.product.agent.application.AgentProductDenied
import com.model_store.modern.catalog.product.command.application.ProductCommandFailure
import com.model_store.modern.catalog.giveaway.admin.application.GiveawayFailure
import com.model_store.modern.identity.agent.domain.AgentTokenFailure
import com.model_store.modern.identity.participant.application.AgentProfileFailure
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AgentProductController::class])
@Profile("modern")
class AgentProductErrorHandler {
    @ExceptionHandler(AgentProductDenied::class, AgentProfileFailure.AdminForbidden::class)
    fun denied() = mvcApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Доступ запрещён")

    @ExceptionHandler(AgentTokenFailure::class)
    fun agentDenied(error: AgentTokenFailure) = mvcApiError(HttpStatus.FORBIDDEN, "ACCESS_DENIED", error.message.orEmpty())

    @ExceptionHandler(AgentProfileFailure.AgentNotFound::class)
    fun agentMissing() = mvcApiError(HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND", "Бот не найден")

    @ExceptionHandler(ProductCommandFailure::class)
    fun product(error: ProductCommandFailure) = mvcApiError(status(error.kind.name), error.code, error.message.orEmpty())

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: DataIntegrityViolationException): org.springframework.http.ResponseEntity<com.model_store.modern.shared.api.MvcApiError> {
        var root: Throwable = error
        while (root.cause != null) root = root.cause!!
        val message = root.message.orEmpty()
        if ("uq_product_one_client" in message)
            return mvcApiError(HttpStatus.CONFLICT, "PRODUCT_ALREADY_EXISTS", "Товар с такими параметрами уже существует")
        if ("foreign key constraint" in message)
            return mvcApiError(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Указана ссылка на несуществующий объект")
        return mvcApiError(HttpStatus.CONFLICT, "DUPLICATE_KEY", "Нарушено ограничение уникальности")
    }

    @ExceptionHandler(GiveawayFailure::class)
    fun giveaway(error: GiveawayFailure) = mvcApiError(status(error.kind.name), error.code, error.message.orEmpty())

    private fun status(kind: String) = HttpStatus.valueOf(when (kind) {
        "BAD_REQUEST" -> 400; "FORBIDDEN" -> 403; "NOT_FOUND" -> 404; else -> 409
    })
}
