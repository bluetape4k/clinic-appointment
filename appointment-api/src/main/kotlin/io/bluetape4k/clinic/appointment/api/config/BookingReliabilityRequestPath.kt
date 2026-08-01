package io.bluetape4k.clinic.appointment.api.config

/** 예약 신뢰성 API의 공개 route family를 정확히 식별합니다. */
internal fun isBookingReliabilityRequestPath(requestUri: String): Boolean =
    Regex(
        "^/api/[^/]+/clinics/[^/]+/members/[^/]+/booking-reliability(?:/.*)?$",
    ).matches(requestUri)
