package com.cloudgaming.signalingservice.dto

/**
 * - INVALID_MESSAGE: невалидный JSON или неизвестный тип сообщения
 * - WORKER_NOT_READY: воркер ответил не 200 на GET /ready
 * - WORKER_TIMEOUT: воркер принял соединение, но не ответил в течение sdpTimeout
 * - WORKER_UNREACHABLE: не удалось установить соединение (DNS, connection refused, connection reset)
 * - WORKER_REJECTED_OFFER: воркер вернул 400 на POST /sdp (текст ошибки в message)
 * - WORKER_ERROR: воркер вернул 5xx или невалидный ответ
 * - OFFER_IN_PROGRESS: предыдущий offer для этой сессии ещё обрабатывается
 * - SERVER_BUSY: executor переполнен, невозможно обработать запрос
 * - INTERNAL_ERROR: неожиданная ошибка сервера (детали только в логе)
 */
enum class ErrorCode {
    INVALID_MESSAGE,
    WORKER_NOT_READY,
    WORKER_TIMEOUT,
    WORKER_UNREACHABLE,
    WORKER_REJECTED_OFFER,
    WORKER_ERROR,
    OFFER_IN_PROGRESS,
    SERVER_BUSY,
    INTERNAL_ERROR,
}