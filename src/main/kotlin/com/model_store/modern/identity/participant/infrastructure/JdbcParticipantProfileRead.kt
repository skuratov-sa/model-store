package com.model_store.modern.identity.participant.infrastructure

import com.model_store.modern.identity.participant.application.*
import com.model_store.modern.identity.participant.domain.Participant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

@Repository
class JdbcParticipantProfileRead(private val jdbc: JdbcTemplate) : ParticipantProfileRead {
    override fun fullProfile(participant: Participant): FullProfile {
        val id = participant.id
        val rating = jdbc.query("SELECT average_rating, total_reviews FROM seller_rating WHERE seller_id = ?", { rs, _ ->
            val reviews = rs.getInt(2)
            val nullableReviews = if (rs.wasNull()) null else reviews
            Pair(rs.getBigDecimal(1)?.toFloat(), nullableReviews)
        }, id).firstOrNull()
        return FullProfile(
            id, participant.login, participant.mail, participant.fullName, participant.phoneNumber,
            participant.status.name, participant.sellerStatus?.name, rating?.first, rating?.second,
            mainImage(id),
            rows("""SELECT a.id, a.country, a.city, a.street, a.house_number AS "houseNumber",
                a.apartment_number AS "apartmentNumber", a."index", a.status::text AS status
                FROM address a JOIN participant_address pa ON pa.address_id = a.id WHERE pa.participant_id = ?""", id)
                .map { it + ("fullAddress" to "${it["country"]} г. ${it["city"]} ул. ${it["street"]} ${it["houseNumber"]} кв. ${it["apartmentNumber"]}, индекс: ${it["index"]}") },
            rows("""SELECT id, transfer_money::text AS "transferMoney", username,
                entity_value AS "entityValue", comment, participant_id AS "participantId"
                FROM account WHERE participant_id = ?""", id),
            rows("""SELECT id, sending::text AS sending, price, currency::text AS currency,
                participant_id AS "participantId", status::text AS status FROM transfer WHERE participant_id = ?""", id),
            rows("""SELECT id, type::text AS type, login, participant_id AS "participantId"
                FROM social_network WHERE participant_id = ?""", id),
        )
    }

    override fun search(id: Long?, name: String?): List<ParticipantSearchResult> {
        val sql = StringBuilder("""SELECT p.id, p.login, p.created_at, p.deadline_sending, p.deadline_payment,
            p.seller_status FROM participant p WHERE p.status = 'ACTIVE'""")
        val args = mutableListOf<Any>()
        if (id != null) {
            sql.append(" AND p.id = ?")
            args.add(id)
        }
        if (name != null) {
            sql.append(" AND (p.login ILIKE ? OR p.mail ILIKE ? OR p.full_name ILIKE ?)")
            repeat(3) { args.add("%$name%") }
        }
        sql.append(" ORDER BY p.created_at DESC")
        return jdbc.query(sql.toString(), { rs, _ ->
            val participantId = rs.getLong("id")
            ParticipantSearchResult(
                participantId, rs.getString("login"), null, null, mainImage(participantId),
                experience(rs.getTimestamp("created_at")?.toInstant()),
                count("SELECT count(*) FROM \"order\" WHERE seller_id = ? AND status = 'COMPLETED'", participantId),
                count("SELECT count(*) FROM \"order\" WHERE customer_id = ? AND status = 'COMPLETED'", participantId),
                rs.getInt("deadline_sending"), rs.getInt("deadline_payment"), rs.getString("seller_status"),
                null, jdbc.queryForList("SELECT transfer_money::text FROM account WHERE participant_id = ?", String::class.java, participantId).filterNotNull(),
            )
        }, *args.toTypedArray())
    }

    private fun rows(sql: String, id: Long): List<Map<String, Any?>> =
        jdbc.query(sql, { rs, _ -> rs.row() }, id)

    private fun ResultSet.row(): Map<String, Any?> = buildMap {
        val meta = metaData
        for (column in 1..meta.columnCount) put(meta.getColumnLabel(column), getObject(column))
    }

    private fun mainImage(id: Long): Long? = jdbc.query(
        """SELECT id FROM image WHERE entity_id = ? AND tag = 'PARTICIPANT'
            AND status = 'ACTIVE' ORDER BY created_at LIMIT 1""",
        { rs, _ -> rs.getLong(1) }, id,
    ).firstOrNull()

    private fun count(sql: String, id: Long): Int = jdbc.queryForObject(sql, Int::class.java, id) ?: 0

    private fun experience(created: Instant?): String {
        if (created == null) return "Нет опыта"
        val period = Period.between(created.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
        val years = period.years
        val months = period.months
        fun yearsLabel(n: Int) = if (n % 10 == 1 && n % 100 != 11) "год" else if (n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20)) "года" else "лет"
        fun monthsLabel(n: Int) = if (n == 1) "месяц" else if (n in 2..4) "месяца" else "месяцев"
        return when {
            years == 0 && months == 0 -> "Меньше месяца"
            years == 0 -> "$months ${monthsLabel(months)}"
            else -> "$years ${yearsLabel(years)} и $months ${monthsLabel(months)}"
        }
    }
}
