package com.model_store.modern.catalog.category.application

import com.model_store.modern.identity.participant.application.CurrentAdminForbidden
import com.model_store.modern.identity.participant.application.CurrentAdminLock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Participant authorization precedes category writes and lasts until the shared JPA commit. */
@Service
@Profile("modern")
class AdminCategoryCommands(
    private val currentAdmin: CurrentAdminLock,
    private val categories: CategoryUseCases,
) {
    @Transactional(transactionManager = "transactionManager")
    fun create(callerId: Long, name: String, parentId: Long?): Long {
        authorize(callerId)
        return categories.create(name, parentId)
    }

    @Transactional(transactionManager = "transactionManager")
    fun rename(callerId: Long, categoryId: Long, name: String) {
        authorize(callerId)
        categories.rename(categoryId, name)
    }

    private fun authorize(callerId: Long) {
        if (callerId <= 0) throw CurrentAdminForbidden()
        currentAdmin.lockActiveAdmin(callerId)
    }
}
