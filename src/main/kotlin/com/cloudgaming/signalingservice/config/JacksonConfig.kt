package com.cloudgaming.signalingservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// jackson-module-kotlin пока не поддерживает Jackson 3, поэтому явный бин вместо автоконфигурации Spring Boot 4
@Configuration
class JacksonConfig {

    @Bean
    fun signalingObjectMapper(): ObjectMapper = jacksonObjectMapper()
}
