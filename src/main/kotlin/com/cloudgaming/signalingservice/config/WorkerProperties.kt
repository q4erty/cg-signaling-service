package com.cloudgaming.signalingservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "worker")
data class WorkerProperties(
    val baseUrl: String,
    val readyTimeout: Duration = Duration.ofSeconds(3),
    val sdpTimeout: Duration = Duration.ofSeconds(20),
    val callExecutorPoolSize: Int = 8,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** Запас поверх sdpTimeout для read-timeout sdpClient — реальный дедлайн
     *  обеспечивает future.get() в RestClientWorkerSignalingClient.exchangeSdp. */
    val sdpReadTimeoutSlack: Duration = Duration.ofSeconds(30),
)
