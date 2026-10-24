package com.cloudgaming.signalingservice.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class ObservabilityService(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ObservabilityService::class.java)

    private val sdpDurationTimer: Timer = Timer.builder("signaling.sdp.duration")
        .description("Duration of SDP exchange calls to workers")
        .serviceLevelObjectives(
            Duration.ofMillis(100),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(8),
            Duration.ofSeconds(10),
            Duration.ofSeconds(12),
            Duration.ofSeconds(15),
            Duration.ofSeconds(20),
        )
        .register(meterRegistry)

    private val activeSessions = AtomicInteger(0)
    private val activeSessionIds = ConcurrentHashMap.newKeySet<String>()

    init {
        meterRegistry.gauge("signaling.ws.sessions.active", activeSessions)
    }

    fun recordSdpDuration(outcome: String, durationMs: Long) {
        sdpDurationTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        log.info(
            "sdp_call_finished outcome={} durationMs={}",
            outcome,
            durationMs,
        )
    }

    fun sessionOpened(sessionId: String) {
        if (activeSessionIds.add(sessionId)) {
            activeSessions.incrementAndGet()
        }
    }

    fun sessionClosed(sessionId: String) {
        if (activeSessionIds.remove(sessionId)) {
            activeSessions.decrementAndGet()
        }
    }

    fun getActiveSessionCount(): Int = activeSessions.get()
}
