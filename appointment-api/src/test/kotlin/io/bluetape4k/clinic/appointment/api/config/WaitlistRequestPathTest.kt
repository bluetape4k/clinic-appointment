package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class WaitlistRequestPathTest {
    @Test
    fun `waitlist path helper matches only clinic waitlist routes`() {
        isWaitlistRequestPath("/api/acme/clinics/7/waitlist") shouldBeEqualTo true
        isWaitlistRequestPath("/api/acme/clinics/7/waitlist/offers/o-1/decision") shouldBeEqualTo true

        isWaitlistRequestPath("/api/acme/clinics/7/members/m-1/booking-reliability") shouldBeEqualTo false
        isWaitlistRequestPath("/api/acme/admin/waitlist") shouldBeEqualTo false
        isWaitlistRequestPath("/api/acme/clinics/7/waitlisted") shouldBeEqualTo false
    }
}
