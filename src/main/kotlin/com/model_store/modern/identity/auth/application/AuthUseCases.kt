package com.model_store.modern.identity.auth.application

import com.model_store.modern.identity.auth.domain.AuthFailure
import com.model_store.modern.identity.auth.domain.AuthStatus
import com.model_store.modern.identity.verification.application.VerificationTokenIssuer
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service

@Service
@Profile("modern")
class AuthUseCases(
    private val accounts: AuthAccounts,
    private val passwords: AuthPasswords,
    private val tokens: AuthTokens,
) : VerificationTokenIssuer {
    fun login(mail: String?, password: String?): Map<String, String> {
        if (mail.isNullOrBlank() || password == null) throw BadCredentialsException("Bad credentials")
        val account = accounts.byMail(mail) ?: run {
            passwords.consumeUnknown(password)
            throw BadCredentialsException("Bad credentials")
        }
        // Legacy authentication manager checks locking before comparing the password.
        when (account.status) {
            AuthStatus.BLOCKED, AuthStatus.DELETED -> throw AuthFailure.Blocked()
            else -> Unit
        }
        if (!passwords.matches(password, account.passwordHash)) throw BadCredentialsException("Bad credentials")
        if (account.status == AuthStatus.WAITING_VERIFY) throw AuthFailure.WaitingVerify()
        return tokens.issue(account)
    }

    // The legacy endpoint maps any refresh failure to TOKEN_INVALID_OR_EXPIRED.
    fun refresh(token: String): String = try {
        tokens.refresh(token, accounts::byMail)
    } catch (_: Exception) {
        throw AuthFailure.InvalidRefresh()
    }

    /** Verification has already activated this ID before asking for its tokens. */
    override fun issue(participantId: Long): Map<String, String> {
        val account = accounts.byId(participantId) ?: throw AuthFailure.InvalidRefresh()
        if (account.status != AuthStatus.ACTIVE) throw AuthFailure.InvalidRefresh()
        return tokens.issue(account)
    }
}
