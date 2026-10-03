package com.cloudgaming.signalingservice.dto

sealed interface ServerMessage {
    val type: String

    data class Answer(
        val sdp: String,
        override val type: String = "answer",
    ) : ServerMessage

    data class Error(
        val code: ErrorCode,
        val message: String,
        override val type: String = "error",
    ) : ServerMessage
}
