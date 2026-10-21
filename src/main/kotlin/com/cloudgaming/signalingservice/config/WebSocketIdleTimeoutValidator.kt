package com.cloudgaming.signalingservice.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WebSocketIdleTimeoutValidator(
    private val webSocketProperties: WebSocketProperties,
    private val workerProperties: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(WebSocketIdleTimeoutValidator::class.java)

    @PostConstruct
    fun validate() {
        val worstCaseWait = workerProperties.readyTimeout
            .plus(workerProperties.sdpTimeout)

        if (webSocketProperties.idleTimeout <= worstCaseWait) {
            val message = """
                WebSocket idle timeout (${webSocketProperties.idleTimeout}) is too short.
                Worst case wait time is $worstCaseWait (readyTimeout: ${workerProperties.readyTimeout} + sdpTimeout: ${workerProperties.sdpTimeout}).
                Set websocket.idle-timeout to a value greater than $worstCaseWait to prevent premature connection closure during SDP exchange.
            """.trimIndent()

            log.error(message)
            throw IllegalStateException(message)
        }

        log.info(
            "WebSocket idle timeout validation passed: {} > {} (worst case)",
            webSocketProperties.idleTimeout,
            worstCaseWait,
        )
    }
}
