package com.cloudgaming.signalingservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "worker")
data class WorkerProperties(
    val readyTimeout: Duration = Duration.ofSeconds(3),
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val sdpTimeout: Duration = Duration.ofSeconds(20),
    val sdpReadTimeoutSlack: Duration = Duration.ofSeconds(5),
    val callExecutorPoolSize: Int = 100,
)
