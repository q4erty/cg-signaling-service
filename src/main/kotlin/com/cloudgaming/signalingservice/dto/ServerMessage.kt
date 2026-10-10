package com.cloudgaming.signalingservice.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ServerMessage.Answer::class, name = "answer"),
    JsonSubTypes.Type(value = ServerMessage.Error::class, name = "error"),
)
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