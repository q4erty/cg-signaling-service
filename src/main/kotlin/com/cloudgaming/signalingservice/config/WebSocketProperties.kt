package com.cloudgaming.signalingservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "websocket")
data class WebSocketProperties(
    val allowedOrigins: List<String> = emptyList()
)