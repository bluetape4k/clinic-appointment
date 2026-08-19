package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.AppointmentItemAppendScope
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalException
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionStatus
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import org.junit.jupiter.api.Test
import java.time.Instant

class RepositoryTransactionContractTest {

    @Test
    fun `단순 record repository는 bluetape LongJdbcRepository 계약을 구현한다`() {
        LongJdbcRepository::class.java.isAssignableFrom(AppointmentIdempotencyRepository::class.java).shouldBeTrue()
        LongJdbcRepository::class.java.isAssignableFrom(AppointmentStateHistoryRepository::class.java).shouldBeTrue()
        LongJdbcRepository::class.java.isAssignableFrom(TreatmentSpaceRepository::class.java).shouldBeTrue()
    }

    @Test
    fun `Composite DSL은 모든 public 진입점이 caller transaction 없이 명시적인 guard로 실패한다`() {
        val repository = AppointmentItemRepository()
        listOf(
            runCatching {
                repository.appendValidated(
                    scope = AppointmentItemAppendScope(
                        appointmentId = 1L,
                        proposalId = 1L,
                        tenantGroupId = 1L,
                        clinicId = 1L,
                        patientReferenceFingerprint = "f".repeat(64),
                    ),
                    items = emptyList(),
                )
            },
            runCatching {
                repository.requireResourceReferences(
                    proposalId = 1L,
                    requests = emptyList(),
                )
            },
        ).forEach { result ->
            val failure = checkNotNull(result.exceptionOrNull())
            (failure is IllegalStateException && failure.message.orEmpty().contains("AppointmentItemRepository"))
                .shouldBeTrue()
        }
    }

    @Test
    fun `append DSL은 모든 public 상태 전이 진입점이 caller transaction 없이 명시적인 guard로 실패한다`() {
        val repository = AppointmentOperationalExceptionRepository()
        val exception = AppointmentOperationalException(
            appointmentPlanId = 1L,
            appointmentId = null,
            type = AppointmentOperationalExceptionType.OTHER,
            reasonCode = "CONTRACT_TEST",
            status = AppointmentOperationalExceptionStatus.OPEN,
            openedAt = Instant.parse("2026-08-19T00:00:00Z"),
            resolvedAt = null,
        )
        listOf(
            runCatching { repository.append(exception) },
            runCatching { repository.acknowledge(1L) },
            runCatching { repository.resolve(1L, Instant.parse("2026-08-19T00:01:00Z")) },
        ).forEach { result ->
            val failure = checkNotNull(result.exceptionOrNull())
            (failure is IllegalStateException && failure.message.orEmpty().contains("AppointmentOperationalExceptionRepository"))
                .shouldBeTrue()
        }
    }
}
