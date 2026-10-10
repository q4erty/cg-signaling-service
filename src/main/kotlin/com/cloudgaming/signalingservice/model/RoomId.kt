package com.cloudgaming.signalingservice.model

@JvmInline
value class RoomId private constructor(val value: String) {
    companion object {
        private val VALID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun of(value: String?): Result<RoomId> {
            if (value.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("room_id is required"))
            }
            if (!VALID_PATTERN.matches(value)) {
                return Result.failure(
                    IllegalArgumentException(
                        "room_id must match pattern ${VALID_PATTERN.pattern}, got: '$value'"
                    )
                )
            }
            return Result.success(RoomId(value))
        }
    }

    override fun toString(): String = value
}