package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ProfileReevaluationAdminServiceTest {

    @Test
    fun `preview는 범위를 제한해 보여주지만 저장 상태를 변경하지 않는다`() {
        runBlocking {
            val store = FakeAdminStore(mutableListOf(failedJob(1L), failedJob(2L, clinicId = 12L)))
            val service = ProfileReevaluationAdminService(store, redriveCooldown = Duration.ZERO)

            val result = service.redrive(command(ProfileReevaluationAdminAction.PREVIEW, clinicId = 11L))

            result.matched shouldBeEqualTo 1
            result.created shouldBeEqualTo 0
            store.redriveCalls shouldBeEqualTo 0
        }
    }

    @Test
    fun `execute는 lineage CAS로 새 attempt를 만들고 같은 key 재호출은 결과를 재사용한다`() {
        runBlocking {
            val store = FakeAdminStore(mutableListOf(failedJob(1L)))
            val auditEvents = mutableListOf<ProfileReevaluationAdminAuditEvent>()
            val service = ProfileReevaluationAdminService(
                store,
                redriveCooldown = Duration.ZERO,
                auditSink = auditEvents::add,
            )
            val command = command(ProfileReevaluationAdminAction.EXECUTE)

            val first = service.redrive(command)
            val second = service.redrive(command)

            first shouldBeEqualTo second
            first.created shouldBeEqualTo 1
            store.redriveCalls shouldBeEqualTo 1
            auditEvents.size shouldBeEqualTo 2
            auditEvents.map { it.replayed } shouldBeEqualTo listOf(false, true)
            auditEvents.any { it.reasonDigest == command.reason } shouldBeEqualTo false
        }
    }

    @Test
    fun `actor reason idempotency key와 범위는 bounded 계약을 따른다`() = runBlocking {
        val service = ProfileReevaluationAdminService(FakeAdminStore())

        listOf(
            command(ProfileReevaluationAdminAction.EXECUTE).copy(actor = ""),
            command(ProfileReevaluationAdminAction.EXECUTE).copy(reason = ""),
            command(ProfileReevaluationAdminAction.EXECUTE).copy(idempotencyKey = "short"),
            command(ProfileReevaluationAdminAction.EXECUTE).copy(limit = 101),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { runBlocking { service.redrive(invalid) } }
        }
    }

    private fun command(
        action: ProfileReevaluationAdminAction,
        clinicId: Long? = null,
    ) =
        ProfileReevaluationAdminCommand(
            action = action,
            actor = "ops-admin",
            reason = "CRM 장애 복구 후 제한된 재처리",
            idempotencyKey = "redrive-20260730-0001",
            scope = ProfileReevaluationAdminScope(
                tenantGroupId = clinicId?.let { 1L },
                clinicId = clinicId,
            ),
            limit = 10,
        )

    private class FakeAdminStore(
        private val failed: MutableList<ProfileReevaluationJobRecord> = mutableListOf(),
    ) : ProfileReevaluationAdminStore {
        var redriveCalls: Int = 0

        override suspend fun snapshot() = ProfileReevaluationOperationalSnapshot(failedJobs = failed.size.toLong())

        override suspend fun findFailed(
            scope: ProfileReevaluationAdminScope,
            limit: Int,
        ): List<ProfileReevaluationJobRecord> =
            failed.filter {
                (scope.tenantGroupId == null || it.scope.tenantGroupId == scope.tenantGroupId) &&
                    (scope.clinicId == null || it.scope.clinicId == scope.clinicId) &&
                    (scope.targetRevision == null || it.targetRevision == scope.targetRevision)
            }.take(limit)

        override suspend fun redrive(
            command: RedriveProfileReevaluationJob,
        ): ProfileReevaluationJobRecord? {
            redriveCalls++
            val original = failed.firstOrNull {
                it.id == command.jobId &&
                    (command.expectedRedriveCount == null || it.redriveCount == command.expectedRedriveCount)
            } ?: return null
            failed.remove(original)
            return original.copy(
                id = original.id + 100,
                status = ProfileReevaluationJobStatus.PENDING,
                redriveOfJobId = original.id,
                redriveGeneration = original.redriveGeneration + 1,
            )
        }
    }

    private fun failedJob(
        id: Long,
        clinicId: Long = 11L,
    ): ProfileReevaluationJobRecord {
        val now = Instant.parse("2026-07-30T00:00:00Z")
        return ProfileReevaluationJobRecord(
            id = id,
            headId = id,
            scope = ProfileReevaluationScope(1L, clinicId, "a".repeat(64)),
            targetRevision = 7L,
            eventId = "event-$id",
            assessmentRef = "assessment/$id",
            assessmentHash = "b".repeat(64),
            status = ProfileReevaluationJobStatus.FAILED,
            occurredAt = now.minusSeconds(600),
            dueAt = now.minusSeconds(300),
            targetDuration = Duration.ofMinutes(5),
            heldTarget = Duration.ofMinutes(5),
            proposedTarget = Duration.ofMinutes(30),
            targetPolicyRef = "policy/profile",
            targetPolicyGeneration = 1L,
            nextAttemptAt = now,
            leaseOwner = null,
            leaseExpiresAt = null,
            attemptCount = 5,
            firstAttemptAt = now.minusSeconds(500),
            redriveCount = 0,
            rootJobId = id,
            redriveOfJobId = null,
            redriveGeneration = 0,
            priorityClass = ProfileReevaluationPriorityClass.HELD_PRESENT,
            heldCursorAppointmentId = null,
            proposedCursorAppointmentId = null,
            scannedCount = 0,
            outcomeCounts = ProfileReevaluationOutcomeCounts(),
            lastFailureCode = "UPSTREAM_UNAVAILABLE",
            createdAt = now.minusSeconds(600),
            updatedAt = now.minusSeconds(60),
        )
    }
}
