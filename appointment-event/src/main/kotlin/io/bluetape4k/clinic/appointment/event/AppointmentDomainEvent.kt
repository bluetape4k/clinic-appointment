package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.springframework.context.ApplicationEvent
import java.io.Serializable
import java.time.Instant

/**
 * 예약 도메인 이벤트의 공통 기반 클래스.
 *
 * @property occurredAt 이벤트 발생 시각
 * @property scope 검증된 tenant/clinic 격리 범위
 */
sealed class AppointmentDomainEvent(
    source: Any,
    val occurredAt: Instant = Instant.now(),
) : ApplicationEvent(source), Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * 예약 생성 이벤트.
     *
     * @property appointmentId 생성된 예약 ID
     * @property scope 예약이 속한 tenant/병원 범위
     */
    data class Created(
        val appointmentId: Long,
        val scope: TenantClinicScope,
    ) : AppointmentDomainEvent(appointmentId), Serializable {
        val tenantGroupId: Long get() = scope.tenantGroupId
        val clinicId: Long get() = scope.clinicId

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 예약 상태 변경 이벤트.
     *
     * @property appointmentId 상태가 변경된 예약 ID
     * @property scope 예약이 속한 tenant/병원 범위
     * @property fromState 변경 전 상태
     * @property toState 변경 후 상태
     * @property reason 상태 변경 사유
     */
    data class StatusChanged(
        val appointmentId: Long,
        val scope: TenantClinicScope,
        val fromState: String,
        val toState: String,
        val reason: String? = null,
    ) : AppointmentDomainEvent(appointmentId), Serializable {
        val tenantGroupId: Long get() = scope.tenantGroupId
        val clinicId: Long get() = scope.clinicId

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 예약 취소 이벤트.
     *
     * @property appointmentId 취소된 예약 ID
     * @property scope 예약이 속한 tenant/병원 범위
     * @property reason 취소 사유
     */
    data class Cancelled(
        val appointmentId: Long,
        val scope: TenantClinicScope,
        val reason: String,
    ) : AppointmentDomainEvent(appointmentId), Serializable {
        val tenantGroupId: Long get() = scope.tenantGroupId
        val clinicId: Long get() = scope.clinicId

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 예약 재배정 이벤트.
     *
     * @property originalId 원본 예약 ID
     * @property newId 재배정된 새 예약 ID
     * @property scope 예약이 속한 tenant/병원 범위
     */
    data class Rescheduled(
        val originalId: Long,
        val newId: Long,
        val scope: TenantClinicScope,
    ) : AppointmentDomainEvent(originalId), Serializable {
        val tenantGroupId: Long get() = scope.tenantGroupId
        val clinicId: Long get() = scope.clinicId

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
