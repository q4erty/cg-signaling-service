package com.cloudgaming.signalingservice.config

import com.cloudgaming.signalingservice.signaling.SignalingWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val signalingWebSocketHandler: SignalingWebSocketHandler,
    private val webSocketProperties: WebSocketProperties,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val allowedOrigins = webSocketProperties.allowedOrigins.toTypedArray()
        registry
            .addHandler(signalingWebSocketHandler, "/api/v1/signaling")
            .setAllowedOrigins(*allowedOrigins)
    }
}
