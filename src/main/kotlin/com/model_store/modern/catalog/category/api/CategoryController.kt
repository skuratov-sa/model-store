package com.model_store.modern.catalog.category.api

import com.model_store.modern.catalog.category.application.CategoryUseCases
import com.model_store.modern.catalog.category.domain.CategoryBranch
import com.model_store.modern.catalog.category.domain.CategoryRuleViolation
import com.model_store.modern.catalog.category.domain.CategoryInvalidReference
import com.model_store.modern.catalog.category.domain.CategoryIntegrityFailure
import com.model_store.modern.shared.domain.Actor
import com.model_store.modern.shared.api.MvcApiError
import com.model_store.modern.shared.api.mvcApiError
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.server.ResponseStatusException

data class CategoryResponse(val id: Long, val name: String?, val childs: List<CategoryResponse>) {
    constructor(branch: CategoryBranch) : this(branch.id, branch.name, branch.children.map(::CategoryResponse))
}

@RestController
@Profile("modern")
class CategoryController(private val cases: CategoryUseCases) {
    @GetMapping("/categories")
    fun categories(): List<CategoryResponse> = cases.tree().map(::CategoryResponse)

    @PostMapping("/admin/actions/categories")
    fun create(
        @AuthenticationPrincipal actor: Actor?,
        @RequestParam name: String,
        @RequestParam(required = false) parentId: Long?,
    ): Long {
        requireAdmin(actor)
        return cases.create(name, parentId)
    }

    @PutMapping("/admin/actions/categories")
    fun rename(@AuthenticationPrincipal actor: Actor?, @RequestParam categoryId: Long, @RequestParam name: String) {
        requireAdmin(actor)
        cases.rename(categoryId, name)
    }

    private fun requireAdmin(actor: Actor?) {
        if (actor?.role != "ADMIN") throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    }
}

@RestControllerAdvice(assignableTypes = [CategoryController::class])
@Profile("modern")
class CategoryErrorHandler {
    @ExceptionHandler(CategoryRuleViolation::class)
    fun invalid(error: CategoryRuleViolation): ResponseEntity<MvcApiError> = mvcApiError(
        HttpStatus.BAD_REQUEST,
        if (error is CategoryInvalidReference) "INVALID_REFERENCE" else "BAD_REQUEST",
        if (error is CategoryInvalidReference) "Указана ссылка на несуществующий объект" else error.message,
    )

    @ExceptionHandler(CategoryIntegrityFailure::class)
    fun corruptTree(error: CategoryIntegrityFailure): ResponseEntity<MvcApiError> =
        mvcApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun databaseConstraint(error: DataIntegrityViolationException): ResponseEntity<MvcApiError> {
        val detail = generateSequence(error as Throwable) { it.cause }.last().message.orEmpty()
        val invalidReference = detail.contains("foreign key constraint", ignoreCase = true)
        val status = if (invalidReference) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT
        return mvcApiError(status,
            if (invalidReference) "INVALID_REFERENCE" else "DUPLICATE_KEY",
            if (invalidReference) "Указана ссылка на несуществующий объект" else "Нарушено ограничение уникальности")
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun forbidden(error: ResponseStatusException): ResponseEntity<MvcApiError> =
        mvcApiError(error.statusCode, "ACCESS_DENIED", error.reason)
}
