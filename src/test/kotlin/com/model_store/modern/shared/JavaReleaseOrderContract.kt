package com.model_store.modern.shared

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** B08 is Java-only until the Kotlin transfer listed in JAVA_RELEASE_PROGRESS.md is implemented. */
internal object JavaReleaseOrderContract {
    private val migratedFields = setOf("orderId", "actualStatus", "totalPrice", "prepaymentAmount", "sellerId",
        "sellerLogin", "createdAt", "userInfo", "product", "transfer", "images", "paymentProofImageId",
        "deliveryUrl", "histories")
    private val javaReviewFields = setOf("canLeaveReview", "reviewId")

    fun assertResponse(view: JsonNode, javaRelease: Boolean, canLeaveReview: Boolean = false, reviewId: Long? = null) {
        assertEquals(migratedFields + if (javaRelease) javaReviewFields else emptySet(),
            view.fieldNames().asSequence().toSet(), "Order fields for the selected release profile")
        if (javaRelease) {
            assertTrue(view["canLeaveReview"].isBoolean, "canLeaveReview must be a JSON boolean")
            assertEquals(canLeaveReview, view["canLeaveReview"].booleanValue())
            if (reviewId == null) assertTrue(view["reviewId"].isNull, "reviewId must be explicitly null")
            else {
                assertTrue(view["reviewId"].isIntegralNumber, "reviewId must be an integer")
                assertEquals(reviewId, view["reviewId"].longValue())
            }
        }
    }

    /** Exclude only the documented root-level B08 extension; all migrated fields remain comparable. */
    fun migratedResponse(view: JsonNode): JsonNode = view.deepCopy<ObjectNode>().also {
        it.remove(javaReviewFields)
    }
}
