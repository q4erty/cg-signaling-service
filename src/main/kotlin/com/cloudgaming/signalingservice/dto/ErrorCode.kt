package com.cloudgaming.signalingservice.dto

enum class ErrorCode {
    INVALID_MESSAGE,
    WORKER_NOT_READY,
    WORKER_TIMEOUT,
    WORKER_UNREACHABLE,
    WORKER_REJECTED_OFFER,
    OVERLOAD,
    INTERNAL_ERROR,
}
