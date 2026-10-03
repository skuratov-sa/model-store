package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.domain.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "participant")
class ParticipantEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var login: String? = null,
    var mail: String? = null,
    @Column(name = "full_name") var fullName: String? = null,
    @Column(name = "phone_number") var phoneNumber: String? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    var status: ParticipantStatus = ParticipantStatus.WAITING_VERIFY,
    var password: String = "",
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    var role: ParticipantRole = ParticipantRole.USER,
    @Column(name = "deadline_sending") var deadlineSending: Short = 1,
    @Column(name = "deadline_payment") var deadlinePayment: Short = 1,
    @Column(name = "created_at", insertable = false, updatable = false)
    var createdAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(name = "seller_status")
    var sellerStatus: SellerStatus? = SellerStatus.DEFAULT,
    var age: Short? = null,
    @Column(name = "is_agent") var isAgent: Boolean = false,
) {
    fun toDomain() = Participant(
        id = requireNotNull(id), login = login, mail = mail, fullName = fullName,
        phoneNumber = phoneNumber, status = status, role = role, passwordHash = password,
        deadlineSending = deadlineSending.toInt(), deadlinePayment = deadlinePayment.toInt(),
        sellerStatus = sellerStatus, createdAt = requireNotNull(createdAt), age = age?.toInt(),
        isAgent = isAgent,
    )

    fun apply(participant: Participant) {
        login = participant.login
        mail = participant.mail
        fullName = participant.fullName
        phoneNumber = participant.phoneNumber
        status = participant.status
        password = participant.passwordHash
        role = participant.role
        deadlineSending = participant.deadlineSending.toShort()
        deadlinePayment = participant.deadlinePayment.toShort()
        sellerStatus = participant.sellerStatus
        age = participant.age?.toShort()
        isAgent = participant.isAgent
    }
}
