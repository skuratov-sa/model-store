package com.model_store.modern.shared.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.cors.DefaultCorsProcessor

class ModernCorsConfigurationTest {
    @Test
    fun `configured origin list keeps credentialed preflight narrow`() {
        val environment = MockEnvironment()
            .withProperty("app.cors-allow-all", "false")
            .withProperty("app.allowed-origins[0]", "http://localhost:3000")
        val source = ModernPlatformConfiguration().modernCorsConfigurationSource(environment)
        val accepted = preflight(source, "http://localhost:3000")
        assertEquals(200, accepted.status)
        assertEquals("http://localhost:3000", accepted.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", accepted.getHeader("Access-Control-Allow-Credentials"))
        val rejected = preflight(source, "https://elsewhere.example")
        assertEquals(403, rejected.status)
        assertFalse(rejected.containsHeader("Access-Control-Allow-Origin"))
    }

    @Test
    fun `explicit allow all reflects origin with credentials as legacy does`() {
        val environment = MockEnvironment().withProperty("app.cors-allow-all", "true")
        val source = ModernPlatformConfiguration().modernCorsConfigurationSource(environment)
        val response = preflight(source, "https://elsewhere.example")
        assertEquals(200, response.status)
        assertEquals("https://elsewhere.example", response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        val actual = MockHttpServletRequest("GET", "/categories")
        actual.addHeader("Origin", "https://elsewhere.example")
        val actualResponse = MockHttpServletResponse()
        DefaultCorsProcessor().processRequest(source.getCorsConfiguration(actual), actual, actualResponse)
        assertEquals(200, actualResponse.status)
        assertEquals("https://elsewhere.example", actualResponse.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", actualResponse.getHeader("Access-Control-Allow-Credentials"))
    }

    private fun preflight(source: org.springframework.web.cors.UrlBasedCorsConfigurationSource,
                          origin: String): MockHttpServletResponse {
        val request = MockHttpServletRequest("OPTIONS", "/images")
        request.addHeader("Origin", origin)
        request.addHeader("Access-Control-Request-Method", "POST")
        request.addHeader("Access-Control-Request-Headers", "Authorization")
        val response = MockHttpServletResponse()
        DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response)
        return response
    }
}
