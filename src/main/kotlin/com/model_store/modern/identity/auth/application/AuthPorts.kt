package com.model_store.modern.identity.auth.application

import com.model_store.modern.identity.auth.domain.AuthAccount

interface AuthAccounts {
    fun byMail(mail: String): AuthAccount?
    fun byId(id: Long): AuthAccount?
}

interface AuthPasswords {
    fun matches(raw: String, hash: String): Boolean
    fun consumeUnknown(raw: String)
}

interface AuthTokens {
    fun issue(account: AuthAccount): Map<String, String>
    fun refresh(token: String, findAccount: (String) -> AuthAccount?): String
}
