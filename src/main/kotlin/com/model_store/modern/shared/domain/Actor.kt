package com.model_store.modern.shared.domain

/** Identity and role carried by a verified access token. */
data class Actor(
    val participantId: Long?,
    val login: String?,
    val role: String?,
)
