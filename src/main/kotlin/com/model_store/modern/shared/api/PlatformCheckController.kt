package com.model_store.modern.shared.api

import com.model_store.modern.shared.domain.Actor
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("modern")
@RequestMapping("/modern/check")
class PlatformCheckController {
    @GetMapping("/public")
    fun publicCheck() = PlatformStatus("modern", "ok")

    @GetMapping("/actor")
    fun actor(@AuthenticationPrincipal actor: Actor) = actor

    @GetMapping("/admin")
    fun adminCheck() = PlatformStatus("modern", "admin")
}

data class PlatformStatus(val mode: String, val status: String)
