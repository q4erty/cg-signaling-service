package com.cloudgaming.signalingservice.worker

import com.cloudgaming.signalingservice.config.WorkerProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RestClientWorkerSignalingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RestClientWorkerSignalingClient
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')

        val properties = WorkerProperties(
            baseUrl = baseUrl,
            readyTimeout = java.time.Duration.ofSeconds(2),
            // Специально маленький — тест таймаута ниже полагается на то, что 2с
            // короче, чем сервер намеренно задерживает ответ.
            sdpTimeout = java.time.Duration.ofSeconds(2),
        )
        client = RestClientWorkerSignalingClient(properties, jacksonObjectMapper())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `isReady returns true when the worker reports PLAYING`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status": "READY", "pipeline": "PLAYING"}"""),
        )

        assertThat(client.isReady(baseUrl)).isTrue()
    }

    @Test
    fun `isReady returns false on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(client.isReady(baseUrl)).isFalse()
    }

    @Test
    fun `isReady returns false rather than throwing when the worker is unreachable`() {
        server.shutdown()

        assertThat(client.isReady(baseUrl)).isFalse()
    }

    @Test
    fun `exchangeSdp returns the worker's answer and posts the offer as JSON`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sdp": "v=0...answer", "type": "answer"}"""),
        )

        val result = client.exchangeSdp(baseUrl, "v=0...offer")

        assertThat(result.sdp).isEqualTo("v=0...answer")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/sdp")
        assertThat(recorded.body.readUtf8()).contains("v=0...offer").contains("\"offer\"")
    }

    @Test
    fun `exchangeSdp throws WorkerRejectedOfferException on 400 and keeps the worker's message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Offer без m-line"}"""),
        )

        assertThatThrownBy { client.exchangeSdp(baseUrl, "bad-offer") }
            .isInstanceOf(WorkerRejectedOfferException::class.java)
            .extracting { (it as WorkerRejectedOfferException).workerMessage }
            .isEqualTo("Offer без m-line")
    }

    @Test
    fun `exchangeSdp throws WorkerTimeoutException when the worker is slower than sdpTimeout`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sdp": "...", "type": "answer"}""")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )

        assertThatThrownBy { client.exchangeSdp(baseUrl, "offer") }
            .isInstanceOf(WorkerTimeoutException::class.java)
    }

    @Test
    fun `exchangeSdp throws WorkerUnreachableException when the worker is down`() {
        server.shutdown()

        assertThatThrownBy { client.exchangeSdp(baseUrl, "offer") }
            .isInstanceOf(WorkerUnreachableException::class.java)
    }
}
