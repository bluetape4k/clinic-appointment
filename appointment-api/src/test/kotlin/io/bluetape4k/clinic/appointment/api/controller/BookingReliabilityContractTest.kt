package io.bluetape4k.clinic.appointment.api.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

class BookingReliabilityContractTest {

    @Test
    fun `controller exposes the approved scoped routes`() {
        val controller = BookingReliabilityController::class.java
        assertTrue(controller.isAnnotationPresent(Validated::class.java))
        assertEquals(
            listOf("/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability"),
            controller.getAnnotation(RequestMapping::class.java).value.toList(),
        )

        val methods = controller.declaredMethods.associateBy { it.name }
        assertEquals(listOf("/decision"), methods.getValue("decision").getAnnotation(GetMapping::class.java).value.toList())
        assertEquals(listOf("/override"), methods.getValue("override").getAnnotation(PostMapping::class.java).value.toList())
        assertEquals(listOf("/clear"), methods.getValue("clear").getAnnotation(PostMapping::class.java).value.toList())
        assertEquals(listOf("/audit"), methods.getValue("audit").getAnnotation(GetMapping::class.java).value.toList())
    }
}
