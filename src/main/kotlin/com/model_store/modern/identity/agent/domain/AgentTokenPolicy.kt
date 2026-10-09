package com.model_store.modern.identity.agent.domain

import java.time.Duration
import java.time.Period

data class AgentTokenLifetime(val access: Duration, val refresh: Period) {
    companion object {
        fun from(accessMinutes: Int, refreshDays: Int): AgentTokenLifetime {
            if (accessMinutes !in 15..1440) throw AgentTokenFailure.InvalidAccessTtl()
            if (refreshDays !in 30..365) throw AgentTokenFailure.InvalidRefreshTtl()
            return AgentTokenLifetime(Duration.ofMinutes(accessMinutes.toLong()), Period.ofDays(refreshDays))
        }
    }
}

sealed class AgentTokenFailure(message: String) : RuntimeException(message) {
    class InvalidAccessTtl : AgentTokenFailure("accessTokenTtlMinutes должен быть в диапазоне 15-1440 минут")
    class InvalidRefreshTtl : AgentTokenFailure("refreshTokenTtlDays должен быть в диапазоне 30-365 дней")
    class AgentNotFound : AgentTokenFailure("Бот не найден")
    class AgentInactive : AgentTokenFailure("Бот не активен")
    class AccessDenied : AgentTokenFailure("Доступ запрещён")
    class InvalidAgentAccess : AgentTokenFailure("Доступ только для агентских токенов")
}
