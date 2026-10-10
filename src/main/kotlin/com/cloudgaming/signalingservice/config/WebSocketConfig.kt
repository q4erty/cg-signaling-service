package com.cloudgaming.signalingservice.config

import com.cloudgaming.signalingservice.websocket.SignalingHandshakeInterceptor
import com.cloudgaming.signalingservice.websocket.SignalingWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val signalingWebSocketHandler: SignalingWebSocketHandler,
    private val webSocketProperties: WebSocketProperties,
    private val handshakeInterceptor: SignalingHandshakeInterceptor,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val allowedOrigins = webSocketProperties.allowedOrigins.toTypedArray()

        registry
            .addHandler(signalingWebSocketHandler, "/api/v1/signaling")
            .addInterceptors(handshakeInterceptor)
            .setAllowedOrigins(*allowedOrigins)
    }
}
