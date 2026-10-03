package com.cloudgaming.signalingservice.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServerMessageTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `serializes an answer with its type field`() {
        val json = mapper.writeValueAsString(ServerMessage.Answer(sdp = "v=0...answer"))
        val tree = mapper.readTree(json)

        assertThat(tree["type"].asText()).isEqualTo("answer")
        assertThat(tree["sdp"].asText()).isEqualTo("v=0...answer")
    }

    @Test
    fun `serializes an error with its code and message`() {
        val json = mapper.writeValueAsString(
            ServerMessage.Error(ErrorCode.WORKER_TIMEOUT, "worker did not respond in time"),
        )
        val tree = mapper.readTree(json)

        assertThat(tree["type"].asText()).isEqualTo("error")
        assertThat(tree["code"].asText()).isEqualTo("WORKER_TIMEOUT")
        assertThat(tree["message"].asText()).isEqualTo("worker did not respond in time")
    }
}
