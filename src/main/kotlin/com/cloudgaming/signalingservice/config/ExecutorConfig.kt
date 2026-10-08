package com.cloudgaming.signalingservice.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Configuration
class ExecutorConfig(
    private val workerProperties: WorkerProperties,
) {

    @Bean(destroyMethod = "shutdown")
    fun workerCallExecutor(): ExecutorService = ThreadPoolExecutor(
        workerProperties.callExecutorPoolSize,
        workerProperties.callExecutorPoolSize,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        CustomizableThreadFactory("worker-call-"),
    )
}
