package com.cloudgaming.signalingservice.integration

import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ErrorCode
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.security.JwtTestHelper
import com.cloudgaming.signalingservice.security.JwksStubServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for Ticket 4 resilience scenarios on stub worker (MockWebServer).
 *
 * Real JWT validation via JwksStubServer + JwtTestHelper (no auth bypasses).
 * No Docker required — runs with regular `./gradlew test`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "worker.ready-timeout=500ms",
        "worker.connect-timeout=500ms",
        "worker.sdp-timeout=2s",
        "worker.sdp-read-timeout-slack=500ms",
        "worker.call-executor-pool-size=4",
        "websocket.idle-timeout=30s",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResilienceIntegrationTest {

    companion object {
        private val jwtHelper = JwtTestHelper(issuer = "https://resilience-test.example.com")

        private val jwksServer = JwksStubServer(listOf(jwtHelper.publicKey)).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("auth.issuer-uri") { jwtHelper.issuer }
            registry.add("auth.jwk-set-uri") { jwksServer.jwksUri }
            registry.add("internal.secret") { "test-secret" }
        }

        @JvmStatic
        @AfterAll
        fun stopJwks() {
            jwksServer.stop()
        }

        @JvmStatic
        fun roomRegistry(): RoomRegistry = mockk(relaxed = true)
    }

    @LocalServerPort
    private var port: Int = 0

    @TestBean
    private lateinit var roomRegistry: RoomRegistry

    private lateinit var worker: MockWebServer
    private val objectMapper = jacksonObjectMapper()
    private val userA = "user-A"
    private val userB = "user-B"

    @BeforeEach
    fun setUp() {
        worker = MockWebServer()
        worker.start()

        val roomA = Room(
            roomId = RoomId.of("room-A").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = worker.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userA,
        )
        every { roomRegistry.resolve(RoomId.of("room-A").getOrThrow()) } returns RoomResolutionResult.Found(roomA)

        val roomB = Room(
            roomId = RoomId.of("room-B").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = worker.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userB,
        )
        every { roomRegistry.resolve(RoomId.of("room-B").getOrThrow()) } returns RoomResolutionResult.Found(roomB)
    }

    @AfterEach
    fun tearDown() {
        worker.shutdown()
    }

    // ===== Сценарий 1: Повторный offer =====

    @Test
    fun `repeated offer - two offers in same session get two answers`() {
        val sdpRequestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setBody(
                    """{"sdp":"answer-${sdpRequestCount.incrementAndGet()}","type":"answer"}"""
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        val answer1 = sendOfferAndWait(handler, session, "offer-1")
        assertEquals("answer-1", answer1)

        val answer2 = sendOfferAndWait(handler, session, "offer-2")
        assertEquals("answer-2", answer2)

        assertEquals(2, sdpRequestCount.get(), "Worker should receive exactly 2 POST /sdp")
        assertTrue(session.isOpen, "Session should remain open")
    }

    // ===== Сценарий 2: Восстановление после ошибки воркера =====

    @Test
    fun `recovery - worker returns 503 then back online, same session works`() {
        val readyCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> {
                    val count = readyCount.incrementAndGet()
                    if (count <= 1) {
                        MockResponse().setResponseCode(503)
                    } else {
                        MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    }
                }
                "/sdp" -> MockResponse().setBody("""{"sdp":"answer-after-recovery","type":"answer"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        // Первый offer — worker not ready
        val error1 = sendOfferAndWaitForError(handler, session, "offer-1")
        assertEquals(ErrorCode.WORKER_NOT_READY, error1.code)
        assertTrue(session.isOpen, "Session should remain open after error")

        // Небольшая пауза для завершения обработки предыдущего ответа
        Thread.sleep(100)

        // Второй offer — worker is back, /ready вернёт OK, /sdp вернёт answer
        val answer2 = sendOfferAndWait(handler, session, "offer-2", timeoutSeconds = 15)
        assertEquals("answer-after-recovery", answer2)
    }

    // ===== Сценарий 3: Таблица ошибок воркера =====

    @Test
    fun `worker ready returns 503 - WORKER_NOT_READY, sdp not called`() {
        val sdpRequestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setResponseCode(503)
                "/sdp" -> {
                    sdpRequestCount.incrementAndGet()
                    MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_NOT_READY, error.code)
        assertEquals(0, sdpRequestCount.get(), "POST /sdp should not be called when /ready fails")
        assertTrue(session.isOpen, "Session should remain open")

        // Session still works
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setBody("""{"sdp":"answer-2","type":"answer"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val answer = sendOfferAndWait(handler, session, "offer-2")
        assertEquals("answer-2", answer)
    }

    @Test
    fun `worker sdp returns 500 - WORKER_ERROR`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setResponseCode(500).setBody("Internal server error")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_ERROR, error.code)
        assertTrue(session.isOpen, "Session should remain open")
    }

    @Test
    fun `worker sdp returns 400 - WORKER_REJECTED_OFFER with worker message`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"detail":"Invalid SDP format"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")
        val error = sendOfferAndWaitForError(handler, session, "offer-1")

        assertEquals(ErrorCode.WORKER_REJECTED_OFFER, error.code)
        assertTrue(error.message.contains("Invalid SDP format"), "Worker message should be passed through")
        assertTrue(session.isOpen, "Session should remain open")
    }

    // ===== Сценарий 4: Обрыв посреди /sdp =====

    @Test
    fun `connection reset during sdp - error returned quickly, session stays open`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        val startTime = System.currentTimeMillis()
        val error = sendOfferAndWaitForError(handler, session, "offer-1", timeoutSeconds = 30)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(session.isOpen, "Session should remain open after connection reset")

        assertTrue(
            elapsed < 15_000,
            "Should fail in reasonable time (< 15s), took ${elapsed}ms"
        )

        println("Connection reset test: got error code ${error.code} in ${elapsed}ms")
    }

    // ===== Сценарий 5: Read timeout =====

    @Test
    fun `read timeout - WORKER_TIMEOUT after sdpTimeout`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse()
                    .setBody("""{"sdp":"answer","type":"answer"}""")
                    .setBodyDelay(10, TimeUnit.SECONDS)
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        val startTime = System.currentTimeMillis()
        val error = sendOfferAndWaitForError(handler, session, "offer-1", timeoutSeconds = 10)
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(ErrorCode.WORKER_TIMEOUT, error.code)
        assertTrue(session.isOpen, "Session should remain open")
        assertTrue(elapsed >= 1500, "Should wait at least close to sdpTimeout, took ${elapsed}ms")
        assertTrue(elapsed < 5000, "Should not wait much longer than sdpTimeout, took ${elapsed}ms")
    }

    // ===== Сценарий 7: Лимит - один offer в полёте =====

    @Test
    fun `offer in progress - second offer gets OFFER_IN_PROGRESS`() {
        val releaseLatch = CountDownLatch(1)
        val sdpRequestCount = AtomicInteger(0)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> {
                    sdpRequestCount.incrementAndGet()
                    releaseLatch.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"sdp":"answer-1","type":"answer"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        // Send first offer (will block on latch)
        val offer1 = ClientMessage.Offer(sdp = "offer-1")
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(offer1)))

        // Wait until first request reaches worker
        await().atMost(Duration.ofSeconds(5)).until { sdpRequestCount.get() == 1 }

        // Send second offer — should get OFFER_IN_PROGRESS immediately
        val error2 = sendOfferAndWaitForError(handler, session, "offer-2")
        assertEquals(ErrorCode.OFFER_IN_PROGRESS, error2.code)

        // Worker received exactly one request
        assertEquals(1, sdpRequestCount.get())

        // Release first request
        releaseLatch.countDown()

        // First offer gets answer
        val answer1 = handler.waitForAnswer(10, TimeUnit.SECONDS)
        assertNotNull(answer1)
        assertEquals("answer-1", answer1)

        // After first completes, next offer goes through
        val answer3 = sendOfferAndWait(handler, session, "offer-3")
        assertEquals("answer-1", answer3)
        assertEquals(2, sdpRequestCount.get())
    }

    @Test
    fun `offer in progress flag cleared after worker error`() {
        val requestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> {
                    val count = requestCount.incrementAndGet()
                    if (count == 1) {
                        MockResponse().setResponseCode(500)
                    } else {
                        MockResponse().setBody("""{"sdp":"answer-2","type":"answer"}""")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        val error1 = sendOfferAndWaitForError(handler, session, "offer-1")
        assertEquals(ErrorCode.WORKER_ERROR, error1.code)

        val answer2 = sendOfferAndWait(handler, session, "offer-2")
        assertEquals("answer-2", answer2)
    }

    @Test
    fun `two different sessions can have offers in flight simultaneously`() {
        val releaseLatch = CountDownLatch(1)
        val sdpRequestCount = AtomicInteger(0)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> {
                    sdpRequestCount.incrementAndGet()
                    releaseLatch.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (sessionA, handlerA) = connect(userA, "room-A")
        val (sessionB, handlerB) = connect(userB, "room-B")

        sessionA.sendMessage(TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-A"))))
        sessionB.sendMessage(TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-B"))))

        await().atMost(Duration.ofSeconds(5)).until { sdpRequestCount.get() == 2 }

        releaseLatch.countDown()

        val answerA = handlerA.waitForAnswer(10, TimeUnit.SECONDS)
        val answerB = handlerB.waitForAnswer(10, TimeUnit.SECONDS)
        assertNotNull(answerA)
        assertNotNull(answerB)
    }

    // ===== Сценарий 8: Executor переполнен =====

    @Test
    fun `executor exhausted - SERVER_BUSY, flag cleared, session open`() {
        val releaseLatch = CountDownLatch(1)
        val tasksInFlight = AtomicInteger(0)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> {
                    tasksInFlight.incrementAndGet()
                    releaseLatch.await(10, TimeUnit.SECONDS)
                    tasksInFlight.decrementAndGet()
                    MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        // Pool size is 4. Fill it with 4 sessions.
        val sessions = (1..4).map { connect(userA, "room-A") }

        // Send offers one by one and wait for each to reach worker
        sessions.forEachIndexed { index, (session, _) ->
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-$index"))))
            await().atMost(Duration.ofSeconds(5)).until { tasksInFlight.get() >= index + 1 }
        }

        // Now all 4 executor slots are occupied
        assertEquals(4, tasksInFlight.get(), "All 4 tasks should be in flight")

        // 5th session should get SERVER_BUSY
        val (session5, handler5) = connect(userA, "room-A")
        val error = sendOfferAndWaitForError(handler5, session5, "offer-5", timeoutSeconds = 5)
        assertEquals(ErrorCode.SERVER_BUSY, error.code)
        assertTrue(session5.isOpen, "Session should remain open after SERVER_BUSY")

        // Release all tasks
        releaseLatch.countDown()

        // Wait for all 4 tasks to complete
        await().atMost(Duration.ofSeconds(10)).until { tasksInFlight.get() == 0 }

        // Now session5 should be able to process another offer
        val answer = sendOfferAndWait(handler5, session5, "offer-6", timeoutSeconds = 15)
        assertEquals("answer", answer)
    }

    // ===== Сценарий 9: Стресс отправки =====

    @Test
    fun `stress - N sequential offers all get responses`() {
        val sdpRequestCount = AtomicInteger(0)
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setBody(
                    """{"sdp":"answer-${sdpRequestCount.incrementAndGet()}","type":"answer"}"""
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")
        val offerCount = 10

        repeat(offerCount) { i ->
            val answer = sendOfferAndWait(handler, session, "offer-$i")
            assertNotNull(answer, "Offer $i should get an answer")
        }

        assertEquals(offerCount, sdpRequestCount.get())
        assertTrue(session.isOpen)
    }

    // ===== Сценарий 10: Хендлер не роняет сессию =====

    @Test
    fun `malformed JSON - INVALID_MESSAGE, session stays open`() {
        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, handler) = connect(userA, "room-A")

        session.sendMessage(TextMessage("not valid json"))
        val error = handler.waitForError(5, TimeUnit.SECONDS)
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_MESSAGE, error?.code)
        assertTrue(session.isOpen, "Session should remain open after malformed JSON")

        val answer = sendOfferAndWait(handler, session, "offer-1")
        assertEquals("answer", answer)
    }

    @Test
    fun `unknown message type - INVALID_MESSAGE, session stays open`() {
        val (session, handler) = connect(userA, "room-A")

        session.sendMessage(TextMessage("""{"type":"unknown","data":"something"}"""))
        val error = handler.waitForError(5, TimeUnit.SECONDS)
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_MESSAGE, error?.code)
        assertTrue(session.isOpen, "Session should remain open after unknown type")
    }

    // ===== Сценарий: Закрытие вкладки во время /sdp =====

    @Test
    fun `client closes during sdp - worker request completes, no leak`() {
        val releaseLatch = CountDownLatch(1)
        val sdpCompleted = CountDownLatch(1)

        worker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                "/sdp" -> {
                    releaseLatch.await(10, TimeUnit.SECONDS)
                    sdpCompleted.countDown()
                    MockResponse().setBody("""{"sdp":"answer","type":"answer"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val (session, _) = connect(userA, "room-A")

        session.sendMessage(TextMessage(objectMapper.writeValueAsString(ClientMessage.Offer(sdp = "offer-1"))))

        await().atMost(Duration.ofSeconds(5)).until { worker.requestCount >= 2 }

        session.close()

        releaseLatch.countDown()

        assertTrue(
            sdpCompleted.await(5, TimeUnit.SECONDS),
            "Worker request should complete even after client disconnect"
        )
    }

    // ===== Helpers =====

    private fun connect(userId: String, roomId: String): Pair<WebSocketSession, TestHandler> {
        val token = jwtHelper.validToken(subject = userId)
        val client = StandardWebSocketClient()
        val handler = TestHandler(objectMapper)
        val wsUrl = "ws://localhost:$port/api/v1/signaling?room_id=$roomId&token=$token"
        val session = client.execute(handler, wsUrl).get(5, TimeUnit.SECONDS)
        return session to handler
    }

    private fun sendOfferAndWait(
        handler: TestHandler,
        session: WebSocketSession,
        offerSdp: String,
        timeoutSeconds: Long = 15,
    ): String {
        val offer = ClientMessage.Offer(sdp = offerSdp)
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(offer)))
        val answer = handler.waitForAnswer(timeoutSeconds, TimeUnit.SECONDS)
        assertNotNull(answer, "Did not receive answer in time for offer: $offerSdp")
        return answer!!
    }

    private fun sendOfferAndWaitForError(
        handler: TestHandler,
        session: WebSocketSession,
        offerSdp: String,
        timeoutSeconds: Long = 10,
    ): ServerMessage.Error {
        val offer = ClientMessage.Offer(sdp = offerSdp)
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(offer)))
        return requireNotNull(
            handler.waitForError(timeoutSeconds, TimeUnit.SECONDS)
        ) { "Did not receive error in time for offer: $offerSdp" }
    }

    class TestHandler(
        private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    ) : TextWebSocketHandler() {
        private val messages = LinkedBlockingDeque<ServerMessage>()

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            try {
                val serverMessage = objectMapper.readValue(message.payload, ServerMessage::class.java)
                messages.offer(serverMessage)
            } catch (e: Exception) {

            }
        }

        fun waitForAnswer(timeout: Long, unit: TimeUnit): String? {
            val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                val message = messages.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                if (message is ServerMessage.Answer) return message.sdp
            }
            return null
        }

        fun waitForError(timeout: Long, unit: TimeUnit): ServerMessage.Error? {
            val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                val message = messages.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                if (message is ServerMessage.Error) return message
            }
            return null
        }
    }
}