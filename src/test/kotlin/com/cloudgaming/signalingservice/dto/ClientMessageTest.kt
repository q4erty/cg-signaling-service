package com.cloudgaming.signalingservice.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClientMessageTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `parses an offer message`() {
        val json = """{"type": "offer", "sdp": "v=0..."}"""

        val message = mapper.readValue(json, ClientMessage::class.java)

        assertThat(message).isInstanceOf(ClientMessage.Offer::class.java)
        assertThat((message as ClientMessage.Offer).sdp).isEqualTo("v=0...")
    }

    @Test
    fun `rejects a message with an unknown type`() {
        // ice-candidate не поддержан (trickle ICE отложен) — важно, чтобы это падало
        // предсказуемо, а не молча.
        val json = """{"type": "ice-candidate", "candidate": "..."}"""

        assertThatThrownBy { mapper.readValue(json, ClientMessage::class.java) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `rejects a message with no type field`() {
        val json = """{"sdp": "v=0..."}"""

        assertThatThrownBy { mapper.readValue(json, ClientMessage::class.java) }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `rejects malformed JSON`() {
        assertThatThrownBy { mapper.readValue("{not json", ClientMessage::class.java) }
            .isInstanceOf(Exception::class.java)
    }
}
