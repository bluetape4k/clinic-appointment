package io.bluetape4k.clinic.appointment.profile

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationTargets
import io.bluetape4k.clinic.appointment.service.ProfileReevaluationTargetResolver
import org.junit.jupiter.api.Test
import java.time.Duration

class ProfileReevaluationTargetResolverTest {

    private val resolver = ProfileReevaluationTargetResolver

    @Test
    fun `clinic 값이 tenant와 플랫폼 기본값보다 우선한다`() {
        val target = resolver.resolve(
            status = AppointmentCommitmentStatus.HELD,
            platform = targets(heldMinutes = 5, proposedMinutes = 30),
            tenant = targets(heldMinutes = 4),
            clinic = targets(heldMinutes = 2),
        )

        target shouldBeEqualTo Duration.ofMinutes(2)
    }

    @Test
    fun `tenant 값이 없으면 플랫폼 환경 기본값을 사용한다`() {
        val target = resolver.resolve(
            status = AppointmentCommitmentStatus.PROPOSED,
            platform = targets(heldMinutes = 5, proposedMinutes = 30),
            tenant = targets(),
            clinic = targets(),
        )

        target shouldBeEqualTo Duration.ofMinutes(30)
    }

    @Test
    fun `확정 예약의 처리 목표는 계산하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                status = AppointmentCommitmentStatus.CONFIRMED,
                platform = targets(heldMinutes = 5, proposedMinutes = 30),
            )
        }
    }

    @Test
    fun `선점 예약 처리 목표는 1분 이상 15분 이하만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                status = AppointmentCommitmentStatus.HELD,
                platform = targets(heldMinutes = 5, proposedMinutes = 30),
                clinic = ProfileReevaluationTargets(
                    heldTarget = Duration.ofSeconds(59),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                status = AppointmentCommitmentStatus.HELD,
                platform = targets(heldMinutes = 5, proposedMinutes = 30),
                clinic = ProfileReevaluationTargets(
                    heldTarget = Duration.ofMinutes(15).plusSeconds(1),
                ),
            )
        }
    }

    @Test
    fun `제안 예약 처리 목표는 5분 이상 120분 이하만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                status = AppointmentCommitmentStatus.PROPOSED,
                platform = targets(heldMinutes = 5, proposedMinutes = 30),
                tenant = ProfileReevaluationTargets(
                    proposedTarget = Duration.ofMinutes(5).minusSeconds(1),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(
                status = AppointmentCommitmentStatus.PROPOSED,
                platform = targets(heldMinutes = 5, proposedMinutes = 30),
                tenant = ProfileReevaluationTargets(
                    proposedTarget = Duration.ofMinutes(120).plusSeconds(1),
                ),
            )
        }
    }

    private fun targets(
        heldMinutes: Long? = null,
        proposedMinutes: Long? = null,
    ) = ProfileReevaluationTargets(
        heldTarget = heldMinutes?.let(Duration::ofMinutes),
        proposedTarget = proposedMinutes?.let(Duration::ofMinutes),
    )
}
