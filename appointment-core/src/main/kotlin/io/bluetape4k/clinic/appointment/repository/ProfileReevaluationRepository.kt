package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationCursor
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationHeadRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeCounts
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationOutcomeRecord
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 프로필 변경을 latest revision 작업으로 병합하고 worker lease를 fencing하는 저장소입니다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행합니다. 저장소는
 * 중첩 transaction을 열지 않습니다. claim과 이후의 모든 전이는 데이터베이스
 * `CURRENT_TIMESTAMP`를 기준으로 하며, head 행 잠금과 lease owner 조건을 함께 사용해
 * 오래된 revision 또는 오래된 worker의 쓰기를 거부합니다.
 *
 * @param hasHeldAppointments 첫 claim에서 해당 환자 범위에 `HELD` 예약이 존재하는지
 * indexed existence query로 확인하는 함수입니다. 호출자의 현재 transaction 안에서 실행됩니다.
 */
class ProfileReevaluationRepository(
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val retryDelay: Duration = Duration.ofSeconds(5),
    private val maxAttempts: Int = 5,
    private val hasHeldAppointments: (ProfileReevaluationScope) -> Boolean = { true },
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
        require(!retryDelay.isNegative) { "retryDelay must be non-negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * 더 최신 revision만 head와 runnable 작업으로 반영합니다.
     *
     * 같은 revision, 같은 event 재수신, 이전 revision 지연 도착은 저장 상태를 변경하지 않습니다.
     */
    fun upsertEvent(command: UpsertProfileChange): ProfileReevaluationHeadRecord {
        bootstrapHead(command.scope)
        val head = findHeadRow(command.scope, forUpdate = true)
            ?: error("profile reevaluation head bootstrap failed")
        if (command.revision <= head[ProfileReevaluationHeads.latestRevision]) {
            return head.toHeadRecord()
        }

        val dbNow = dbCurrentTimestamp()
        val headId = head[ProfileReevaluationHeads.id].value
        ProfileReevaluationHeads.update({ ProfileReevaluationHeads.id eq headId }) {
            it[latestRevision] = command.revision
            it[latestEventId] = command.eventId
            it[assessmentRef] = command.assessmentRef
            it[assessmentHash] = command.assessmentHash
            it[occurredAt] = command.occurredAt
            it[updatedAt] = dbNow
        }
        ProfileReevaluationJobs.update({
            (ProfileReevaluationJobs.headId eq EntityID(headId, ProfileReevaluationHeads)) and
                (ProfileReevaluationJobs.targetRevision lessEq command.revision - 1L) and
                (ProfileReevaluationJobs.status inList READY_STATES)
        }) {
            it[status] = ProfileReevaluationJobStatus.STALE
            it[leaseOwner] = null
            it[leaseExpiresAt] = null
            it[updatedAt] = dbNow
        }

        val jobId = ProfileReevaluationJobs.insertAndGetId {
            it[ProfileReevaluationJobs.headId] = EntityID(headId, ProfileReevaluationHeads)
            it[tenantGroupId] = command.scope.tenantGroupId
            it[clinicId] = command.scope.clinicId
            it[patientReferenceFingerprint] = command.scope.patientReferenceFingerprint
            it[targetRevision] = command.revision
            it[eventId] = command.eventId
            it[assessmentRef] = command.assessmentRef
            it[assessmentHash] = command.assessmentHash
            it[status] = ProfileReevaluationJobStatus.PENDING
            it[occurredAt] = command.occurredAt
            val earliestTarget = minOf(command.heldTarget, command.proposedTarget)
            it[dueAt] = command.occurredAt.plus(earliestTarget)
            it[targetDurationSeconds] = earliestTarget.seconds
            it[heldTargetSeconds] = command.heldTarget.seconds
            it[proposedTargetSeconds] = command.proposedTarget.seconds
            it[targetPolicyRef] = command.targetPolicyRef
            it[targetPolicyGeneration] = command.targetPolicyGeneration
            it[nextAttemptAt] = command.occurredAt
            it[priorityClass] = ProfileReevaluationPriorityClass.UNCLASSIFIED
        }.value
        ProfileReevaluationJobs.update({ ProfileReevaluationJobs.id eq jobId }) {
            it[rootJobId] = jobId
        }
        return requireNotNull(findHead(command.scope))
    }

    /**
     * 실행 가능한 작업을 due 순서로 읽되 병원별 개수를 제한하여 선점합니다.
     */
    fun claimFairJobs(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord> {
        val dbNow = dbCurrentTimestamp()
        val candidates = ProfileReevaluationJobs
            .selectAll()
            .where {
                (
                    (ProfileReevaluationJobs.status inList READY_STATES) and
                        (ProfileReevaluationJobs.nextAttemptAt lessEq dbNow) and
                        (ProfileReevaluationJobs.dueAt lessEq dbNow)
                    ) or
                    (
                        (ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.RUNNING) and
                            (ProfileReevaluationJobs.leaseExpiresAt lessEq dbNow)
                        )
            }
            .orderBy(
                ProfileReevaluationJobs.dueAt to SortOrder.ASC,
                ProfileReevaluationJobs.id to SortOrder.ASC,
            )
            .limit(MAX_CLAIM_CANDIDATES)
            .toList()

        val fairCandidates = fairClaimOrder(candidates, command.perClinicLimit)
        val claimed = ArrayList<ProfileReevaluationJobRecord>(command.limit)
        for (candidate in fairCandidates) {
            if (claimed.size >= command.limit) break
            val job =
                claimCandidate(
                    candidate = candidate.row,
                    owner = command.leaseOwner,
                    dbNow = dbNow,
                    priority = candidate.priority,
                ) ?: continue
            claimed += job
        }
        return claimed
    }

    /**
     * 최신 revision의 현재 worker에게만 lease 연장을 허용합니다.
     */
    fun renewLease(jobId: Long, revision: Long, leaseOwner: String): Boolean {
        validateTransitionIdentity(jobId, revision, leaseOwner)
        val dbNow = dbCurrentTimestamp()
        if (!matchesCurrentRevision(jobId, revision)) return false
        return ProfileReevaluationJobs.update({
            activeLeasePredicate(jobId, revision, leaseOwner, dbNow)
        }) {
            it[leaseExpiresAt] = dbNow.plus(leaseDuration)
            it[updatedAt] = dbNow
        } == 1
    }

    /**
     * 최신 revision의 현재 worker에게만 단조 증가 cursor와 bounded count 저장을 허용합니다.
     */
    fun advanceCursor(
        jobId: Long,
        revision: Long,
        leaseOwner: String,
        cursor: ProfileReevaluationCursor,
    ): Boolean {
        validateTransitionIdentity(jobId, revision, leaseOwner)
        val dbNow = dbCurrentTimestamp()
        if (!matchesCurrentRevision(jobId, revision)) return false
        val row = findJobRow(jobId, forUpdate = true) ?: return false
        if (!hasActiveLease(row, revision, leaseOwner, dbNow)) return false
        if (!cursorIsMonotonic(row, cursor)) return false

        val scanned = addBounded(row[ProfileReevaluationJobs.scannedCount], cursor.scannedDelta)
        val currentCounts = row.toOutcomeCounts()
        val nextCounts = addBounded(currentCounts, cursor.outcomeDeltas)
        if (nextCounts.values().sum() > scanned) return false

        return ProfileReevaluationJobs.update({
            activeLeasePredicate(jobId, revision, leaseOwner, dbNow)
        }) {
            cursor.heldCursorAppointmentId?.let { value -> it[heldCursorAppointmentId] = value }
            cursor.proposedCursorAppointmentId?.let { value -> it[proposedCursorAppointmentId] = value }
            it[scannedCount] = scanned
            it[proposalSupersededCount] = nextCounts.proposalSuperseded
            it[holdKeptCount] = nextCounts.holdKept
            it[holdReplacedCount] = nextCounts.holdReplaced
            it[fallbackToProposedCount] = nextCounts.fallbackToProposed
            it[skippedIneligibleCount] = nextCounts.skippedIneligible
            it[skippedUnchangedCount] = nextCounts.skippedUnchanged
            it[updatedAt] = dbNow
        } == 1
    }

    /**
     * 현재 worker의 실패를 재시도 대기 또는 최종 실패로 전이합니다.
     */
    fun scheduleRetry(
        jobId: Long,
        revision: Long,
        leaseOwner: String,
        failureCode: String,
        delay: Duration = retryDelay,
        terminal: Boolean = false,
    ): Boolean {
        validateTransitionIdentity(jobId, revision, leaseOwner)
        require(FAILURE_CODE_REGEX.matches(failureCode)) {
            "failureCode must contain 1..96 uppercase identifier characters"
        }
        require(!delay.isNegative) { "delay must be non-negative" }
        val dbNow = dbCurrentTimestamp()
        if (!matchesCurrentRevision(jobId, revision)) return false
        val row = findJobRow(jobId, forUpdate = true) ?: return false
        if (!hasActiveLease(row, revision, leaseOwner, dbNow)) return false
        val exhausted = terminal || row[ProfileReevaluationJobs.attemptCount] >= maxAttempts

        return ProfileReevaluationJobs.update({
            activeLeasePredicate(jobId, revision, leaseOwner, dbNow)
        }) {
            it[status] = if (exhausted) {
                ProfileReevaluationJobStatus.FAILED
            } else {
                ProfileReevaluationJobStatus.RETRY_WAIT
            }
            it[nextAttemptAt] = dbNow.plus(delay)
            it[ProfileReevaluationJobs.leaseOwner] = null
            it[leaseExpiresAt] = null
            it[lastFailureCode] = failureCode
            it[updatedAt] = dbNow
        } == 1
    }

    /**
     * 최신 revision의 현재 worker만 작업을 완료할 수 있습니다.
     */
    fun complete(jobId: Long, revision: Long, leaseOwner: String): Boolean {
        validateTransitionIdentity(jobId, revision, leaseOwner)
        val dbNow = dbCurrentTimestamp()
        if (!matchesCurrentRevision(jobId, revision)) return false
        return ProfileReevaluationJobs.update({
            activeLeasePredicate(jobId, revision, leaseOwner, dbNow)
        }) {
            it[status] = ProfileReevaluationJobStatus.COMPLETED
            it[ProfileReevaluationJobs.leaseOwner] = null
            it[leaseExpiresAt] = null
            it[lastFailureCode] = null
            it[updatedAt] = dbNow
        } == 1
    }

    /**
     * worker가 관찰한 더 최신 head revision과 일치할 때만 실행 중 작업을 stale로 닫습니다.
     */
    fun markStale(jobId: Long, observedRevision: Long, leaseOwner: String): Boolean {
        require(observedRevision > 0) { "observedRevision must be positive" }
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
        val dbNow = dbCurrentTimestamp()
        val row = findJobRow(jobId, forUpdate = true) ?: return false
        val targetRevision = row[ProfileReevaluationJobs.targetRevision]
        if (observedRevision <= targetRevision) return false
        val head = findHeadRow(row.toScope(), forUpdate = true) ?: return false
        if (head[ProfileReevaluationHeads.latestRevision] != observedRevision) return false
        if (!hasActiveLease(row, targetRevision, leaseOwner, dbNow)) return false

        return ProfileReevaluationJobs.update({
            activeLeasePredicate(jobId, targetRevision, leaseOwner, dbNow)
        }) {
            it[status] = ProfileReevaluationJobStatus.STALE
            it[ProfileReevaluationJobs.leaseOwner] = null
            it[leaseExpiresAt] = null
            it[updatedAt] = dbNow
        } == 1
    }

    /**
     * 실패 원본을 유지하고 lineage generation이 증가한 새 작업을 만듭니다.
     */
    fun redriveFailed(command: RedriveProfileReevaluationJob): ProfileReevaluationJobRecord? {
        val dbNow = dbCurrentTimestamp()
        val original = findJobRow(command.jobId, forUpdate = true) ?: return null
        if (original[ProfileReevaluationJobs.status] != ProfileReevaluationJobStatus.FAILED) return null
        if (original[ProfileReevaluationJobs.updatedAt].plus(command.cooldown) > dbNow) return null
        val currentRedriveCount = original[ProfileReevaluationJobs.redriveCount]
        if (currentRedriveCount > 0) return null
        if (command.expectedRedriveCount != null && command.expectedRedriveCount != currentRedriveCount) {
            return null
        }
        val revision = original[ProfileReevaluationJobs.targetRevision]
        if (!matchesCurrentRevision(command.jobId, revision)) return null

        val updated = ProfileReevaluationJobs.update({
            (ProfileReevaluationJobs.id eq command.jobId) and
                (ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.FAILED) and
                (ProfileReevaluationJobs.redriveCount eq currentRedriveCount)
        }) {
            it[redriveCount] = currentRedriveCount + 1
            it[updatedAt] = dbNow
        }
        if (updated != 1) return null

        val rootId = requireNotNull(original[ProfileReevaluationJobs.rootJobId])
        val nextGeneration = original[ProfileReevaluationJobs.redriveGeneration] + 1
        val newId = ProfileReevaluationJobs.insertAndGetId {
            it[headId] = original[ProfileReevaluationJobs.headId]
            it[tenantGroupId] = original[ProfileReevaluationJobs.tenantGroupId]
            it[clinicId] = original[ProfileReevaluationJobs.clinicId]
            it[patientReferenceFingerprint] = original[ProfileReevaluationJobs.patientReferenceFingerprint]
            it[targetRevision] = revision
            it[eventId] = original[ProfileReevaluationJobs.eventId]
            it[assessmentRef] = original[ProfileReevaluationJobs.assessmentRef]
            it[assessmentHash] = original[ProfileReevaluationJobs.assessmentHash]
            it[status] = ProfileReevaluationJobStatus.PENDING
            it[occurredAt] = original[ProfileReevaluationJobs.occurredAt]
            it[dueAt] = dbNow
            it[targetDurationSeconds] = original[ProfileReevaluationJobs.targetDurationSeconds]
            it[heldTargetSeconds] = original[ProfileReevaluationJobs.heldTargetSeconds]
            it[proposedTargetSeconds] = original[ProfileReevaluationJobs.proposedTargetSeconds]
            it[targetPolicyRef] = original[ProfileReevaluationJobs.targetPolicyRef]
            it[targetPolicyGeneration] = original[ProfileReevaluationJobs.targetPolicyGeneration]
            it[nextAttemptAt] = dbNow
            it[rootJobId] = rootId
            it[redriveOfJobId] = command.jobId
            it[redriveGeneration] = nextGeneration
            it[priorityClass] = original[ProfileReevaluationJobs.priorityClass]
        }.value
        return findJob(newId)
    }

    /**
     * 같은 작업·revision·예약의 결과를 한 번만 기록합니다.
     */
    fun recordOutcome(
        jobId: Long,
        revision: Long,
        appointmentId: Long,
        outcomeType: ProfileReevaluationOutcomeType,
    ): ProfileReevaluationOutcomeRecord {
        require(jobId > 0 && revision > 0 && appointmentId > 0) {
            "jobId, revision and appointmentId must be positive"
        }
        ProfileReevaluationOutcomes.insertIgnore {
            it[ProfileReevaluationOutcomes.jobId] = EntityID(jobId, ProfileReevaluationJobs)
            it[targetRevision] = revision
            it[ProfileReevaluationOutcomes.appointmentId] = appointmentId
            it[ProfileReevaluationOutcomes.outcomeType] = outcomeType
        }
        return ProfileReevaluationOutcomes
            .selectAll()
            .where {
                (ProfileReevaluationOutcomes.jobId eq EntityID(jobId, ProfileReevaluationJobs)) and
                    (ProfileReevaluationOutcomes.targetRevision eq revision) and
                    (ProfileReevaluationOutcomes.appointmentId eq appointmentId)
            }
            .single()
            .toOutcomeRecord()
    }

    /** 같은 작업에서 이미 완료한 예약 결과를 반환합니다. */
    fun findOutcome(
        jobId: Long,
        appointmentId: Long,
    ): ProfileReevaluationOutcomeRecord? {
        require(jobId > 0 && appointmentId > 0) {
            "jobId and appointmentId must be positive"
        }
        return ProfileReevaluationOutcomes
            .selectAll()
            .where {
                (ProfileReevaluationOutcomes.jobId eq EntityID(jobId, ProfileReevaluationJobs)) and
                    (ProfileReevaluationOutcomes.appointmentId eq appointmentId)
            }.singleOrNull()
            ?.toOutcomeRecord()
    }

    /** 작업이 해당 환자 범위의 최신 revision을 계속 가리키는지 반환합니다. */
    fun isCurrentRevision(
        jobId: Long,
        revision: Long,
    ): Boolean {
        require(jobId > 0 && revision > 0) { "jobId and revision must be positive" }
        return matchesCurrentRevision(jobId, revision)
    }

    /**
     * head와 job을 ingress와 같은 순서로 잠근 뒤 최신 revision 여부를 다시 검증합니다.
     *
     * appointment 최종 transaction이 이 fencing을 통과해야 더 최신 event와 경합한
     * 오래된 worker가 뒤늦게 예약 상태를 commit하지 못합니다.
     */
    fun lockCurrentRevision(
        jobId: Long,
        revision: Long,
    ): Boolean {
        require(jobId > 0 && revision > 0) { "jobId and revision must be positive" }
        val observedJob = findJobRow(jobId, forUpdate = false) ?: return false
        val head = findHeadRow(observedJob.toScope(), forUpdate = true) ?: return false
        if (head[ProfileReevaluationHeads.latestRevision] != revision) return false
        val lockedJob = findJobRow(jobId, forUpdate = true) ?: return false
        return lockedJob[ProfileReevaluationJobs.targetRevision] == revision &&
            lockedJob[ProfileReevaluationJobs.headId] == observedJob[ProfileReevaluationJobs.headId]
    }

    fun findHead(scope: ProfileReevaluationScope): ProfileReevaluationHeadRecord? =
        findHeadRow(scope, forUpdate = false)?.toHeadRecord()

    fun findJob(jobId: Long): ProfileReevaluationJobRecord? =
        findJobRow(jobId, forUpdate = false)?.toJobRecord()

    /** 자동 redrive 검토 대상을 오래된 최종 실패부터 제한된 개수로 반환합니다. */
    fun findFailedJobs(limit: Int): List<ProfileReevaluationJobRecord> {
        require(limit in 1..MAX_FAILED_JOB_PAGE_SIZE) {
            "limit must be in 1..$MAX_FAILED_JOB_PAGE_SIZE"
        }
        return ProfileReevaluationJobs
            .selectAll()
            .where { ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.FAILED }
            .orderBy(
                ProfileReevaluationJobs.updatedAt to SortOrder.ASC,
                ProfileReevaluationJobs.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toJobRecord() }
    }

    /**
     * 운영자 redrive 화면에 필요한 실패 작업만 제한된 범위와 개수로 반환합니다.
     *
     * 환자 지문은 반환 record 내부에 남아 있지만 API projection에서 절대 노출하지 않습니다.
     */
    fun findFailedJobs(
        tenantGroupId: Long?,
        clinicId: Long?,
        targetRevision: Long?,
        limit: Int,
    ): List<ProfileReevaluationJobRecord> {
        require(tenantGroupId == null || tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId == null || clinicId > 0) { "clinicId must be positive" }
        require(targetRevision == null || targetRevision > 0) { "targetRevision must be positive" }
        require(limit in 1..MAX_FAILED_JOB_PAGE_SIZE) {
            "limit must be in 1..$MAX_FAILED_JOB_PAGE_SIZE"
        }
        var predicate = ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.FAILED
        if (tenantGroupId != null) {
            predicate = predicate and (ProfileReevaluationJobs.tenantGroupId eq tenantGroupId)
        }
        if (clinicId != null) {
            predicate = predicate and (ProfileReevaluationJobs.clinicId eq clinicId)
        }
        if (targetRevision != null) {
            predicate = predicate and (ProfileReevaluationJobs.targetRevision eq targetRevision)
        }
        return ProfileReevaluationJobs
            .selectAll()
            .where { predicate }
            .orderBy(
                ProfileReevaluationJobs.updatedAt to SortOrder.ASC,
                ProfileReevaluationJobs.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toJobRecord() }
    }

    /**
     * health와 내부 운영 endpoint가 사용할 식별자 없는 작업 집계를 반환합니다.
     */
    fun summarizeOperations(): ProfileReevaluationRepositorySummary {
        val dbNow = dbCurrentTimestamp()
        fun count(status: ProfileReevaluationJobStatus): Long =
            ProfileReevaluationJobs.selectAll()
                .where { ProfileReevaluationJobs.status eq status }
                .count()

        val oldestDueAt =
            ProfileReevaluationJobs
                .selectAll()
                .where {
                    ProfileReevaluationJobs.status inList listOf(
                        ProfileReevaluationJobStatus.PENDING,
                        ProfileReevaluationJobStatus.RUNNING,
                        ProfileReevaluationJobStatus.RETRY_WAIT,
                    )
                }
                .orderBy(ProfileReevaluationJobs.dueAt to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.get(ProfileReevaluationJobs.dueAt)
        val activeLeases =
            ProfileReevaluationJobs.selectAll()
                .where {
                    (ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.RUNNING) and
                        (ProfileReevaluationJobs.leaseExpiresAt greater dbNow)
                }
                .count()

        return ProfileReevaluationRepositorySummary(
            pendingJobs = count(ProfileReevaluationJobStatus.PENDING),
            runningJobs = count(ProfileReevaluationJobStatus.RUNNING),
            retryWaitJobs = count(ProfileReevaluationJobStatus.RETRY_WAIT),
            failedJobs = count(ProfileReevaluationJobStatus.FAILED),
            activeLeases = activeLeases,
            oldestBacklogAge =
                oldestDueAt
                    ?.let { Duration.between(it, dbNow).coerceAtLeast(Duration.ZERO) }
                    ?: Duration.ZERO,
        )
    }

    /**
     * 기존 비종료 작업 한 건의 처리 목표를 갱신하되 이미 약속한 due 시각은 늦추지 않습니다.
     *
     * 병원 정책 변경의 대량 반영은 bounded keyset runner가 이 primitive를 호출해야 합니다.
     */
    fun advanceTargets(
        jobId: Long,
        heldTarget: Duration,
        proposedTarget: Duration,
        targetPolicyRef: String,
        targetPolicyGeneration: Long,
    ): ProfileReevaluationJobRecord? {
        require(jobId > 0) { "jobId must be positive" }
        require(heldTarget.isPositive && proposedTarget.isPositive) {
            "profile reevaluation targets must be positive"
        }
        require(targetPolicyRef.isNotBlank() && targetPolicyRef.length <= 256) {
            "targetPolicyRef must contain 1..256 characters"
        }
        require(targetPolicyGeneration > 0) { "targetPolicyGeneration must be positive" }
        val row = findJobRow(jobId, forUpdate = true) ?: return null
        if (row[ProfileReevaluationJobs.status] !in NON_TERMINAL_STATES) return null
        val earliestTarget = minOf(heldTarget, proposedTarget)
        val advancedDueAt = row[ProfileReevaluationJobs.occurredAt].plus(earliestTarget)
        val pinnedDueAt = minOf(row[ProfileReevaluationJobs.dueAt], advancedDueAt)
        ProfileReevaluationJobs.update({
            (ProfileReevaluationJobs.id eq jobId) and
                (ProfileReevaluationJobs.status inList NON_TERMINAL_STATES)
        }) {
            it[dueAt] = pinnedDueAt
            it[targetDurationSeconds] = earliestTarget.seconds
            it[heldTargetSeconds] = heldTarget.seconds
            it[proposedTargetSeconds] = proposedTarget.seconds
            it[ProfileReevaluationJobs.targetPolicyRef] = targetPolicyRef
            it[ProfileReevaluationJobs.targetPolicyGeneration] = targetPolicyGeneration
            it[updatedAt] = dbCurrentTimestamp()
        }
        return findJob(jobId)
    }

    fun findJobs(scope: ProfileReevaluationScope): List<ProfileReevaluationJobRecord> =
        ProfileReevaluationJobs
            .selectAll()
            .where { jobScopePredicate(scope) }
            .orderBy(ProfileReevaluationJobs.id to SortOrder.ASC)
            .map { it.toJobRecord() }

    fun findRunnableJobs(scope: ProfileReevaluationScope): List<ProfileReevaluationJobRecord> =
        ProfileReevaluationJobs
            .selectAll()
            .where {
                jobScopePredicate(scope) and
                    (ProfileReevaluationJobs.status inList READY_STATES)
            }
            .orderBy(ProfileReevaluationJobs.id to SortOrder.ASC)
            .map { it.toJobRecord() }

    private fun claimCandidate(
        candidate: ResultRow,
        owner: String,
        dbNow: Instant,
        priority: ProfileReevaluationPriorityClass,
    ): ProfileReevaluationJobRecord? {
        val jobId = candidate[ProfileReevaluationJobs.id].value
        val revision = candidate[ProfileReevaluationJobs.targetRevision]
        val head = findHeadRow(candidate.toScope(), forUpdate = true) ?: return null
        if (head[ProfileReevaluationHeads.latestRevision] != revision) {
            ProfileReevaluationJobs.update({
                (ProfileReevaluationJobs.id eq jobId) and
                    (ProfileReevaluationJobs.status inList NON_TERMINAL_STATES)
            }) {
                it[status] = ProfileReevaluationJobStatus.STALE
                it[leaseOwner] = null
                it[leaseExpiresAt] = null
                it[updatedAt] = dbNow
            }
            return null
        }

        val targetSeconds = if (priority == ProfileReevaluationPriorityClass.PROPOSED_ONLY) {
            candidate[ProfileReevaluationJobs.proposedTargetSeconds]
        } else {
            candidate[ProfileReevaluationJobs.heldTargetSeconds]
        }
        val targetDueAt = candidate[ProfileReevaluationJobs.occurredAt].plusSeconds(targetSeconds)
        val pinnedDueAt = minOf(candidate[ProfileReevaluationJobs.dueAt], targetDueAt)
        val eligible =
            (
                (ProfileReevaluationJobs.status inList READY_STATES) and
                    (ProfileReevaluationJobs.nextAttemptAt lessEq dbNow) and
                    (ProfileReevaluationJobs.dueAt lessEq dbNow)
                ) or
                (
                    (ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.RUNNING) and
                        (ProfileReevaluationJobs.leaseExpiresAt lessEq dbNow)
                    )
        val updated = ProfileReevaluationJobs.update({
            (ProfileReevaluationJobs.id eq jobId) and
                (ProfileReevaluationJobs.targetRevision eq revision) and eligible
        }) {
            it[status] = ProfileReevaluationJobStatus.RUNNING
            it[leaseOwner] = owner
            it[leaseExpiresAt] = dbNow.plus(leaseDuration)
            it[attemptCount] = candidate[ProfileReevaluationJobs.attemptCount] + 1
            if (candidate[ProfileReevaluationJobs.firstAttemptAt] == null) {
                it[firstAttemptAt] = dbNow
            }
            it[priorityClass] = priority
            it[dueAt] = pinnedDueAt
            it[targetDurationSeconds] = targetSeconds
            it[updatedAt] = dbNow
        }
        return if (updated == 1) findJob(jobId) else null
    }

    /**
     * 제한된 후보 집합을 병원별 queue로 나눈 뒤 한 병원에서 한 건씩 순환합니다.
     *
     * queue 내부에서는 처리 목표 시각이 오래된 작업을 먼저 선택하고, 같은 시각이면
     * 선점 예약이 있는 작업을 우선합니다. 이 순서는 큰 병원 하나가 선점 batch를
     * 독점하지 못하게 하면서도 제안 작업의 처리 목표를 aging 기준으로 보장합니다.
     */
    private fun fairClaimOrder(
        candidates: List<ResultRow>,
        perClinicLimit: Int,
    ): List<FairClaimCandidate> {
        val prepared =
            candidates.map { row ->
                val priority =
                    when (val current = row[ProfileReevaluationJobs.priorityClass]) {
                        ProfileReevaluationPriorityClass.UNCLASSIFIED ->
                            if (hasHeldAppointments(row.toScope())) {
                                ProfileReevaluationPriorityClass.HELD_PRESENT
                            } else {
                                ProfileReevaluationPriorityClass.PROPOSED_ONLY
                            }
                        else -> current
                    }
                val targetSeconds =
                    if (priority == ProfileReevaluationPriorityClass.PROPOSED_ONLY) {
                        row[ProfileReevaluationJobs.proposedTargetSeconds]
                    } else {
                        row[ProfileReevaluationJobs.heldTargetSeconds]
                    }
                FairClaimCandidate(
                    row = row,
                    clinicKey =
                        ClinicKey(
                            row[ProfileReevaluationJobs.tenantGroupId],
                            row[ProfileReevaluationJobs.clinicId],
                        ),
                    priority = priority,
                    effectiveDueAt =
                        minOf(
                            row[ProfileReevaluationJobs.dueAt],
                            row[ProfileReevaluationJobs.occurredAt].plusSeconds(targetSeconds),
                        ),
                    jobId = row[ProfileReevaluationJobs.id].value,
                )
            }

        val comparator =
            compareBy<FairClaimCandidate>(
                { it.effectiveDueAt },
                { it.priority.claimRank },
                { it.jobId },
            )
        val queues =
            prepared
                .groupBy(FairClaimCandidate::clinicKey)
                .mapValues { (_, jobs) -> ArrayDeque(jobs.sortedWith(comparator).take(perClinicLimit)) }
                .toList()
                .sortedWith(
                    compareBy<Pair<ClinicKey, ArrayDeque<FairClaimCandidate>>>(
                        { it.second.first().effectiveDueAt },
                        { it.second.first().priority.claimRank },
                        { it.first.tenantGroupId },
                        { it.first.clinicId },
                    ),
                )
                .map { it.second }

        return buildList {
            var remaining = true
            while (remaining) {
                remaining = false
                for (queue in queues) {
                    if (queue.isNotEmpty()) {
                        add(queue.removeFirst())
                        remaining = true
                    }
                }
            }
        }
    }

    private val ProfileReevaluationPriorityClass.claimRank: Int
        get() =
            when (this) {
                ProfileReevaluationPriorityClass.HELD_PRESENT -> 0
                ProfileReevaluationPriorityClass.UNCLASSIFIED -> 1
                ProfileReevaluationPriorityClass.PROPOSED_ONLY -> 2
            }

    private fun matchesCurrentRevision(jobId: Long, revision: Long): Boolean {
        val job = findJobRow(jobId, forUpdate = true) ?: return false
        if (job[ProfileReevaluationJobs.targetRevision] != revision) return false
        val head = findHeadRow(job.toScope(), forUpdate = true) ?: return false
        return head[ProfileReevaluationHeads.latestRevision] == revision
    }

    private fun bootstrapHead(scope: ProfileReevaluationScope) {
        val insertBody: ProfileReevaluationHeads.(UpdateBuilder<*>) -> Unit = {
            it[tenantGroupId] = scope.tenantGroupId
            it[clinicId] = scope.clinicId
            it[patientReferenceFingerprint] = scope.patientReferenceFingerprint
            it[latestRevision] = 0L
            it[latestEventId] = "bootstrap"
            it[assessmentRef] = "bootstrap"
            it[assessmentHash] = "0".repeat(64)
            it[occurredAt] = Instant.EPOCH
        }
        if (TransactionManager.current().db.dialect.name.equals("h2", ignoreCase = true)) {
            val exists = findHeadRow(scope, forUpdate = false) != null
            if (!exists) {
                try {
                    ProfileReevaluationHeads.insert(insertBody)
                } catch (error: ExposedSQLException) {
                    if (findHeadRow(scope, forUpdate = false) == null) throw error
                }
            }
        } else {
            ProfileReevaluationHeads.insertIgnore(insertBody)
        }
    }

    private fun findHeadRow(scope: ProfileReevaluationScope, forUpdate: Boolean): ResultRow? {
        val query = ProfileReevaluationHeads.selectAll().where { headScopePredicate(scope) }
        return if (forUpdate) query.forUpdate().singleOrNull() else query.singleOrNull()
    }

    private fun findJobRow(jobId: Long, forUpdate: Boolean): ResultRow? {
        val query = ProfileReevaluationJobs.selectAll().where { ProfileReevaluationJobs.id eq jobId }
        return if (forUpdate) query.forUpdate().singleOrNull() else query.singleOrNull()
    }

    private fun headScopePredicate(scope: ProfileReevaluationScope) =
        (ProfileReevaluationHeads.tenantGroupId eq scope.tenantGroupId) and
            (ProfileReevaluationHeads.clinicId eq scope.clinicId) and
            (ProfileReevaluationHeads.patientReferenceFingerprint eq scope.patientReferenceFingerprint)

    private fun jobScopePredicate(scope: ProfileReevaluationScope) =
        (ProfileReevaluationJobs.tenantGroupId eq scope.tenantGroupId) and
            (ProfileReevaluationJobs.clinicId eq scope.clinicId) and
            (ProfileReevaluationJobs.patientReferenceFingerprint eq scope.patientReferenceFingerprint)

    private fun activeLeasePredicate(jobId: Long, revision: Long, owner: String, dbNow: Instant) =
        (ProfileReevaluationJobs.id eq jobId) and
            (ProfileReevaluationJobs.targetRevision eq revision) and
            (ProfileReevaluationJobs.status eq ProfileReevaluationJobStatus.RUNNING) and
            (ProfileReevaluationJobs.leaseOwner eq owner) and
            (ProfileReevaluationJobs.leaseExpiresAt greater dbNow)

    private fun hasActiveLease(row: ResultRow, revision: Long, owner: String, dbNow: Instant): Boolean =
        row[ProfileReevaluationJobs.targetRevision] == revision &&
            row[ProfileReevaluationJobs.status] == ProfileReevaluationJobStatus.RUNNING &&
            row[ProfileReevaluationJobs.leaseOwner] == owner &&
            requireNotNull(row[ProfileReevaluationJobs.leaseExpiresAt]) > dbNow

    private fun cursorIsMonotonic(row: ResultRow, cursor: ProfileReevaluationCursor): Boolean =
        (cursor.heldCursorAppointmentId == null ||
            cursor.heldCursorAppointmentId >= (row[ProfileReevaluationJobs.heldCursorAppointmentId] ?: 0L)) &&
            (cursor.proposedCursorAppointmentId == null ||
                cursor.proposedCursorAppointmentId >=
                (row[ProfileReevaluationJobs.proposedCursorAppointmentId] ?: 0L))

    private fun validateTransitionIdentity(jobId: Long, revision: Long, owner: String) {
        require(jobId > 0) { "jobId must be positive" }
        require(revision > 0) { "revision must be positive" }
        require(owner.isNotBlank() && owner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
    }

    private fun dbCurrentTimestamp(): Instant =
        TransactionManager.current().dbCurrentTimestamp()

    private fun ResultRow.toHeadRecord() = ProfileReevaluationHeadRecord(
        id = this[ProfileReevaluationHeads.id].value,
        scope = ProfileReevaluationScope(
            tenantGroupId = this[ProfileReevaluationHeads.tenantGroupId],
            clinicId = this[ProfileReevaluationHeads.clinicId],
            patientReferenceFingerprint = this[ProfileReevaluationHeads.patientReferenceFingerprint],
        ),
        latestRevision = this[ProfileReevaluationHeads.latestRevision],
        latestEventId = this[ProfileReevaluationHeads.latestEventId],
        assessmentRef = this[ProfileReevaluationHeads.assessmentRef],
        assessmentHash = this[ProfileReevaluationHeads.assessmentHash],
        occurredAt = this[ProfileReevaluationHeads.occurredAt],
        createdAt = this[ProfileReevaluationHeads.createdAt],
        updatedAt = this[ProfileReevaluationHeads.updatedAt],
    )

    private fun ResultRow.toJobRecord() = ProfileReevaluationJobRecord(
        id = this[ProfileReevaluationJobs.id].value,
        headId = this[ProfileReevaluationJobs.headId].value,
        scope = toScope(),
        targetRevision = this[ProfileReevaluationJobs.targetRevision],
        eventId = this[ProfileReevaluationJobs.eventId],
        assessmentRef = this[ProfileReevaluationJobs.assessmentRef],
        assessmentHash = this[ProfileReevaluationJobs.assessmentHash],
        status = this[ProfileReevaluationJobs.status],
        occurredAt = this[ProfileReevaluationJobs.occurredAt],
        dueAt = this[ProfileReevaluationJobs.dueAt],
        targetDuration = Duration.ofSeconds(this[ProfileReevaluationJobs.targetDurationSeconds]),
        heldTarget = Duration.ofSeconds(this[ProfileReevaluationJobs.heldTargetSeconds]),
        proposedTarget = Duration.ofSeconds(this[ProfileReevaluationJobs.proposedTargetSeconds]),
        targetPolicyRef = this[ProfileReevaluationJobs.targetPolicyRef],
        targetPolicyGeneration = this[ProfileReevaluationJobs.targetPolicyGeneration],
        nextAttemptAt = this[ProfileReevaluationJobs.nextAttemptAt],
        leaseOwner = this[ProfileReevaluationJobs.leaseOwner],
        leaseExpiresAt = this[ProfileReevaluationJobs.leaseExpiresAt],
        attemptCount = this[ProfileReevaluationJobs.attemptCount],
        firstAttemptAt = this[ProfileReevaluationJobs.firstAttemptAt],
        redriveCount = this[ProfileReevaluationJobs.redriveCount],
        rootJobId = requireNotNull(this[ProfileReevaluationJobs.rootJobId]),
        redriveOfJobId = this[ProfileReevaluationJobs.redriveOfJobId],
        redriveGeneration = this[ProfileReevaluationJobs.redriveGeneration],
        priorityClass = this[ProfileReevaluationJobs.priorityClass],
        heldCursorAppointmentId = this[ProfileReevaluationJobs.heldCursorAppointmentId],
        proposedCursorAppointmentId = this[ProfileReevaluationJobs.proposedCursorAppointmentId],
        scannedCount = this[ProfileReevaluationJobs.scannedCount],
        outcomeCounts = toOutcomeCounts(),
        lastFailureCode = this[ProfileReevaluationJobs.lastFailureCode],
        createdAt = this[ProfileReevaluationJobs.createdAt],
        updatedAt = this[ProfileReevaluationJobs.updatedAt],
    )

    private fun ResultRow.toScope() = ProfileReevaluationScope(
        tenantGroupId = this[ProfileReevaluationJobs.tenantGroupId],
        clinicId = this[ProfileReevaluationJobs.clinicId],
        patientReferenceFingerprint = this[ProfileReevaluationJobs.patientReferenceFingerprint],
    )

    private fun ResultRow.toOutcomeCounts() = ProfileReevaluationOutcomeCounts(
        proposalSuperseded = this[ProfileReevaluationJobs.proposalSupersededCount],
        holdKept = this[ProfileReevaluationJobs.holdKeptCount],
        holdReplaced = this[ProfileReevaluationJobs.holdReplacedCount],
        fallbackToProposed = this[ProfileReevaluationJobs.fallbackToProposedCount],
        skippedIneligible = this[ProfileReevaluationJobs.skippedIneligibleCount],
        skippedUnchanged = this[ProfileReevaluationJobs.skippedUnchangedCount],
    )

    private fun ResultRow.toOutcomeRecord() = ProfileReevaluationOutcomeRecord(
        id = this[ProfileReevaluationOutcomes.id].value,
        jobId = this[ProfileReevaluationOutcomes.jobId].value,
        targetRevision = this[ProfileReevaluationOutcomes.targetRevision],
        appointmentId = this[ProfileReevaluationOutcomes.appointmentId],
        outcomeType = this[ProfileReevaluationOutcomes.outcomeType],
        createdAt = this[ProfileReevaluationOutcomes.createdAt],
    )

    private fun addBounded(left: Long, right: Long): Long =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            error("profile reevaluation progress counter overflow")
        }

    private fun addBounded(
        left: ProfileReevaluationOutcomeCounts,
        right: ProfileReevaluationOutcomeCounts,
    ) = ProfileReevaluationOutcomeCounts(
        proposalSuperseded = addBounded(left.proposalSuperseded, right.proposalSuperseded),
        holdKept = addBounded(left.holdKept, right.holdKept),
        holdReplaced = addBounded(left.holdReplaced, right.holdReplaced),
        fallbackToProposed = addBounded(left.fallbackToProposed, right.fallbackToProposed),
        skippedIneligible = addBounded(left.skippedIneligible, right.skippedIneligible),
        skippedUnchanged = addBounded(left.skippedUnchanged, right.skippedUnchanged),
    )

    private data class ClinicKey(
        val tenantGroupId: Long,
        val clinicId: Long,
    )

    private data class FairClaimCandidate(
        val row: ResultRow,
        val clinicKey: ClinicKey,
        val priority: ProfileReevaluationPriorityClass,
        val effectiveDueAt: Instant,
        val jobId: Long,
    )

    private companion object {
        val READY_STATES = listOf(
            ProfileReevaluationJobStatus.PENDING,
            ProfileReevaluationJobStatus.RETRY_WAIT,
        )
        val NON_TERMINAL_STATES = READY_STATES + ProfileReevaluationJobStatus.RUNNING
        const val MAX_CLAIM_CANDIDATES = 10_000
        const val MAX_FAILED_JOB_PAGE_SIZE = 1_000
        val FAILURE_CODE_REGEX = Regex("^[A-Z][A-Z0-9_]{0,95}$")
    }
}

data class ProfileReevaluationRepositorySummary(
    val pendingJobs: Long,
    val runningJobs: Long,
    val retryWaitJobs: Long,
    val failedJobs: Long,
    val activeLeases: Long,
    val oldestBacklogAge: Duration,
)

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) error("SELECT CURRENT_TIMESTAMP returned no rows")
        resultSet.getObject(1).toInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")

private fun Any?.toInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }
