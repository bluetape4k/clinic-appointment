package io.bluetape4k.clinic.appointment.statemachine

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.states.core.suspendStateMachine
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** bluetape4k-states DSL API가 appointment-core 테스트 classpath에서 컴파일되고 동작하는지 검증합니다. */
class StatesCompileProbeTest {

    @Test
    fun `appointment facade does not keep a local transition map`() {
        val hasLocalMap = AppointmentStateMachine::class.java.declaredFields
            .any { Map::class.java.isAssignableFrom(it.type) }

        hasLocalMap.shouldBeFalse()
    }

    @Test
    fun `suspendStateMachine DSL exposes current state transition and allowed events`() = runTest {
        val machine = suspendStateMachine<AppointmentState, AppointmentEvent> {
            initialState = AppointmentState.PENDING
            finalStates = setOf(AppointmentState.REQUESTED)
            transition(AppointmentState.PENDING, AppointmentEvent.Request::class.java, AppointmentState.REQUESTED)
        }

        machine.initialState shouldBeEqualTo AppointmentState.PENDING
        machine.currentState shouldBeEqualTo AppointmentState.PENDING
        machine.canTransition(AppointmentEvent.Request).shouldBeTrue()
        machine.canTransition(AppointmentEvent.Complete).shouldBeFalse()
        machine.allowedEvents() shouldBeEqualTo setOf(AppointmentEvent.Request::class.java)

        val result = machine.transition(AppointmentEvent.Request)

        result.previousState shouldBeEqualTo AppointmentState.PENDING
        result.event shouldBeEqualTo AppointmentEvent.Request
        result.currentState shouldBeEqualTo AppointmentState.REQUESTED
        machine.currentState shouldBeEqualTo AppointmentState.REQUESTED
    }
}
