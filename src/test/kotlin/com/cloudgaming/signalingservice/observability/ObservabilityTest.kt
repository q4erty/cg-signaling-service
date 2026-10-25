package com.cloudgaming.signalingservice.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.cloudgaming.signalingservice.client.WorkerSignalingClient
import com.cloudgaming.signalingservice.config.WorkerProperties
import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.exception.WorkerTimeoutException
import com.cloudgaming.signalingservice.websocket.SessionState
import com.cloudgaming.signalingservice.websocket.SignalingHandshakeInterceptor
import com.cloudgaming.signalingservice.websocket.SignalingWebSocketHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

private class DirectExecutorService : AbstractExecutorService() {
    private var terminated = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { terminated = true }
    override fun shutdownNow(): MutableList<Runnable> { terminated = true; return mutableListOf() }
    override fun isShutdown(): Boolean = terminated
    override fun isTerminated(): Boolean = terminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

class ObservabilityTest {

    private val workerSignalingClient = mockk<WorkerSignalingClient>()
    private val objectMapper = jacksonObjectMapper()
    private val workerBaseUrl = "http://worker.local:9090"
    private val workerProperties = WorkerProperties()
    private lateinit var meterRegistry: MeterRegistry
    private lateinit var observabilityService: ObservabilityService
    private lateinit var handler: SignalingWebSocketHandler
    private lateinit var session: WebSocketSession
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: Logger

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        observabilityService = ObservabilityService(meterRegistry)
        handler = SignalingWebSocketHandler(
            workerSignalingClient = workerSignalingClient,
            objectMapper = objectMapper,
            workerCallExecutor = DirectExecutorService(),
            workerProperties = workerProperties,
            observabilityService = observabilityService,
        )

        session = mockk(relaxed = true)
        every { session.id } returns "test-session-1"
        every { session.isOpen } returns true
        every { session.attributes } returns mutableMapOf(
            SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL to workerBaseUrl,
            SignalingHandshakeInterceptor.ATTR_ROOM_ID to "test-room",
            SignalingHandshakeInterceptor.ATTR_USER_ID to "user-42",
        )

        val sentMessage = slot<TextMessage>()
        every { session.sendMessage(capture(sentMessage)) } returns Unit

        rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.level = Level.INFO
        listAppender = ListAppender()
        listAppender.start()
        rootLogger.addAppender(listAppender)

        handler.afterConnectionEstablished(session)
    }

    @AfterEach
    fun tearDown() {
        rootLogger.detachAppender(listAppender)
    }

    @Test
    fun `successful offer produces full event chain`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns true
        every { workerSignalingClient.exchangeSdp(workerBaseUrl, any()) } returns
                com.cloudgaming.signalingservice.client.SdpAnswer(sdp = "v=0...answer")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val events = listAppender.list.map { it.formattedMessage }

        assertThat(events).anyMatch { it.contains("ws_connected") }
        assertThat(events).anyMatch { it.contains("offer_received") }
        assertThat(events).anyMatch { it.contains("sdp_call_started") }
        assertThat(events).anyMatch { it.contains("sdp_call_finished") && it.contains("outcome=SUCCESS") }
    }

    @Test
    fun `worker timeout produces WORKER_TIMEOUT outcome in metrics`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns true
        every { workerSignalingClient.exchangeSdp(workerBaseUrl, any()) } throws
                WorkerTimeoutException(workerBaseUrl)

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val events = listAppender.list.map { it.formattedMessage }
        assertThat(events).anyMatch { it.contains("sdp_call_finished") && it.contains("outcome=WORKER_TIMEOUT") }

        val timer = meterRegistry.find("signaling.sdp.duration").timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1)
    }

    @Test
    fun `sdp duration timer records histogram buckets`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns true
        every { workerSignalingClient.exchangeSdp(workerBaseUrl, any()) } returns
                com.cloudgaming.signalingservice.client.SdpAnswer(sdp = "v=0...answer")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val timer = meterRegistry.find("signaling.sdp.duration").timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1)

        val snapshot = timer.takeSnapshot()
        assertThat(snapshot.histogramCounts()).isNotEmpty
    }

    @Test
    fun `active sessions gauge increments and decrements`() {
        assertThat(observabilityService.getActiveSessionCount()).isEqualTo(1)

        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL)

        assertThat(observabilityService.getActiveSessionCount()).isEqualTo(0)
    }

    @Test
    fun `transport error decrements active sessions gauge`() {
        assertThat(observabilityService.getActiveSessionCount()).isEqualTo(1)

        handler.handleTransportError(session, RuntimeException("connection reset"))

        assertThat(observabilityService.getActiveSessionCount()).isEqualTo(0)
    }

    @Test
    fun `MDC is cleared after task execution`() {
        every { workerSignalingClient.isReady(workerBaseUrl) } returns true
        every { workerSignalingClient.exchangeSdp(workerBaseUrl, any()) } returns
                com.cloudgaming.signalingservice.client.SdpAnswer(sdp = "v=0...answer")

        handler.handleTextMessage(session, TextMessage("""{"type": "offer", "sdp": "v=0...offer"}"""))

        val mdcContext = org.slf4j.MDC.getCopyOfContextMap()
        assertThat(mdcContext).isNull()
    }
}