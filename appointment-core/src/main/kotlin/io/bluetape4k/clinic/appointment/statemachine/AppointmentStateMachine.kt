package io.bluetape4k.clinic.appointment.statemachine

import io.bluetape4k.states.api.StateMachineException
import io.bluetape4k.states.api.StateMachine
import io.bluetape4k.states.core.stateMachine

/**
 * 예약 상태 머신.
 *
 * bluetape4k-states DSL 로 허용된 상태 전이를 정의하고,
 * 기존 appointment domain facade API로 현재 상태와 이벤트에 따른 다음 상태를 반환합니다.
 */
class AppointmentStateMachine(
    private val onTransition: (
        suspend (
            from: AppointmentState,
            event: AppointmentEvent,
            to: AppointmentState,
        ) -> Unit
    )? = null,
) {
    /**
     * 현재 상태에서 이벤트를 처리하여 다음 상태로 전이합니다.
     *
     * @param currentState 현재 상태
     * @param event 발생한 이벤트
     * @return 전이된 다음 상태
     * @throws IllegalStateException 허용되지 않은 전이인 경우
     */
    suspend fun transition(
        currentState: AppointmentState,
        event: AppointmentEvent,
    ): AppointmentState {
        val result = try {
            stateMachine(currentState).transition(event)
        } catch (e: StateMachineException) {
            throw invalidTransition(currentState, event, e)
        }

        onTransition?.invoke(result.previousState, result.event, result.currentState)
        return result.currentState
    }

    /**
     * callback 없이 다음 상태만 계산한다.
     *
     * 데이터베이스 command transaction 안에서 상태 검증과 CAS update를 분리하지 않아야
     * 하는 caller가 사용한다. callback이 필요한 경로는 [transition]을 사용한다.
     */
    fun nextState(
        currentState: AppointmentState,
        event: AppointmentEvent,
    ): AppointmentState = transitionWithoutCallback(currentState, event)

    /**
     * 현재 상태에서 해당 이벤트로 전이 가능한지 확인합니다.
     */
    fun canTransition(
        currentState: AppointmentState,
        event: AppointmentEvent,
    ): Boolean =
        stateMachine(currentState).canTransition(event)

    /**
     * 현재 상태에서 허용된 이벤트 클래스 목록을 반환합니다.
     */
    fun allowedEvents(currentState: AppointmentState): Set<Class<out AppointmentEvent>> =
        stateMachine(currentState).allowedEvents()

    private fun transitionWithoutCallback(
        currentState: AppointmentState,
        event: AppointmentEvent,
    ): AppointmentState {
        val result = try {
            stateMachine(currentState).transition(event)
        } catch (e: StateMachineException) {
            throw invalidTransition(currentState, event, e)
        }

        return result.currentState
    }

    private fun stateMachine(
        currentState: AppointmentState,
    ): StateMachine<AppointmentState, AppointmentEvent> =
        stateMachine {
            initialState = currentState
            finalStates = setOf(
                AppointmentState.COMPLETED,
                AppointmentState.CANCELLED,
                AppointmentState.NO_SHOW,
                AppointmentState.RESCHEDULED,
            )

            transition(AppointmentState.PENDING, AppointmentEvent.Request::class.java, AppointmentState.REQUESTED)
            transition(AppointmentState.PENDING, AppointmentEvent.Cancel::class.java, AppointmentState.CANCELLED)

            transition(AppointmentState.REQUESTED, AppointmentEvent.Confirm::class.java, AppointmentState.CONFIRMED)
            transition(
                AppointmentState.REQUESTED,
                AppointmentEvent.RequestReschedule::class.java,
                AppointmentState.PENDING_RESCHEDULE,
            )
            transition(AppointmentState.REQUESTED, AppointmentEvent.Cancel::class.java, AppointmentState.CANCELLED)

            transition(AppointmentState.CONFIRMED, AppointmentEvent.CheckIn::class.java, AppointmentState.CHECKED_IN)
            transition(AppointmentState.CONFIRMED, AppointmentEvent.MarkNoShow::class.java, AppointmentState.NO_SHOW)
            transition(AppointmentState.CONFIRMED, AppointmentEvent.Cancel::class.java, AppointmentState.CANCELLED)
            transition(AppointmentState.CONFIRMED, AppointmentEvent.Reschedule::class.java, AppointmentState.PENDING)
            transition(
                AppointmentState.CONFIRMED,
                AppointmentEvent.RequestReschedule::class.java,
                AppointmentState.PENDING_RESCHEDULE,
            )

            transition(
                AppointmentState.PENDING_RESCHEDULE,
                AppointmentEvent.ConfirmReschedule::class.java,
                AppointmentState.RESCHEDULED,
            )
            transition(
                AppointmentState.PENDING_RESCHEDULE,
                AppointmentEvent.Cancel::class.java,
                AppointmentState.CANCELLED,
            )

            transition(
                AppointmentState.CHECKED_IN,
                AppointmentEvent.StartTreatment::class.java,
                AppointmentState.IN_PROGRESS,
            )
            transition(AppointmentState.CHECKED_IN, AppointmentEvent.Cancel::class.java, AppointmentState.CANCELLED)

            transition(AppointmentState.IN_PROGRESS, AppointmentEvent.Complete::class.java, AppointmentState.COMPLETED)
        }

    private fun invalidTransition(
        currentState: AppointmentState,
        event: AppointmentEvent,
        cause: StateMachineException,
    ): IllegalStateException =
        IllegalStateException(
            "Invalid transition: $currentState + $event. Allowed events: ${allowedEvents(currentState)}",
            cause,
        )
}
