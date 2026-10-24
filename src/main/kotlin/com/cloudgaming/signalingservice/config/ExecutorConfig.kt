package com.cloudgaming.signalingservice.config

import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.CustomizableThreadFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private class MdcPropagatingRunnable(
    private val delegate: Runnable,
    private val contextMap: Map<String, String>?,
) : Runnable {
    override fun run() {
        val previousContext = MDC.getCopyOfContextMap()
        try {
            if (contextMap != null) {
                MDC.setContextMap(contextMap)
            } else {
                MDC.clear()
            }
            delegate.run()
        } finally {
            if (previousContext != null) {
                MDC.setContextMap(previousContext)
            } else {
                MDC.clear()
            }
        }
    }
}

private class MdcPropagatingExecutorService(
    private val delegate: ExecutorService,
) : ExecutorService by delegate {
    override fun execute(command: Runnable) {
        val contextMap = MDC.getCopyOfContextMap()
        delegate.execute(MdcPropagatingRunnable(command, contextMap))
    }
}

@Configuration
class ExecutorConfig(
    private val workerProperties: WorkerProperties,
) {

    @Bean(destroyMethod = "shutdown")
    fun workerCallExecutor(): ExecutorService {
        val executor = ThreadPoolExecutor(
            workerProperties.callExecutorPoolSize,
            workerProperties.callExecutorPoolSize,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            CustomizableThreadFactory("worker-call-"),
        )
        return MdcPropagatingExecutorService(executor)
    }
}
