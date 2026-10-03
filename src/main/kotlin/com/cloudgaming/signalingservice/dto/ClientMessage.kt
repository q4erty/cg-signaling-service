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
    JsonSubTypes.Type(value = ClientMessage.Offer::class, name = "offer"),
)
sealed interface ClientMessage {
    val type: String

    data class Offer(
        val sdp: String,
        override val type: String = "offer",
    ) : ClientMessage
}
