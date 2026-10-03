package com.model_store.modern.seller.address.infrastructure

import com.model_store.modern.seller.address.application.AddressStore
import com.model_store.modern.seller.address.domain.AddressFields
import com.model_store.modern.seller.address.domain.AddressStatus
import com.model_store.modern.seller.address.domain.DeliveryAddress
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcAddressStore(private val jdbc: JdbcTemplate) : AddressStore {
    private val columns = "a.id, a.country, a.city, a.street, a.house_number, a.apartment_number, a.\"index\", a.status::text AS status"

    override fun regions(): List<String?> = jdbc.query("SELECT DISTINCT country FROM address", { rs, _ -> rs.getString(1) })

    override fun findByOwner(ownerId: Long): List<DeliveryAddress> = jdbc.query(
        "SELECT $columns FROM address a JOIN participant_address pa ON pa.address_id = a.id WHERE pa.participant_id = ?",
        ::map, ownerId,
    )

    override fun findActiveOwned(customerId: Long, addressId: Long): DeliveryAddress? = ownedActive(customerId, addressId, false)

    override fun lockActiveOwned(ownerId: Long, addressId: Long): DeliveryAddress? = ownedActive(ownerId, addressId, true)

    private fun ownedActive(customerId: Long, addressId: Long, lock: Boolean): DeliveryAddress? = jdbc.query(
        "SELECT $columns FROM address a JOIN participant_address pa ON pa.address_id = a.id " +
            "WHERE pa.participant_id = ? AND a.id = ? AND a.status = 'ACTIVE'" + if (lock) " FOR UPDATE OF a" else "",
        ::map, customerId, addressId,
    ).firstOrNull()

    override fun create(ownerId: Long, fields: AddressFields): Long {
        val id = jdbc.queryForObject(
            "INSERT INTO address(country, city, street, house_number, apartment_number, \"index\") " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long::class.java,
            fields.country, fields.city, fields.street, fields.houseNumber, fields.apartmentNumber, fields.index,
        )!!
        jdbc.update("INSERT INTO participant_address(participant_id, address_id) VALUES (?, ?)", ownerId, id)
        return id
    }

    override fun update(addressId: Long, fields: AddressFields): DeliveryAddress {
        jdbc.update(
            "UPDATE address SET country = ?, city = ?, street = ?, house_number = ?, apartment_number = ?, \"index\" = ? WHERE id = ?",
            fields.country, fields.city, fields.street, fields.houseNumber, fields.apartmentNumber, fields.index, addressId,
        )
        return jdbc.query("SELECT $columns FROM address a WHERE a.id = ?", ::map, addressId).single()
    }

    override fun delete(ownerId: Long, addressId: Long) {
        jdbc.update("DELETE FROM participant_address WHERE participant_id = ? AND address_id = ?", ownerId, addressId)
        jdbc.update("UPDATE address SET status = 'DELETED' WHERE id = ?", addressId)
    }

    private fun map(rs: ResultSet, row: Int): DeliveryAddress = DeliveryAddress(
        rs.getLong("id"), rs.getString("country"), rs.getString("city"), rs.getString("street"),
        rs.getString("house_number"), rs.getString("apartment_number"),
        rs.getInt("index").let { if (rs.wasNull()) null else it }, AddressStatus.valueOf(rs.getString("status")),
    )
}
