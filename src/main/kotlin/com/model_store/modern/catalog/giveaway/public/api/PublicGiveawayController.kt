package com.model_store.modern.catalog.giveaway.`public`.api

import com.model_store.modern.catalog.giveaway.`public`.application.PublicGiveawayQuery
import com.model_store.modern.catalog.giveaway.`public`.domain.PublicGiveaway
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class PublicGiveawayResponse(val productId: Long, val name: String?, val description: String?,
    val imageIds: List<Long>, val telegramUrl: String?, val startAt: Instant, val endAt: Instant,
    val winnersCount: Int?, val rules: String?, val homeText: String?, val status: String)

@RestController
@Profile("modern")
class PublicGiveawayController(private val query: PublicGiveawayQuery) {
    @GetMapping("/giveaways/active")
    fun active(): ResponseEntity<PublicGiveawayResponse> = response(query.active())

    @GetMapping("/giveaways/products/{productId}")
    fun product(@PathVariable productId: Long): ResponseEntity<PublicGiveawayResponse> =
        response(query.byProductId(productId))

    private fun response(giveaway: PublicGiveaway?): ResponseEntity<PublicGiveawayResponse> =
        giveaway?.let {
            ResponseEntity.ok().header("X-Robots-Tag", "noindex, nofollow").body(
                PublicGiveawayResponse(it.productId, it.name, it.description, it.imageIds,
                    it.telegramUrl, it.startAt, it.endAt, it.winnersCount, it.rules, it.homeText, it.status))
        } ?: ResponseEntity.notFound().build()
}
