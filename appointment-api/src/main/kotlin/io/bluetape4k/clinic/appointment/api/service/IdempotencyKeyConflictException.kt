package io.bluetape4k.clinic.appointment.api.service

class IdempotencyKeyConflictException : RuntimeException(
    "Idempotency key was already used with a different request",
)
