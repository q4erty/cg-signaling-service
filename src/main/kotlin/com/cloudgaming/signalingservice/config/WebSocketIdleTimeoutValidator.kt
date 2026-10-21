package com.cloudgaming.signalingservice.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class WebSocketIdleTimeoutValidator(
    private val webSocketProperties: WebSocketProperties,
    private val workerProperties: WorkerProperties,
) {
    private val log = LoggerFactory.getLogger(WebSocketIdleTimeoutValidator::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun validate() {
        val worstCaseWait = workerProperties.readyTimeout
            .plus(workerProperties.sdpTimeout)
            .plusSeconds(5) // запас

        if (webSocketProperties.idleTimeout <= worstCaseWait) {
            val message = """
                WebSocket idle timeout (${webSocketProperties.idleTimeout}) is too short.
                Worst case wait time is ${worstCaseWait} (readyTimeout: ${workerProperties.readyTimeout} + sdpTimeout: ${workerProperties.sdpTimeout} + 5s buffer).
                Set websocket.idle-timeout to at least ${worstCaseWait.plusSeconds(10)} to prevent premature connection closure during SDP exchange.
            """.trimIndent()

            log.error(message)
            throw IllegalStateException(message)
        }

        log.info(
            "WebSocket idle timeout validation passed: {} >= {} (worst case)",
            webSocketProperties.idleTimeout,
            worstCaseWait,
        )
    }
}
