package com.model_store.modern.seller.social.domain

enum class SocialNetworkType { TELEGRAM, VK, FACEBOOK, WHATSAPP }

data class SocialNetworkFields(val type: SocialNetworkType?, val login: String?) {
    fun validate() {
        requireNotNull(type) { "Не указан тип социальной сети" }
        require(login == null || login.length <= 255) { "Слишком длинный логин социальной сети" }
    }
}

data class SocialNetwork(val id: Long, val type: SocialNetworkType, val login: String?, val participantId: Long)

class SocialNetworkNotFound(message: String = "Социальная сеть не найдена") : RuntimeException(message)
class SocialNetworkAlreadyExists : RuntimeException("Такая соцсеть уже добавлена")
class SocialNetworkAccessDenied : RuntimeException("Доступ запрещён")
