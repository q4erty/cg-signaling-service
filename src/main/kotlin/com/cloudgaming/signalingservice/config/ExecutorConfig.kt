package com.cloudgaming.signalingservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class ExecutorConfig(
    private val workerProperties: WorkerProperties,
) {

    @Bean(destroyMethod = "shutdown")
    fun workerCallExecutor(): ExecutorService =
        Executors.newFixedThreadPool(
            workerProperties.callExecutorPoolSize,
            CustomizableThreadFactory("worker-call-"),
        )
}
