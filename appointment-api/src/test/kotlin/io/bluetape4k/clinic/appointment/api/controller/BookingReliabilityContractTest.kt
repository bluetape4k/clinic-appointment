package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class BookingReliabilityContractTest {

    @Test
    fun `controller exposes the approved scoped routes`() {
        val controller = BookingReliabilityController::class.java
        controller.isAnnotationPresent(Validated::class.java).shouldBeTrue()
        controller.getAnnotation(RequestMapping::class.java).value.toList() shouldBeEqualTo
            listOf("/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability")

        val methods = controller.declaredMethods.associateBy { it.name }
        methods.getValue("decision").getAnnotation(GetMapping::class.java).value.toList() shouldBeEqualTo listOf("/decision")
        methods.getValue("override").getAnnotation(PostMapping::class.java).value.toList() shouldBeEqualTo listOf("/override")
        methods.getValue("clear").getAnnotation(PostMapping::class.java).value.toList() shouldBeEqualTo listOf("/clear")
        methods.getValue("audit").getAnnotation(GetMapping::class.java).value.toList() shouldBeEqualTo listOf("/audit")
    }
}
