package com.model_store.modern.seller.account.domain

enum class TransferMoney { BANK_CARD, BANK_SBP, CASH }

data class SellerAccount(
    val id: Long,
    val transferMoney: TransferMoney,
    val username: String?,
    val entityValue: String?,
    val comment: String?,
    val participantId: Long,
)

data class AccountDetails(
    val transferMoney: TransferMoney?,
    val username: String?,
    val entityValue: String?,
    val comment: String?,
)

class AccountNotFound : RuntimeException("Не удалось найти способы оплаты для данного пользователя")
class AccountAlreadyExists : RuntimeException("Способ оплаты с такой категории уже был загружен")
class AccountAccessDenied : RuntimeException("Доступ запрещён")
class AgentAccountDenied : RuntimeException("Доступ запрещён")
