package com.cloudgaming.signalingservice.worker

import com.cloudgaming.signalingservice.config.WorkerProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

private data class OfferRequestBody(val sdp: String, val type: String = "offer")

private data class ReadyResponseBody(val status: String? = null, val pipeline: String? = null)

private object WorkerApi {
    const val READY_PATH = "/ready"
    const val SDP_PATH = "/sdp"
    const val PIPELINE_PLAYING = "PLAYING"
    const val ERROR_DETAIL_FIELD = "detail"
}

@Component
class RestClientWorkerSignalingClient(
    private val workerProperties: WorkerProperties,
    private val objectMapper: ObjectMapper,
) : WorkerSignalingClient {

    private val log = LoggerFactory.getLogger(RestClientWorkerSignalingClient::class.java)

    private val readyClient: RestClient = buildClient(
        responseTimeout = workerProperties.readyTimeout,
        connectionTimeout = workerProperties.readyTimeout,
    )

    /** Read-timeout больше sdpTimeout: JDK HttpClient не кидает HttpTimeoutException
     *  при таймауте чтения тела (JDK-8208693) — реальный дедлайн см. future.get() ниже. */
    private val sdpClient: RestClient = buildClient(
        responseTimeout = workerProperties.sdpTimeout.plus(workerProperties.sdpReadTimeoutSlack),
        connectionTimeout = workerProperties.connectTimeout,
    )

    private val sdpExecutor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors(),
        object : ThreadFactory {
            private val counter = AtomicLong(0)
            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "sdp-worker-${counter.incrementAndGet()}")
                t.isDaemon = true
                return t
            }
        },
    )

    private fun buildClient(responseTimeout: Duration, connectionTimeout: Duration): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(connectionTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(responseTimeout)
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build()
    }

    override fun isReady(workerBaseUrl: String): Boolean {
        return try {
            val body = readyClient.get()
                .uri("$workerBaseUrl${WorkerApi.READY_PATH}")
                .retrieve()
                .body(String::class.java)
                ?: return false
            val parsed = objectMapper.readValue(body, ReadyResponseBody::class.java)
            parsed.pipeline == WorkerApi.PIPELINE_PLAYING
        } catch (e: Exception) {
            log.debug("isReady({}) -> false: {}", workerBaseUrl, e.toString())
            false
        }
    }

    override fun exchangeSdp(workerBaseUrl: String, offerSdp: String): SdpAnswer {
        val requestBody = objectMapper.writeValueAsString(OfferRequestBody(sdp = offerSdp))

        val future = CompletableFuture.supplyAsync({
            sdpClient.post()
                .uri("$workerBaseUrl${WorkerApi.SDP_PATH}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .onStatus({ status: HttpStatusCode -> status == HttpStatus.BAD_REQUEST }) { _, response ->
                    val text = response.body.readAllBytes().toString(Charsets.UTF_8)
                    throw WorkerRejectedOfferException(workerBaseUrl, extractDetail(text))
                }
                .body(String::class.java)
                ?: throw WorkerProtocolException(workerBaseUrl, "empty response body")
        }, sdpExecutor)

        val responseBody: String
        try {
            responseBody = future.get(workerProperties.sdpTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw WorkerTimeoutException(workerBaseUrl, e)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is WorkerRejectedOfferException -> throw cause
                is WorkerProtocolException -> throw cause
                is RestClientException -> {
                    if (cause.hasTimeoutCause()) {
                        throw WorkerTimeoutException(workerBaseUrl, cause)
                    }
                    throw WorkerUnreachableException(workerBaseUrl, cause)
                }
                is java.io.IOException -> {
                    if (cause.hasTimeoutCause()) {
                        throw WorkerTimeoutException(workerBaseUrl, cause)
                    }
                    throw WorkerUnreachableException(workerBaseUrl, cause)
                }
                else -> throw WorkerProtocolException(workerBaseUrl, "unexpected error calling worker", cause ?: e)
            }
        }

        return try {
            objectMapper.readValue(responseBody, SdpAnswer::class.java)
        } catch (e: Exception) {
            throw WorkerProtocolException(workerBaseUrl, "не удалось разобрать ответ как SdpAnswer", e)
        }
    }

    private fun Throwable.hasTimeoutCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is java.net.http.HttpTimeoutException || current is java.net.SocketTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun extractDetail(rawBody: String): String =
        try {
            objectMapper.readTree(rawBody).path(WorkerApi.ERROR_DETAIL_FIELD).asText(rawBody)
        } catch (e: Exception) {
            rawBody
        }

    @PreDestroy
    fun shutdown() {
        sdpExecutor.shutdown()
    }
}