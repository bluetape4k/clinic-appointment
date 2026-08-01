package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecision
import io.bluetape4k.clinic.appointment.model.commitment.ConsentSubjectType
import io.bluetape4k.clinic.appointment.model.commitment.ProductVersionMigrationConsentSubject
import io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.ProposalConsentDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionStamp
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * caller-owned Exposed transaction에서 commitment, proposal, consent primitive를 제공합니다.
 *
 * 이 repository는 transaction을 열거나 commit하지 않습니다. application service가
 * 고객 동의, 자원 점유, outbox와 같은 transaction 안에서 이 primitive를 조합해야
 * `confirmedProposalId`와 allocation 교체가 원자적으로 보입니다.
 */
class AppointmentCommitmentRepository {
    /** 방문 예약 하나에 commitment를 생성합니다. */
    fun create(commitment: AppointmentCommitment): AppointmentCommitmentRecord {
        val id =
            AppointmentCommitments
                .insertAndGetId {
                    it[appointmentId] = commitment.appointmentId
                    it[status] = commitment.status
                    it[origin] = commitment.origin
                it[confirmedProposalId] = commitment.confirmedProposalId
                it[effectivePolicySnapshotId] = commitment.effectivePolicySnapshotId
                it[bookingReliabilityDecisionId] = commitment.bookingReliabilityStamp?.decisionId
                it[bookingReliabilityPolicyVersionId] = commitment.bookingReliabilityStamp?.policyVersionId
                it[bookingReliabilityPolicyHash] = commitment.bookingReliabilityStamp?.policyHash
                it[bookingReliabilityEvaluationDigest] = commitment.bookingReliabilityStamp?.evaluationDigest
                it[bookingReliabilityExpiresAt] = commitment.bookingReliabilityStamp?.expiresAt
                it[version] = commitment.version
                }.value
        return AppointmentCommitmentRecord(
            id = id,
            appointmentId = commitment.appointmentId,
            status = commitment.status,
            origin = commitment.origin,
            confirmedProposalId = commitment.confirmedProposalId,
            effectivePolicySnapshotId = commitment.effectivePolicySnapshotId,
            version = commitment.version,
            bookingReliabilityStamp = commitment.bookingReliabilityStamp,
        )
    }

    /** 정확한 방문 예약의 commitment를 반환합니다. */
    fun findByAppointmentId(appointmentId: Long): AppointmentCommitmentRecord? {
        val validAppointmentId = appointmentId.requirePositiveNumber("appointmentId")
        return AppointmentCommitments
            .selectAll()
            .where { AppointmentCommitments.appointmentId eq validAppointmentId }
            .singleOrNull()
            ?.let(::mapCommitment)
    }

    /** 양수 commitment 식별자로 현재 row를 반환합니다. */
    fun findById(commitmentId: Long): AppointmentCommitmentRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        return AppointmentCommitments
            .selectAll()
            .where { AppointmentCommitments.id eq validCommitmentId }
            .singleOrNull()
            ?.let(::mapCommitment)
    }

    /**
     * 재평가 최종 적용 전에 commitment row를 잠그고 최신 상태와 version을 반환합니다.
     */
    fun findByIdForUpdate(commitmentId: Long): AppointmentCommitmentRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        return AppointmentCommitments
            .selectAll()
            .where { AppointmentCommitments.id eq validCommitmentId }
            .forUpdate()
            .singleOrNull()
            ?.let(::mapCommitment)
    }

    /**
     * [commitmentId]에 실제로 속한 정확한 proposal을 반환합니다.
     *
     * 다른 commitment의 proposal ID를 command가 재사용해도 이 경계를 통과할 수 없습니다.
     */
    fun findProposal(
        commitmentId: Long,
        proposalId: Long,
    ): AppointmentProposalRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return AppointmentProposals
            .selectAll()
            .where {
                (AppointmentProposals.id eq validProposalId) and
                    (AppointmentProposals.commitmentId eq validCommitmentId)
            }.singleOrNull()
            ?.let(::mapProposal)
    }

    /**
     * 종결 command가 같은 proposal을 동시에 수락·거부·만료하지 못하도록 row를 잠급니다.
     *
     * 이 함수는 caller가 연 Exposed transaction 안에서만 사용해야 합니다. 잠금 뒤
     * commitment를 다시 읽고 version을 검증해야 잠금 대기 중 선행 command가 만든
     * 확정 포인터와 version 변경을 현재 command가 관찰할 수 있습니다.
     */
    fun findProposalForUpdate(
        commitmentId: Long,
        proposalId: Long,
    ): AppointmentProposalRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return AppointmentProposals
            .selectAll()
            .where {
                (AppointmentProposals.id eq validProposalId) and
                    (AppointmentProposals.commitmentId eq validCommitmentId)
            }.forUpdate()
            .singleOrNull()
            ?.let(::mapProposal)
    }

    /**
     * 멱등 command 결과가 가리키는 양수 proposal 식별자로 정확한 row를 반환합니다.
     *
     * 이 조회는 멱등 결과 재생에만 사용합니다. 일반 mutation command는 다른 commitment의
     * proposal을 사용할 수 없도록 [findProposal]로 소유권을 함께 검증해야 합니다.
     */
    fun findProposalById(proposalId: Long): AppointmentProposalRecord? {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return AppointmentProposals
            .selectAll()
            .where { AppointmentProposals.id eq validProposalId }
            .singleOrNull()
            ?.let(::mapProposal)
    }

    /** commitment에 append된 가장 큰 proposal revision을 반환합니다. */
    fun findLatestProposalRevision(commitmentId: Long): Long? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        return AppointmentProposals
            .select(AppointmentProposals.revision)
            .where { AppointmentProposals.commitmentId eq validCommitmentId }
            .orderBy(AppointmentProposals.revision, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(AppointmentProposals.revision)
    }

    /** commitment에 append된 가장 최근 proposal을 반환합니다. */
    fun findLatestProposal(commitmentId: Long): AppointmentProposalRecord? =
        findLatestProposal(commitmentId, forUpdate = false)

    /**
     * commitment에 append된 가장 최근 proposal을 잠급니다.
     *
     * 재평가 서비스는 commitment를 먼저 잠근 뒤 이 함수를 호출해 잠금 순서를 일정하게
     * 유지해야 합니다.
     */
    fun findLatestProposalForUpdate(commitmentId: Long): AppointmentProposalRecord? =
        findLatestProposal(commitmentId, forUpdate = true)

    /**
     * proposal ID/revision/hash에 정확히 결합된 가장 최근 고객 결정을 반환합니다.
     *
     * append-only 이력에서 뒤의 결정이 앞의 결정을 대체합니다. `subjectType`만 같거나
     * hash가 다른 동의는 현재 proposal의 증빙으로 인정하지 않습니다.
     */
    fun findLatestProposalDecision(
        commitmentId: Long,
        proposalId: Long,
        proposalRevision: Long,
        proposalHash: String,
    ): ProposalConsentDecisionRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        val validRevision = proposalRevision.requirePositiveNumber("proposalRevision")
        val validHash = proposalHash.requireNotBlank("proposalHash")
        val subjectPayload = "$validProposalId|$validRevision|$validHash"
        return ConsentDecisions
            .selectAll()
            .where {
                (ConsentDecisions.commitmentId eq validCommitmentId) and
                    (ConsentDecisions.subjectType eq ConsentSubjectType.APPOINTMENT_PROPOSAL) and
                    (ConsentDecisions.subjectPayload eq subjectPayload)
            }.orderBy(ConsentDecisions.id, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                ProposalConsentDecisionRecord(
                    proposalId = validProposalId,
                    proposalRevision = validRevision,
                    proposalHash = validHash,
                    decision = row[ConsentDecisions.decision],
                    evidenceType = row[ConsentDecisions.evidenceType],
                    termsHash = row[ConsentDecisions.termsHash],
                )
            }
    }

    /**
     * 수정 불가능한 새 proposal revision을 append합니다.
     *
     * [proposalHash]는 draft와 별도로 caller가 canonical 계산한 값이며 빈 값이나 64자가
     * 아닌 값은 storage에 쓰지 않습니다.
     */
    fun appendProposal(
        commitmentId: Long,
        draft: AppointmentProposalDraft,
        proposalHash: String,
        expiresAt: Instant,
        representativeTreatmentName: String,
        createdByActor: String,
    ): AppointmentProposalRecord {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validProposalHash = proposalHash.requireNotBlank("proposalHash")
        val validTreatmentName = representativeTreatmentName.requireNotBlank("representativeTreatmentName")
        val validCreatedByActor = createdByActor.requireNotBlank("createdByActor")
        require(validProposalHash.length == 64) { "proposalHash must be a 64-character SHA-256 hex value" }
        require(expiresAt <= draft.endsAt) { "expiresAt must not be after proposed end" }
        val owner =
            AppointmentCommitments
                .selectAll()
                .where { AppointmentCommitments.id eq validCommitmentId }
                .singleOrNull()
        requireNotNull(owner) { "commitment does not exist" }
        require(owner[AppointmentCommitments.appointmentId].value == draft.appointmentId) {
            "proposal appointmentId must match commitment appointmentId"
        }

        val proposalId =
            AppointmentProposals
                .insertAndGetId {
                    it[AppointmentProposals.commitmentId] = validCommitmentId
                    it[revision] = draft.revision
                    it[proposedStartAt] = draft.startsAt
                    it[proposedEndAt] = draft.endsAt
                    it[AppointmentProposals.expiresAt] = expiresAt
                    it[expiredAt] = null
                    it[AppointmentProposals.representativeTreatmentName] = validTreatmentName
                    it[AppointmentProposals.proposalHash] = validProposalHash
                    it[policySnapshotId] = draft.policySnapshotId
                    it[bookingReliabilityDecisionId] = draft.bookingReliabilityStamp?.decisionId
                    it[bookingReliabilityPolicyVersionId] = draft.bookingReliabilityStamp?.policyVersionId
                    it[bookingReliabilityPolicyHash] = draft.bookingReliabilityStamp?.policyHash
                    it[bookingReliabilityEvaluationDigest] = draft.bookingReliabilityStamp?.evaluationDigest
                    it[bookingReliabilityExpiresAt] = draft.bookingReliabilityStamp?.expiresAt
                    it[supersedesProposalId] = draft.supersedesProposalId
                    it[AppointmentProposals.createdByActor] = validCreatedByActor
                }.value
        return AppointmentProposalRecord(
            id = proposalId,
            commitmentId = validCommitmentId,
            revision = draft.revision,
            proposedStartAt = draft.startsAt,
            proposedEndAt = draft.endsAt,
            expiresAt = expiresAt,
            expiredAt = null,
            representativeTreatmentName = validTreatmentName,
            proposalHash = validProposalHash,
            policySnapshotId = draft.policySnapshotId,
            supersedesProposalId = draft.supersedesProposalId,
            createdByActor = validCreatedByActor,
            bookingReliabilityStamp = draft.bookingReliabilityStamp,
        )
    }

    /**
     * 아직 만료되지 않은 proposal에 권위 있는 만료 시각을 한 번만 기록합니다.
     *
     * 같은 proposal을 다른 idempotency key로 다시 만료하려는 command는 `false`를 받아
     * 감사·outbox unique 제약에 도달하기 전에 안정적인 업무 결과로 변환할 수 있습니다.
     */
    fun markProposalExpired(
        proposalId: Long,
        expiredAt: Instant,
    ): Boolean {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        return AppointmentProposals.update(
            where = {
                (AppointmentProposals.id eq validProposalId) and
                    AppointmentProposals.expiredAt.isNull()
            },
        ) {
            it[AppointmentProposals.expiredAt] = expiredAt
        } == 1
    }

    /** 동의 증빙을 수정 없이 append하고 생성된 양수 식별자를 반환합니다. */
    fun appendConsent(
        commitmentId: Long,
        decision: ConsentDecision,
    ): Long {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val subjectPayload =
            when (val subject = decision.subject) {
                is ProposalConsentSubject -> {
                    "${subject.proposalId}|${subject.proposalRevision}|${subject.proposalHash}"
                }

                is ProductVersionMigrationConsentSubject -> {
                    "${subject.migrationId}|${subject.fromProductVersionId}|" +
                        "${subject.toProductVersionId}|${subject.mappingHash}"
                }

                else -> {
                    throw IllegalArgumentException("unsupported consent subject")
                }
            }
        return ConsentDecisions
            .insertAndGetId {
                it[ConsentDecisions.commitmentId] = validCommitmentId
                it[subjectType] = decision.subject.type
                it[ConsentDecisions.subjectPayload] = subjectPayload
                it[ConsentDecisions.decision] = decision.decision
                it[evidenceAuthority] = decision.evidenceAuthority
                it[evidenceId] = decision.evidenceId
                it[evidenceHash] = decision.evidenceHash
                it[evidenceType] = decision.evidenceType
                it[termsHash] = decision.termsHash
                it[decidedAt] = decision.decidedAt
                it[actorRef] = decision.actorRef
            }.value
    }

    /**
     * 현재 [expectedVersion]일 때만 commitment를 확정 proposal로 이동합니다.
     *
     * proposal 소유권을 먼저 검증하며 CAS 실패는 예외 대신 `false`를 반환합니다.
     */
    fun confirmByVersion(
        commitmentId: Long,
        expectedVersion: Long,
        proposalId: Long,
        updatedAt: Instant = Instant.now(),
        bookingReliabilityStamp: BookingReliabilityDecisionStamp? = null,
        expectedBookingReliabilityStamp: BookingReliabilityDecisionStamp? = null,
    ): Boolean {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validExpectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        require(
            AppointmentProposals
                .selectAll()
                .where {
                    (AppointmentProposals.id eq validProposalId) and
                        (AppointmentProposals.commitmentId eq validCommitmentId)
                }.count() == 1L,
        ) {
            "proposal must belong to commitment"
        }
        val policySnapshotId =
            AppointmentProposals
                .select(AppointmentProposals.policySnapshotId)
                .where {
                    (AppointmentProposals.id eq validProposalId) and
                        (AppointmentProposals.commitmentId eq validCommitmentId)
                }
                .single()[AppointmentProposals.policySnapshotId]
        return AppointmentCommitments.update(
            where = {
                var predicate: Op<Boolean> =
                    (AppointmentCommitments.id eq validCommitmentId) and
                        (AppointmentCommitments.version eq validExpectedVersion) and
                        (AppointmentCommitments.status neq AppointmentCommitmentStatus.EXPIRED) and
                        (AppointmentCommitments.status neq AppointmentCommitmentStatus.CANCELLED)
                expectedBookingReliabilityStamp?.let { stamp ->
                    predicate = predicate and (AppointmentCommitments.bookingReliabilityDecisionId eq stamp.decisionId)
                    predicate = predicate and
                        (AppointmentCommitments.bookingReliabilityPolicyVersionId eq stamp.policyVersionId)
                    predicate = predicate and (AppointmentCommitments.bookingReliabilityPolicyHash eq stamp.policyHash)
                    predicate = predicate and
                        (AppointmentCommitments.bookingReliabilityEvaluationDigest eq stamp.evaluationDigest)
                    predicate = predicate and if (stamp.expiresAt == null) {
                        AppointmentCommitments.bookingReliabilityExpiresAt.isNull()
                    } else {
                        AppointmentCommitments.bookingReliabilityExpiresAt eq stamp.expiresAt
                    }
                }
                predicate
            },
        ) {
            it[status] = AppointmentCommitmentStatus.CONFIRMED
            it[confirmedProposalId] = validProposalId
            it[effectivePolicySnapshotId] = policySnapshotId
            bookingReliabilityStamp?.let { stamp ->
                it[AppointmentCommitments.bookingReliabilityDecisionId] = stamp.decisionId
                it[AppointmentCommitments.bookingReliabilityPolicyVersionId] = stamp.policyVersionId
                it[AppointmentCommitments.bookingReliabilityPolicyHash] = stamp.policyHash
                it[AppointmentCommitments.bookingReliabilityEvaluationDigest] = stamp.evaluationDigest
                it[AppointmentCommitments.bookingReliabilityExpiresAt] = stamp.expiresAt
            }
            it[version] = validExpectedVersion + 1
            it[AppointmentCommitments.updatedAt] = updatedAt
        } == 1
    }

    /**
     * 아직 확정되지 않은 commitment를 version CAS로 만료 상태로 전환합니다.
     *
     * 이미 확정된 commitment의 변경 proposal은 이 함수를 사용하지 않습니다.
     * application service가 [advanceConfirmedVersion]으로 확정 포인터를 보존한 채
     * version을 소비해 수락·거부·만료 중 하나만 성공하도록 직렬화해야 합니다.
     */
    fun expireUnconfirmedByVersion(
        commitmentId: Long,
        expectedVersion: Long,
        updatedAt: Instant,
    ): Boolean {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validExpectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
        return AppointmentCommitments.update(
            where = {
                (AppointmentCommitments.id eq validCommitmentId) and
                    (AppointmentCommitments.version eq validExpectedVersion) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.CONFIRMED) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.EXPIRED) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.CANCELLED)
            },
        ) {
            it[status] = AppointmentCommitmentStatus.EXPIRED
            it[version] = validExpectedVersion + 1
            it[AppointmentCommitments.updatedAt] = updatedAt
        } == 1
    }

    /**
     * 확정 포인터와 상태를 보존하면서 변경 proposal 종결 사실을 version에 반영합니다.
     *
     * 고객 수락의 [confirmByVersion]과 같은 CAS 경계를 사용하므로 거부·만료·수락 중
     * 정확히 하나만 [expectedVersion]을 소비합니다. 실패 시 caller는 현재 transaction의
     * consent 또는 proposal 만료 표식을 rollback하고 안정적인 version 충돌을 반환해야 합니다.
     */
    fun advanceConfirmedVersion(
        commitmentId: Long,
        expectedVersion: Long,
        confirmedProposalId: Long,
        updatedAt: Instant,
    ): Boolean {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validExpectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
        val validConfirmedProposalId = confirmedProposalId.requirePositiveNumber("confirmedProposalId")
        return AppointmentCommitments.update(
            where = {
                (AppointmentCommitments.id eq validCommitmentId) and
                    (AppointmentCommitments.version eq validExpectedVersion) and
                    (AppointmentCommitments.status eq AppointmentCommitmentStatus.CONFIRMED) and
                    (AppointmentCommitments.confirmedProposalId eq validConfirmedProposalId)
            },
        ) {
            it[version] = validExpectedVersion + 1
            it[AppointmentCommitments.updatedAt] = updatedAt
        } == 1
    }

    /**
     * 현재 commitment를 version CAS로 취소 상태로 전환합니다.
     *
     * proposal과 allocation의 종결은 caller transaction에서 함께 수행합니다. 이미
     * 만료되거나 취소된 commitment에는 성공하지 않으며 확정 포인터는 취소 이력 조회를
     * 위해 보존합니다.
     */
    fun cancelByVersion(
        commitmentId: Long,
        expectedVersion: Long,
        updatedAt: Instant,
    ): Boolean {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validExpectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
        return AppointmentCommitments.update(
            where = {
                (AppointmentCommitments.id eq validCommitmentId) and
                    (AppointmentCommitments.version eq validExpectedVersion) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.EXPIRED) and
                    (AppointmentCommitments.status neq AppointmentCommitmentStatus.CANCELLED)
            },
        ) {
            it[status] = AppointmentCommitmentStatus.CANCELLED
            it[version] = validExpectedVersion + 1
            it[AppointmentCommitments.updatedAt] = updatedAt
        } == 1
    }

    /**
     * 프로필 재평가 대상 상태와 version이 모두 일치할 때만 다음 미확정 상태로 전이합니다.
     *
     * `CONFIRMED`, `EXPIRED`, `CANCELLED`는 입력 단계에서 거부하므로 이 primitive로
     * 확정 약정을 변경할 수 없습니다. 새 정책 snapshot은 새 proposal을 채택할 때만
     * caller가 전달하고, 기존 선점 유지에는 이 메서드를 호출하지 않습니다.
     */
    fun advanceProfileReevaluationByVersion(
        commitmentId: Long,
        expectedStatus: AppointmentCommitmentStatus,
        expectedVersion: Long,
        nextStatus: AppointmentCommitmentStatus,
        effectivePolicySnapshotId: Long,
        updatedAt: Instant,
    ): Boolean {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val validExpectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
        val validPolicySnapshotId =
            effectivePolicySnapshotId.requirePositiveNumber("effectivePolicySnapshotId")
        require(expectedStatus in PROFILE_REEVALUATION_STATUSES) {
            "expectedStatus must be PROPOSED or HELD"
        }
        require(nextStatus in PROFILE_REEVALUATION_STATUSES) {
            "nextStatus must be PROPOSED or HELD"
        }
        return AppointmentCommitments.update(
            where = {
                (AppointmentCommitments.id eq validCommitmentId) and
                    (AppointmentCommitments.status eq expectedStatus) and
                    (AppointmentCommitments.version eq validExpectedVersion) and
                    AppointmentCommitments.confirmedProposalId.isNull()
            },
        ) {
            it[status] = nextStatus
            it[AppointmentCommitments.effectivePolicySnapshotId] = validPolicySnapshotId
            it[version] = validExpectedVersion + 1L
            it[AppointmentCommitments.updatedAt] = updatedAt
        } == 1
    }

    private fun findLatestProposal(
        commitmentId: Long,
        forUpdate: Boolean,
    ): AppointmentProposalRecord? {
        val validCommitmentId = commitmentId.requirePositiveNumber("commitmentId")
        val query =
            AppointmentProposals
                .selectAll()
                .where { AppointmentProposals.commitmentId eq validCommitmentId }
                .orderBy(AppointmentProposals.revision, SortOrder.DESC)
                .limit(1)
        return (if (forUpdate) query.forUpdate() else query)
            .singleOrNull()
            ?.let(::mapProposal)
    }

    private fun mapCommitment(row: ResultRow) =
        AppointmentCommitmentRecord(
            id = row[AppointmentCommitments.id].value,
            appointmentId = row[AppointmentCommitments.appointmentId].value,
            status = row[AppointmentCommitments.status],
            origin = row[AppointmentCommitments.origin],
            confirmedProposalId = row[AppointmentCommitments.confirmedProposalId],
            effectivePolicySnapshotId = row[AppointmentCommitments.effectivePolicySnapshotId],
            version = row[AppointmentCommitments.version],
            bookingReliabilityStamp = row.toBookingReliabilityStamp(
                decisionId = AppointmentCommitments.bookingReliabilityDecisionId,
                policyVersionId = AppointmentCommitments.bookingReliabilityPolicyVersionId,
                policyHash = AppointmentCommitments.bookingReliabilityPolicyHash,
                evaluationDigest = AppointmentCommitments.bookingReliabilityEvaluationDigest,
                expiresAt = AppointmentCommitments.bookingReliabilityExpiresAt,
            ),
        )

    private fun mapProposal(row: ResultRow) =
        AppointmentProposalRecord(
            id = row[AppointmentProposals.id].value,
            commitmentId = row[AppointmentProposals.commitmentId].value,
            revision = row[AppointmentProposals.revision],
            proposedStartAt = row[AppointmentProposals.proposedStartAt],
            proposedEndAt = row[AppointmentProposals.proposedEndAt],
            expiresAt = row[AppointmentProposals.expiresAt],
            expiredAt = row[AppointmentProposals.expiredAt],
            representativeTreatmentName = row[AppointmentProposals.representativeTreatmentName],
            proposalHash = row[AppointmentProposals.proposalHash],
            policySnapshotId = row[AppointmentProposals.policySnapshotId],
            supersedesProposalId = row[AppointmentProposals.supersedesProposalId],
            createdByActor = row[AppointmentProposals.createdByActor],
            bookingReliabilityStamp = row.toBookingReliabilityStamp(
                decisionId = AppointmentProposals.bookingReliabilityDecisionId,
                policyVersionId = AppointmentProposals.bookingReliabilityPolicyVersionId,
                policyHash = AppointmentProposals.bookingReliabilityPolicyHash,
                evaluationDigest = AppointmentProposals.bookingReliabilityEvaluationDigest,
                expiresAt = AppointmentProposals.bookingReliabilityExpiresAt,
            ),
        )

    private companion object {
        val PROFILE_REEVALUATION_STATUSES =
            setOf(AppointmentCommitmentStatus.PROPOSED, AppointmentCommitmentStatus.HELD)
    }
}

private fun ResultRow.toBookingReliabilityStamp(
    decisionId: Column<Long?>,
    policyVersionId: Column<Long?>,
    policyHash: Column<String?>,
    evaluationDigest: Column<String?>,
    expiresAt: Column<Instant?>,
): BookingReliabilityDecisionStamp? {
    val id = this[decisionId] ?: return null
    val version = this[policyVersionId] ?: return null
    val hash = this[policyHash] ?: return null
    val digest = this[evaluationDigest] ?: return null
    return BookingReliabilityDecisionStamp(
        decisionId = id,
        policyVersionId = version,
        policyHash = hash,
        evaluationDigest = digest,
        expiresAt = this[expiresAt],
    )
}
