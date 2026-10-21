package com.cloudgaming.signalingservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "websocket")
data class WebSocketProperties(
    val allowedOrigins: List<String> = emptyList(),
    val idleTimeout: Duration = Duration.ofSeconds(30),
)