package io.bluetape4k.clinic.appointment.event.reliability

/** 승인된 event 계약의 reader-facing package alias입니다. 구현과 신뢰 경계는 event/integration에 있습니다. */
typealias AppointmentReliabilityEvent =
    io.bluetape4k.clinic.appointment.event.integration.BookingReliabilitySignalEvent

typealias AppointmentReliabilitySignalType =
    io.bluetape4k.clinic.appointment.event.integration.BookingReliabilitySignalType

typealias AppointmentReliabilityResponsibility =
    io.bluetape4k.clinic.appointment.event.integration.BookingReliabilityResponsibility

typealias AppointmentReliabilityPayloadHasher =
    io.bluetape4k.clinic.appointment.event.integration.BookingReliabilitySignalPayloadHasher
