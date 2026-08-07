package io.bluetape4k.clinic.appointment.statemachine

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test

class AppointmentStateMachineTest {

    companion object: KLogging()

    private val stateMachine = AppointmentStateMachine()

    // ========================================
// 유효한 transition
    // ========================================

    @Test
    fun `PENDING에서 Request 이벤트로 REQUESTED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.PENDING, AppointmentEvent.Request)
        next shouldBeEqualTo AppointmentState.REQUESTED
    }

    @Test
    fun `REQUESTED에서 Confirm 이벤트로 CONFIRMED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.REQUESTED, AppointmentEvent.Confirm)
        next shouldBeEqualTo AppointmentState.CONFIRMED
    }

    @Test
    fun `CONFIRMED에서 CheckIn 이벤트로 CHECKED_IN으로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CONFIRMED, AppointmentEvent.CheckIn)
        next shouldBeEqualTo AppointmentState.CHECKED_IN
    }

    @Test
    fun `CHECKED_IN에서 StartTreatment 이벤트로 IN_PROGRESS로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CHECKED_IN, AppointmentEvent.StartTreatment)
        next shouldBeEqualTo AppointmentState.IN_PROGRESS
    }

    @Test
    fun `IN_PROGRESS에서 Complete 이벤트로 COMPLETED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.IN_PROGRESS, AppointmentEvent.Complete)
        next shouldBeEqualTo AppointmentState.COMPLETED
    }

    @Test
    fun `CONFIRMED에서 MarkNoShow 이벤트로 NO_SHOW로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CONFIRMED, AppointmentEvent.MarkNoShow)
        next shouldBeEqualTo AppointmentState.NO_SHOW
    }

    @Test
    fun `전체 정상 흐름 - PENDING부터 COMPLETED까지`() = runTest {
        var state: AppointmentState = AppointmentState.PENDING

        state = stateMachine.transition(state, AppointmentEvent.Request)
        state shouldBeEqualTo AppointmentState.REQUESTED

        state = stateMachine.transition(state, AppointmentEvent.Confirm)
        state shouldBeEqualTo AppointmentState.CONFIRMED

        state = stateMachine.transition(state, AppointmentEvent.CheckIn)
        state shouldBeEqualTo AppointmentState.CHECKED_IN

        state = stateMachine.transition(state, AppointmentEvent.StartTreatment)
        state shouldBeEqualTo AppointmentState.IN_PROGRESS

        state = stateMachine.transition(state, AppointmentEvent.Complete)
        state shouldBeEqualTo AppointmentState.COMPLETED
    }

    // ========================================
// cancel transition 검증
    // ========================================

    @Test
    fun `PENDING에서 Cancel 이벤트로 CANCELLED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.PENDING, AppointmentEvent.Cancel("환자 요청"))
        next shouldBeEqualTo AppointmentState.CANCELLED
    }

    @Test
    fun `REQUESTED에서 Cancel 이벤트로 CANCELLED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.REQUESTED, AppointmentEvent.Cancel("의사 부재"))
        next shouldBeEqualTo AppointmentState.CANCELLED
    }

    @Test
    fun `CONFIRMED에서 Cancel 이벤트로 CANCELLED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CONFIRMED, AppointmentEvent.Cancel("일정 변경"))
        next shouldBeEqualTo AppointmentState.CANCELLED
    }

    @Test
    fun `CHECKED_IN에서 Cancel 이벤트로 CANCELLED로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CHECKED_IN, AppointmentEvent.Cancel("환자 거부"))
        next shouldBeEqualTo AppointmentState.CANCELLED
    }

    // ========================================
// reschedule transition 검증
    // ========================================

    @Test
    fun `CONFIRMED에서 Reschedule 이벤트로 PENDING으로 전이`() = runTest {
        val next = stateMachine.transition(AppointmentState.CONFIRMED, AppointmentEvent.Reschedule)
        next shouldBeEqualTo AppointmentState.PENDING
    }

    // ========================================
// 유효하지 않은 transition
    // ========================================

    @Test
    fun `PENDING에서 Complete 이벤트는 예외 발생`() = runTest {
        assertFailsWith<IllegalStateException> {
            stateMachine.transition(AppointmentState.PENDING, AppointmentEvent.Complete)
        }
    }

    @Test
    fun `COMPLETED에서 Cancel 이벤트는 예외 발생`() = runTest {
        assertFailsWith<IllegalStateException> {
            stateMachine.transition(AppointmentState.COMPLETED, AppointmentEvent.Cancel("취소 시도"))
        }
    }

    @Test
    fun `CANCELLED에서 Request 이벤트는 예외 발생`() = runTest {
        assertFailsWith<IllegalStateException> {
            stateMachine.transition(AppointmentState.CANCELLED, AppointmentEvent.Request)
        }
    }

    @Test
    fun `NO_SHOW에서 CheckIn 이벤트는 예외 발생`() = runTest {
        assertFailsWith<IllegalStateException> {
            stateMachine.transition(AppointmentState.NO_SHOW, AppointmentEvent.CheckIn)
        }
    }

    @Test
    fun `IN_PROGRESS에서 Cancel 이벤트는 예외 발생`() = runTest {
        assertFailsWith<IllegalStateException> {
            stateMachine.transition(AppointmentState.IN_PROGRESS, AppointmentEvent.Cancel("진료 중 취소 불가"))
        }
    }

    // ========================================
// canTransition 검증
    // ========================================

    @Test
    fun `canTransition - 유효한 전이는 true 반환`() {
        stateMachine.canTransition(AppointmentState.PENDING, AppointmentEvent.Request).shouldBeTrue()
        stateMachine.canTransition(AppointmentState.REQUESTED, AppointmentEvent.Confirm).shouldBeTrue()
        stateMachine.canTransition(AppointmentState.CONFIRMED, AppointmentEvent.CheckIn).shouldBeTrue()
        stateMachine.canTransition(AppointmentState.CONFIRMED, AppointmentEvent.Reschedule).shouldBeTrue()
        stateMachine.canTransition(AppointmentState.PENDING, AppointmentEvent.Cancel("이유")).shouldBeTrue()
    }

    @Test
    fun `canTransition - 유효하지 않은 전이는 false 반환`() {
        stateMachine.canTransition(AppointmentState.PENDING, AppointmentEvent.Complete).shouldBeFalse()
        stateMachine.canTransition(AppointmentState.COMPLETED, AppointmentEvent.Cancel("이유")).shouldBeFalse()
        stateMachine.canTransition(AppointmentState.CANCELLED, AppointmentEvent.Request).shouldBeFalse()
    }

    // ========================================
// allowedEvents 검증
    // ========================================

    @Test
    fun `allowedEvents - PENDING에서 허용된 이벤트`() {
        val allowed = stateMachine.allowedEvents(AppointmentState.PENDING)
        allowed shouldContain AppointmentEvent.Request::class.java
        allowed shouldContain AppointmentEvent.Cancel::class.java
        allowed shouldNotContain AppointmentEvent.Confirm::class.java
    }

    @Test
    fun `allowedEvents - CONFIRMED에서 허용된 이벤트`() {
        val allowed = stateMachine.allowedEvents(AppointmentState.CONFIRMED)
        allowed shouldContain AppointmentEvent.CheckIn::class.java
        allowed shouldContain AppointmentEvent.MarkNoShow::class.java
        allowed shouldContain AppointmentEvent.Cancel::class.java
        allowed shouldContain AppointmentEvent.Reschedule::class.java
        allowed shouldNotContain AppointmentEvent.Complete::class.java
    }

    @Test
    fun `allowedEvents - COMPLETED에서 허용된 이벤트 없음`() {
        val allowed = stateMachine.allowedEvents(AppointmentState.COMPLETED)
        allowed shouldBeEqualTo emptySet()
    }

    @Test
    fun `allowedEvents - CANCELLED에서 허용된 이벤트 없음`() {
        val allowed = stateMachine.allowedEvents(AppointmentState.CANCELLED)
        allowed shouldBeEqualTo emptySet()
    }

    // ========================================
// onTransition callback 검증
    // ========================================

    @Test
    fun `onTransition 콜백이 성공적인 전이 시 호출됨`() = runTest {
        val transitions = mutableListOf<Triple<AppointmentState, AppointmentEvent, AppointmentState>>()

        val sm =
            AppointmentStateMachine { from, event, to ->
                transitions.add(Triple(from, event, to))
            }

        sm.transition(AppointmentState.PENDING, AppointmentEvent.Request)
        sm.transition(AppointmentState.REQUESTED, AppointmentEvent.Confirm)

        transitions.size shouldBeEqualTo 2
        transitions[0] shouldBeEqualTo
                Triple(
                    AppointmentState.PENDING,
                    AppointmentEvent.Request as AppointmentEvent,
                    AppointmentState.REQUESTED
                )
        transitions[1] shouldBeEqualTo
                Triple(
                    AppointmentState.REQUESTED,
                    AppointmentEvent.Confirm as AppointmentEvent,
                    AppointmentState.CONFIRMED
                )
    }

    @Test
    fun `onTransition 콜백은 실패한 전이 시 호출되지 않음`() = runTest {
        var callbackInvoked = false

        val sm =
            AppointmentStateMachine { _, _, _ ->
                callbackInvoked = true
            }

        assertFailsWith<IllegalStateException> {
            sm.transition(AppointmentState.PENDING, AppointmentEvent.Complete)
        }

        callbackInvoked.shouldBeFalse()
    }

    @Test
    fun `onTransition 콜백은 성공 전이마다 정확히 한 번 호출됨`() = runTest {
        var callbackCount = 0

        val sm =
            AppointmentStateMachine { from, event, to ->
                callbackCount += 1
                from shouldBeEqualTo AppointmentState.PENDING
                event shouldBeEqualTo AppointmentEvent.Request
                to shouldBeEqualTo AppointmentState.REQUESTED
            }

        sm.transition(AppointmentState.PENDING, AppointmentEvent.Request) shouldBeEqualTo AppointmentState.REQUESTED

        callbackCount shouldBeEqualTo 1
    }

    @Test
    fun `onTransition 콜백의 cancellation은 그대로 전파됨`() = runTest {
        val sm =
            AppointmentStateMachine { _, _, _ ->
                throw CancellationException("cancel transition")
            }

        val error = assertFailsWith<CancellationException> {
            sm.transition(AppointmentState.PENDING, AppointmentEvent.Request)
        }

        error.message shouldBeEqualTo "cancel transition"
    }
}
