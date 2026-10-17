package com.model_store.modern.shared.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("modern")
class ModernOpenApiConfiguration {
    @Bean
    fun modernOpenApi(): OpenAPI {
        val scheme = "bearerAuth"
        return OpenAPI()
            .info(Info().title("Your API").version("1.0"))
            .addSecurityItem(SecurityRequirement().addList(scheme))
            .components(Components().addSecuritySchemes(scheme, SecurityScheme()
                .name(scheme).type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
    }
}
