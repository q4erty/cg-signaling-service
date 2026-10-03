package com.cloudgaming.signalingservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SignalingServiceApplication

fun main(args: Array<String>) {
    runApplication<SignalingServiceApplication>(*args)
}
