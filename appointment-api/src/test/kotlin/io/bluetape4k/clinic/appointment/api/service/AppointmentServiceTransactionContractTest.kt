package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.coroutines.Continuation

class AppointmentServiceTransactionContractTest {

    @Test
    fun `create overload는 모두 기본 REQUIRED transaction으로 선언된다`() {
        val createMethods = AppointmentService::class.java.declaredMethods
            .filter { it.name == "create" }

        createMethods.isNotEmpty().shouldBeTrue()
        createMethods
            .map { it.getAnnotation(Transactional::class.java) }
            .all { it != null && !it.readOnly && it.propagation == Propagation.REQUIRED }
            .shouldBeTrue()
    }

    @Test
    fun `read entry point는 read only transaction으로 선언된다`() {
        val annotations = AppointmentService::class.java.declaredMethods
            .filter { it.name == "getByDateRange" }
            .mapNotNull { it.getAnnotation(Transactional::class.java) }

        annotations.any(Transactional::readOnly).shouldBeTrue()
        annotations.any { !it.readOnly }.shouldBeFalse()
    }

    @Test
    fun `suspend status와 cancel은 명시적 Exposed transaction 경계를 유지한다`() {
        val suspendMethods = AppointmentService::class.java.declaredMethods
            .filter { (it.name == "updateStatus" || it.name == "cancel") && it.parameterTypes.lastOrNull() == Continuation::class.java }
        suspendMethods.isNotEmpty().shouldBeTrue()
        suspendMethods
            .map { it.getAnnotation(Transactional::class.java) }
            .all { it == null }
            .shouldBeTrue()
    }
}
