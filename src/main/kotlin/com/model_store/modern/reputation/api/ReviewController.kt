package com.model_store.modern.reputation.api

import com.model_store.modern.reputation.application.Reviews
import com.model_store.modern.reputation.application.ReviewView
import com.model_store.modern.shared.domain.Actor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

class ReviewRequest {
    @field:NotNull var orderId: Long? = null
    @field:Min(1) @field:Max(5)
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    var rating: Int = 0
    var comment: String? = null
}
data class ReviewResponse(val id: Long, val rating: Int, val comment: String?, val reviewerName: String,
                          val imageId: Long?, val createdAt: Instant?) {
    companion object { fun from(view: ReviewView) = ReviewResponse(view.id,view.rating,view.comment,view.reviewerName,view.imageId,view.createdAt) }
}

@RestController
@Profile("modern")
@RequestMapping("/reviews")
class ReviewController(private val reviews: Reviews) {
    // Match the legacy pre-controller bot filter even for invalid request bodies.
    @ModelAttribute
    fun authorize(@AuthenticationPrincipal actor: Actor?) = reviews.authorize(actor)
    @GetMapping("/seller/{sellerId}")
    fun seller(@AuthenticationPrincipal actor: Actor?, @PathVariable sellerId: Long) = reviews.forSeller(actor,sellerId).map(ReviewResponse::from)
    @PostMapping
    fun create(@AuthenticationPrincipal actor: Actor?, @RequestBody @Valid body: ReviewRequest) = reviews.create(actor,body.orderId!!,body.rating,body.comment)
    @DeleteMapping
    fun delete(@AuthenticationPrincipal actor: Actor?, @RequestParam reviewId: Long) = reviews.delete(actor,reviewId)
}
