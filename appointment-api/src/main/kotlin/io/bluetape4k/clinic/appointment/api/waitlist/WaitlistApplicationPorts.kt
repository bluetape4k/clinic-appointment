package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.ClaimWaitlistOfferCommand
import io.bluetape4k.clinic.appointment.model.waitlist.OfferClaimed
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/**
 * waitlist claim 결과를 commitment command adapter가 이해하는 replacement 계획으로 바꿉니다.
 *
 * 구현체는 Plan, policy, consent evidence, projection target을 같은 business transaction에서
 * 다시 조회해야 하며 request body를 권위로 사용하면 안 됩니다.
 */
fun interface WaitlistReplacementCommandPlanner {
    fun plan(command: WaitlistOfferConfirmationCommand, claim: OfferClaimed): WaitlistReplacementPlan
}

/**
 * offer claim core service를 application transaction에 연결하는 포트입니다.
 */
fun interface WaitlistOfferClaimPort {
    fun claim(command: ClaimWaitlistOfferCommand): OfferClaimed
}

/**
 * replacement appointment 생성 경계입니다.
 *
 * 구현체는 호출자가 연 Exposed transaction을 공유해야 합니다. 별도 transaction이나 외부
 * irreversible side effect를 여기서 실행하면 waitlist hold consume과 원자성이 깨집니다.
 */
fun interface WaitlistReplacementAppointmentPort {
    fun confirm(plan: WaitlistReplacementPlan): WaitlistReplacementAppointment
}

/**
 * replacement appointment 생성 뒤 accepted hold를 terminal consumed로 닫는 포트입니다.
 */
fun interface WaitlistCapacityHoldConsumptionPort {
    fun consume(scope: WaitlistScope, holdId: Long, now: Instant)
}

/**
 * waitlist staff command의 durable idempotency reservation 포트입니다.
 *
 * [reserve]는 business transaction보다 먼저 commit되어야 합니다. business transaction이
 * 실패하면 [completeFailed]가 stable failure replay를 기록합니다.
 */
interface WaitlistCommandReservationPort {
    fun reserve(command: WaitlistOfferConfirmationCommand, now: Instant): WaitlistApplicationReservation

    /**
     * `PROCESSING` replay 또는 success 기록 실패 복구 시 replacement appointment를 재조회합니다.
     *
     * business transaction이 성공한 뒤 [completeSucceeded] commit 전에 process가 죽거나 기록이
     * 실패하면 reservation row는 `PROCESSING`으로 남아야 합니다. 구현체는 이 method에서
     * command scope/request digest로 이미 생성된 replacement appointment를 찾아 stable success
     * replay로 전환하거나, 아직 확인할 수 없으면 [WaitlistApplicationReservation.InProgress]를 반환합니다.
     */
    fun reconcileInProgress(command: WaitlistOfferConfirmationCommand, now: Instant): WaitlistApplicationReservation

    fun completeSucceeded(
        recordId: Long,
        command: WaitlistOfferConfirmationCommand,
        appointmentId: Long,
        now: Instant,
    )

    fun completeFailed(
        recordId: Long,
        command: WaitlistOfferConfirmationCommand,
        failureCode: String,
        now: Instant,
    )
}

/**
 * 취소 command를 실행하고 vacancy 생성에 필요한 비민감 snapshot을 반환하는 경계입니다.
 */
fun interface WaitlistCancellationPort {
    fun cancel(command: WaitlistCancellationVacancyCommand): WaitlistCancelledAppointment
}

/**
 * 취소로 생긴 vacancy를 durable waitlist job으로 여는 경계입니다.
 */
fun interface WaitlistVacancyOpeningPort {
    fun open(cancelled: WaitlistCancelledAppointment): WaitlistOpenedVacancy
}

/**
 * replacement appointment adapter에 넘기는 bounded 계획입니다.
 */
data class WaitlistReplacementPlan(
    val offerId: Long,
    val holdId: Long,
    val memberReference: String,
) : Serializable {
    init {
        offerId.requirePositiveNumber("offerId")
        holdId.requirePositiveNumber("holdId")
        memberReference.requireNotBlank("memberReference")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** replacement appointment 생성 결과의 최소 application snapshot입니다. */
data class WaitlistReplacementAppointment(
    val appointmentId: Long,
    val proposalId: Long,
) : Serializable {
    init {
        appointmentId.requirePositiveNumber("appointmentId")
        proposalId.requirePositiveNumber("proposalId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** cancellation 이후 vacancy opening에 전달되는 bounded snapshot입니다. */
data class WaitlistCancelledAppointment(
    val appointmentId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val occurredAt: Instant,
) : Serializable {
    init {
        appointmentId.requirePositiveNumber("appointmentId")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** vacancy opening 결과의 최소 snapshot입니다. */
data class WaitlistOpenedVacancy(
    val vacancyJobId: Long,
) : Serializable {
    init {
        vacancyJobId.requirePositiveNumber("vacancyJobId")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed interface WaitlistApplicationReservation : Serializable {
    data class Acquired(val recordId: Long) : WaitlistApplicationReservation {
        init {
            recordId.requirePositiveNumber("recordId")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data object InProgress : WaitlistApplicationReservation

    data class ReplaySucceeded(val appointmentId: Long) : WaitlistApplicationReservation {
        init {
            appointmentId.requirePositiveNumber("appointmentId")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class ReplayFailed(val failureCode: String) : WaitlistApplicationReservation {
        init {
            failureCode.requireNotBlank("failureCode")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
