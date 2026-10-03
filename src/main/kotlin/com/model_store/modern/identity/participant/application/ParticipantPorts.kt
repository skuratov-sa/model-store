package com.model_store.modern.identity.participant.application

import com.model_store.modern.identity.participant.domain.Participant

interface ParticipantStore {
    fun find(id: Long): Participant?
    fun findForUpdate(id: Long): Participant?
    fun findActive(id: Long): Participant?
    fun mailExists(mail: String): Boolean
    fun loginExistsForAnother(login: String, id: Long): Boolean
    fun create(mail: String?, passwordHash: String, age: Int): Participant
    fun save(participant: Participant): Participant
}

interface ParticipantProfileRead {
    fun fullProfile(participant: Participant): FullProfile
    fun search(id: Long?, name: String?): List<ParticipantSearchResult>
}

interface ParticipantImagePort {
    fun replace(participantId: Long, imageId: Long)
}

interface ParticipantPasswords {
    fun hash(raw: String): String
    fun matches(raw: String, hash: String): Boolean
}

data class FullProfile(
    val id: Long, val login: String?, val mail: String?, val fullName: String?, val phoneNumber: String?,
    val status: String, val sellerStatus: String?, val averageRating: Float?, val totalReviews: Int?,
    val imageId: Long?, val addresses: List<Map<String, Any?>>, val accounts: List<Map<String, Any?>>,
    val transfers: List<Map<String, Any?>>, val socialNetworks: List<Map<String, Any?>>,
)

data class ParticipantSearchResult(
    val id: Long, val login: String?, val country: String?, val city: String?, val imageId: Long?,
    val experience: String, val orderCompletedCount: Int, val orderPurchaseCount: Int,
    val deadlineSending: Int, val deadlinePayment: Int, val sellerStatus: String?,
    val averageRating: Float?, val transferMoneys: List<String>,
)
