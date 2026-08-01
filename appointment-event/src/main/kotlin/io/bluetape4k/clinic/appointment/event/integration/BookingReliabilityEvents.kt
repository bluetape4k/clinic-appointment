package io.bluetape4k.clinic.appointment.event.integration

/**
 * 예약 신뢰성 원장의 단일 정의는 appointment-core가 소유합니다.
 *
 * event module은 별도 PII table을 만들지 않고 core repository를 통해 이 원장에 기록합니다.
 */
typealias BookingReliabilityEvents =
    io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
