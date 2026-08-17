package io.bluetape4k.clinic.appointment.repository.waitlist

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCommandRecords
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.IdempotencyRequestMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyLeaseFenced
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistContention
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.sql.SQLException
import java.time.Duration
import java.time.Instant

/**
 * waitlist delivery worker와 command handler의 durable DB 권위를 제공합니다.
 *
 * 모든 public 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행되어야 합니다.
 * Redis leader, scheduler, HTTP adapter는 이 저장소가 반환하는 lease/version fence와
 * command reservation 결과를 DB write 권위로 사용해야 합니다.
 */
class WaitlistDeliveryRepository(
    private val claimStrategy: VacancyClaimStrategy? = null,
    private val retryPolicy: ContentionRetryPolicy = ContentionRetryPolicy(),
) {
    fun insertVacancy(vacancy: NewVacancyJob): VacancyJobRecord {
        vacancy.validate()
        val jobId = WaitlistVacancyJobs.insertAndGetId {
            it[tenantGroupId] = vacancy.tenantGroupId
            it[clinicId] = vacancy.clinicId
            it[vacancyKey] = vacancy.vacancyKey
            it[vacancyGeneration] = vacancy.vacancyGeneration
            it[activeVacancyKey] = vacancy.activeVacancyKey
            it[sourceAppointmentId] = vacancy.sourceAppointmentId
            it[sourceTransitionId] = vacancy.sourceTransitionId
            it[resourceType] = vacancy.resourceType
            it[resourceId] = vacancy.resourceId
            it[capacityUnits] = vacancy.capacityUnits
            it[maximumCapacity] = vacancy.maximumCapacity
            it[treatmentTypeId] = vacancy.treatmentTypeId
            it[doctorId] = vacancy.doctorId
            it[policyVersion] = vacancy.policyVersion
            it[status] = VacancyJobState.READY
            it[nextAttemptAt] = vacancy.nextAttemptAt
            it[vacancyStartsAt] = vacancy.vacancyStartsAt
            it[vacancyEndsAt] = vacancy.vacancyEndsAt
            it[version] = 0L
            it[createdAt] = vacancy.now
            it[updatedAt] = vacancy.now
        }.value
        return requireNotNull(findVacancy(jobId))
    }

    fun findVacancy(jobId: Long): VacancyJobRecord? {
        val validJobId = jobId.requirePositiveNumber("jobId")
        return WaitlistVacancyJobs
            .selectAll()
            .where { WaitlistVacancyJobs.id eq validJobId }
            .singleOrNull()
            ?.toVacancyJobRecord()
    }

    /** processing transaction 시작 시 lease/version fence를 확인합니다. */
    fun requireValidFence(
        claim: VacancyClaim,
        now: Instant,
    ): VacancyJobRecord {
        val current = findVacancy(claim.jobId) ?: throw VacancyLeaseFenced()
        if (!current.matchesFence(claim, now)) {
            throw VacancyLeaseFenced()
        }
        return current
    }

    /** vacancy job을 비관 잠금으로 재조회하고 같은 lease fence를 유지하는지 확인합니다. */
    fun lockVacancy(
        claim: VacancyClaim,
        now: Instant,
    ): VacancyJobRecord {
        val locked = WaitlistVacancyJobs
            .selectAll()
            .where { WaitlistVacancyJobs.id eq claim.jobId.requirePositiveNumber("jobId") }
            .forUpdate()
            .singleOrNull()
            ?.toVacancyJobRecord()
            ?: throw VacancyLeaseFenced()
        if (!locked.matchesFence(claim, now)) {
            throw VacancyLeaseFenced()
        }
        return locked
    }

    /** test와 recovery가 vacancy key로 현재 generation을 찾을 수 있는 scope-bound 조회입니다. */
    fun findVacancy(
        tenantGroupId: Long,
        clinicId: Long,
        vacancyKey: String,
        vacancyGeneration: Long? = null,
    ): VacancyJobRecord? {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        vacancyKey.requireNotBlank("vacancyKey")
        vacancyGeneration?.let { require(it > 0L) { "vacancyGeneration must be positive" } }
        return WaitlistVacancyJobs
            .selectAll()
            .where {
                (WaitlistVacancyJobs.tenantGroupId eq tenantGroupId) and
                    (WaitlistVacancyJobs.clinicId eq clinicId) and
                    (WaitlistVacancyJobs.vacancyKey eq vacancyKey) and
                    (vacancyGeneration?.let { WaitlistVacancyJobs.vacancyGeneration eq it } ?: Op.TRUE)
            }
            .orderBy(WaitlistVacancyJobs.vacancyGeneration to SortOrder.DESC, WaitlistVacancyJobs.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toVacancyJobRecord()
    }

    /** 같은 vacancy key에서 이미 terminal 처리된 generation만 찾아 반복 expiry를 멱등화합니다. */
    fun findTerminalVacancy(
        tenantGroupId: Long,
        clinicId: Long,
        vacancyKey: String,
    ): VacancyJobRecord? {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        vacancyKey.requireNotBlank("vacancyKey")
        return WaitlistVacancyJobs
            .selectAll()
            .where {
                (WaitlistVacancyJobs.tenantGroupId eq tenantGroupId) and
                    (WaitlistVacancyJobs.clinicId eq clinicId) and
                    (WaitlistVacancyJobs.vacancyKey eq vacancyKey) and
                    (WaitlistVacancyJobs.status inList TERMINAL_STATES.toList())
            }
            .orderBy(WaitlistVacancyJobs.vacancyGeneration to SortOrder.DESC, WaitlistVacancyJobs.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toVacancyJobRecord()
    }

    /**
     * 현재 Exposed transaction에서 vacancy lease를 한 번만 시도합니다.
     *
     * contention 재시도는 이 메서드가 소유한 transaction 안에서 수행하지 않습니다.
     * 호출자는 [withContentionRetry]를 transaction 경계 밖에서 호출하고, callback마다
     * claim부터 후속 delivery/terminal write까지를 fresh top-level transaction으로 감싸야
     * aborted transaction 재사용을 피할 수 있습니다.
     */
    fun claim(
        jobId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): VacancyClaim? {
        val validJobId = jobId.requirePositiveNumber("jobId")
        val validOwner = owner.requireNotBlank("owner")
        require(validOwner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(leaseUntil > now) { "leaseUntil must be later than now" }

        val strategy = resolvedClaimStrategy()
        val lockTimeoutPlan = strategy.lockTimeoutPlan
        var appliedLockTimeoutStatements = 0
        try {
            lockTimeoutPlan?.beforeClaimSql?.forEach { sql ->
                TransactionManager.current().exec(sql)
                appliedLockTimeoutStatements += 1
            }
            return claimWithStrategy(
                strategy = strategy,
                jobId = validJobId,
                owner = validOwner,
                now = now,
                leaseUntil = leaseUntil,
            )
        } finally {
            if (appliedLockTimeoutStatements > 0) {
                lockTimeoutPlan?.afterClaimSql?.forEach { sql ->
                    TransactionManager.current().exec(sql)
                }
            }
        }
    }

    fun completeOffer(
        claim: VacancyClaim,
        now: Instant,
        offerId: Long,
    ): Boolean =
        terminalUpdate(
            claim = claim,
            now = now,
            status = VacancyJobState.OFFERED,
            resultOfferId = offerId.requirePositiveNumber("offerId"),
            errorCode = null,
        )

    fun markNoCandidate(
        claim: VacancyClaim,
        now: Instant,
    ): Boolean =
        terminalUpdate(
            claim = claim,
            now = now,
            status = VacancyJobState.NO_CANDIDATE,
            resultOfferId = null,
            errorCode = null,
        )

    /** 이름을 delivery orchestration contract에 맞춘 no-candidate terminal alias입니다. */
    fun completeNoCandidate(
        claim: VacancyClaim,
        now: Instant,
    ): Boolean = markNoCandidate(claim, now)

    /** 유효 slot이 사라진 processing job을 terminal EXPIRED로 닫습니다. */
    fun markExpired(
        claim: VacancyClaim,
        now: Instant,
    ): Boolean =
        terminalUpdate(
            claim = claim,
            now = now,
            status = VacancyJobState.EXPIRED,
            resultOfferId = null,
            errorCode = null,
        )

    /** expiry/withdraw progression이 다음 generation을 만들기 전에 열린 job을 닫습니다. */
    fun terminalizeForProgress(
        jobId: Long,
        now: Instant,
        status: VacancyJobState = VacancyJobState.NO_CANDIDATE,
    ): VacancyJobRecord? {
        require(status in TERMINAL_STATES) { "progress status must be terminal" }
        val validJobId = jobId.requirePositiveNumber("jobId")
        val current = WaitlistVacancyJobs
            .selectAll()
            .where { WaitlistVacancyJobs.id eq validJobId }
            .forUpdate()
            .singleOrNull()
            ?.toVacancyJobRecord()
            ?: return null
        if (current.status == VacancyJobState.READY || current.status == VacancyJobState.PROCESSING) {
            WaitlistVacancyJobs.update({
                (WaitlistVacancyJobs.id eq validJobId) and
                    (WaitlistVacancyJobs.status inList listOf(
                        VacancyJobState.READY,
                        VacancyJobState.PROCESSING,
                    ))
            }) {
                it[WaitlistVacancyJobs.status] = status
                it[WaitlistVacancyJobs.offeredWaitlistEntryId] = null
                it[WaitlistVacancyJobs.activeVacancyKey] = null
                it[WaitlistVacancyJobs.leaseOwner] = null
                it[WaitlistVacancyJobs.leaseExpiresAt] = null
                it[WaitlistVacancyJobs.lastErrorCode] = null
                it[WaitlistVacancyJobs.version] = current.version + 1L
                it[WaitlistVacancyJobs.updatedAt] = now
            }
        }
        return findVacancy(validJobId)
    }

    fun markFailed(
        claim: VacancyClaim,
        now: Instant,
        errorCode: String,
    ): Boolean {
        val validErrorCode = errorCode.requireNotBlank("errorCode")
        require(STABLE_ERROR_CODE_REGEX.matches(validErrorCode)) {
            "errorCode must contain 1..96 uppercase safe characters"
        }
        return terminalUpdate(
            claim = claim,
            now = now,
            status = VacancyJobState.FAILED,
            resultOfferId = null,
            errorCode = validErrorCode,
        )
    }

    fun nextGeneration(
        previousJobId: Long,
        now: Instant,
    ): VacancyJobRecord {
        val previous = findVacancy(previousJobId.requirePositiveNumber("previousJobId"))
            ?: throw VacancyGenerationConflict()
        if (previous.status !in TERMINAL_STATES) {
            throw VacancyGenerationConflict()
        }
        findVacancy(
            tenantGroupId = previous.tenantGroupId,
            clinicId = previous.clinicId,
            vacancyKey = previous.vacancyKey,
            vacancyGeneration = previous.vacancyGeneration + 1L,
        )?.let { return it }
        return insertVacancy(
            NewVacancyJob(
                tenantGroupId = previous.tenantGroupId,
                clinicId = previous.clinicId,
                vacancyKey = previous.vacancyKey,
                vacancyGeneration = previous.vacancyGeneration + 1L,
                activeVacancyKey = previous.vacancyKey,
                sourceAppointmentId = previous.sourceAppointmentId,
                sourceTransitionId = "${previous.sourceTransitionId}#generation-${previous.vacancyGeneration + 1L}",
                resourceType = previous.resourceType,
                resourceId = previous.resourceId,
                capacityUnits = previous.capacityUnits,
                maximumCapacity = previous.maximumCapacity,
                treatmentTypeId = previous.treatmentTypeId,
                doctorId = previous.doctorId,
                policyVersion = previous.policyVersion,
                nextAttemptAt = now,
                vacancyStartsAt = previous.vacancyStartsAt,
                vacancyEndsAt = previous.vacancyEndsAt,
                now = now,
            ),
        )
    }

    fun reserve(
        key: WaitlistCommandKey,
        requestDigest: String,
        now: Instant,
    ): CommandReservation {
        val validRequestDigest = validateDigest(requestDigest, "requestDigest")
        val existing = findCommandRecord(key)
        if (existing == null) {
            try {
                val recordId = WaitlistCommandRecords.insertAndGetId {
                    it[tenantGroupId] = key.tenantGroupId
                    it[clinicId] = key.clinicId
                    it[commandType] = key.commandType
                    it[keyDigest] = key.keyDigest
                    it[WaitlistCommandRecords.requestDigest] = validRequestDigest
                    it[status] = WaitlistCommandState.PROCESSING
                    it[expiresAt] = now.plus(COMMAND_RETENTION)
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                return CommandReservation.Acquired(recordId)
            } catch (failure: ExposedSQLException) {
                if (!WaitlistCommandDuplicateClassifier.isCommandReservationDuplicate(failure)) {
                    throw failure
                }
            } catch (failure: SQLException) {
                if (!WaitlistCommandDuplicateClassifier.isCommandReservationDuplicate(failure)) {
                    throw failure
                }
            }
        }

        val record = findCommandRecord(key) ?: throw WaitlistContention()
        if (record.requestDigest != validRequestDigest) {
            throw IdempotencyRequestMismatch()
        }
        return when (record.status) {
            WaitlistCommandState.PROCESSING -> CommandReservation.InProgress()

            WaitlistCommandState.SUCCEEDED -> CommandReservation.ReplaySucceeded(
                status = SUCCESS_REPLAY_STATUS,
                resultBody = """{"type":"${record.resultType}","id":${record.resultId}}""",
            )

            WaitlistCommandState.FAILED -> CommandReservation.ReplayFailed(
                status = FAILURE_REPLAY_STATUS,
                errorBody = """{"code":"${record.failureCode}"}""",
            )
        }
    }

    fun completeCommandSucceeded(
        recordId: Long,
        requestDigest: String,
        resultType: String,
        resultId: Long,
        responseDigest: String,
        now: Instant,
    ): Boolean {
        val validRecordId = recordId.requirePositiveNumber("recordId")
        val validRequestDigest = validateDigest(requestDigest, "requestDigest")
        val validResultType = resultType.requireNotBlank("resultType")
        require(COMMAND_RESULT_TYPE_REGEX.matches(validResultType)) {
            "resultType must contain 1..64 uppercase safe characters"
        }
        val validResultId = resultId.requirePositiveNumber("resultId")
        val validResponseDigest = validateDigest(responseDigest, "responseDigest")
        return WaitlistCommandRecords.update({
            (WaitlistCommandRecords.id eq validRecordId) and
                (WaitlistCommandRecords.requestDigest eq validRequestDigest) and
                (WaitlistCommandRecords.status eq WaitlistCommandState.PROCESSING)
        }) {
            it[status] = WaitlistCommandState.SUCCEEDED
            it[WaitlistCommandRecords.resultType] = validResultType
            it[WaitlistCommandRecords.resultId] = validResultId
            it[WaitlistCommandRecords.responseDigest] = validResponseDigest
            it[updatedAt] = now
        } == 1
    }

    fun reconcileCommandSucceeded(
        key: WaitlistCommandKey,
        requestDigest: String,
        resultType: String,
        resultId: Long,
        responseDigest: String,
        now: Instant,
    ): Boolean {
        val record = findCommandRecord(key) ?: return false
        if (record.requestDigest != validateDigest(requestDigest, "requestDigest")) {
            throw IdempotencyRequestMismatch()
        }
        if (record.status == WaitlistCommandState.SUCCEEDED) return true
        return completeCommandSucceeded(
            recordId = record.id,
            requestDigest = requestDigest,
            resultType = resultType,
            resultId = resultId,
            responseDigest = responseDigest,
            now = now,
        )
    }

    fun completeCommandFailed(
        recordId: Long,
        requestDigest: String,
        failureCode: String,
        now: Instant,
    ): Boolean =
        markCommandFailedIfProcessing(recordId, requestDigest, failureCode, now)

    fun markCommandFailedIfProcessing(
        recordId: Long,
        requestDigest: String,
        failureCode: String,
        now: Instant,
    ): Boolean {
        val validRecordId = recordId.requirePositiveNumber("recordId")
        val validRequestDigest = validateDigest(requestDigest, "requestDigest")
        val validFailureCode = failureCode.requireNotBlank("failureCode")
        require(STABLE_ERROR_CODE_REGEX.matches(validFailureCode)) {
            "failureCode must contain 1..96 uppercase safe characters"
        }
        return WaitlistCommandRecords.update({
            (WaitlistCommandRecords.id eq validRecordId) and
                (WaitlistCommandRecords.requestDigest eq validRequestDigest) and
                (WaitlistCommandRecords.status eq WaitlistCommandState.PROCESSING)
        }) {
            it[status] = WaitlistCommandState.FAILED
            it[WaitlistCommandRecords.failureCode] = validFailureCode
            it[updatedAt] = now
        } == 1
    }

    fun purgeExpiredCommands(
        tenantGroupId: Long,
        clinicId: Long,
        now: Instant,
    ): Int =
        WaitlistCommandRecords.deleteWhere {
            (WaitlistCommandRecords.tenantGroupId eq tenantGroupId.requirePositiveNumber("tenantGroupId")) and
                (WaitlistCommandRecords.clinicId eq clinicId.requirePositiveNumber("clinicId")) and
                (WaitlistCommandRecords.expiresAt lessEq now)
        }

    /**
     * transaction 경계 밖에서 호출하는 contention coordinator입니다.
     * callback은 매 attempt마다 fresh top-level transaction을 열어야 합니다.
     */
    fun <T> withContentionRetry(block: () -> T): T {
        var attempt = 1
        val strategy = resolvedClaimStrategy()
        while (true) {
            try {
                return block()
            } catch (failure: ExposedSQLException) {
                if (!failure.isRetryableContention(strategy)) {
                    throw failure
                }
                if (attempt >= retryPolicy.maxAttempts) {
                    throw WaitlistContention(failure)
                }
                sleepBeforeRetry(attempt)
                attempt += 1
            } catch (failure: SQLException) {
                if (!failure.isRetryableContention(strategy)) {
                    throw failure
                }
                if (attempt >= retryPolicy.maxAttempts) {
                    throw WaitlistContention(failure)
                }
                sleepBeforeRetry(attempt)
                attempt += 1
            }
        }
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            retryPolicy.sleepBeforeRetry(attempt)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WaitlistContention(failure)
        }
    }

    private fun claimWithStrategy(
        strategy: VacancyClaimStrategy,
        jobId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): VacancyClaim? {
        val current =
            when (strategy.mode) {
                VacancyClaimMode.LOCKED_SELECTION -> findClaimCandidate(jobId, now, forUpdate = true)
            } ?: return null
        val eligible = current.status == VacancyJobState.READY ||
            (current.status == VacancyJobState.PROCESSING && current.leaseExpiresAt != null && current.leaseExpiresAt <= now)
        if (!eligible) return null

        val nextVersion = current.version + 1L
        val nextLeaseVersion = current.leaseVersion + 1L
        val affected = WaitlistVacancyJobs.update({
            (WaitlistVacancyJobs.id eq jobId) and
                (WaitlistVacancyJobs.version eq current.version) and
                (WaitlistVacancyJobs.leaseVersion eq current.leaseVersion) and
                (
                    (WaitlistVacancyJobs.status eq VacancyJobState.READY) or
                        (
                            (WaitlistVacancyJobs.status eq VacancyJobState.PROCESSING) and
                                (WaitlistVacancyJobs.leaseExpiresAt lessEq now)
                            )
                    )
        }) {
            it[status] = VacancyJobState.PROCESSING
            it[leaseOwner] = owner
            it[leaseVersion] = nextLeaseVersion
            it[leaseExpiresAt] = leaseUntil
            it[version] = nextVersion
            it[updatedAt] = now
        }
        return if (affected == 1) {
            VacancyClaim(
                jobId = jobId,
                owner = owner,
                version = nextVersion,
                leaseVersion = nextLeaseVersion,
                expiresAt = leaseUntil,
            )
        } else {
            null
        }
    }

    private fun findClaimCandidate(
        jobId: Long,
        now: Instant,
        forUpdate: Boolean,
    ): VacancyJobRecord? {
        val query =
            WaitlistVacancyJobs
                .selectAll()
                .where {
                    (WaitlistVacancyJobs.id eq jobId) and
                        (
                            (WaitlistVacancyJobs.status eq VacancyJobState.READY) or
                                (
                                    (WaitlistVacancyJobs.status eq VacancyJobState.PROCESSING) and
                                        (WaitlistVacancyJobs.leaseExpiresAt lessEq now)
                                    )
                            )
                }
                .limit(1)
        return (if (forUpdate) query.forUpdate() else query)
            .singleOrNull()
            ?.toVacancyJobRecord()
    }

    private fun terminalUpdate(
        claim: VacancyClaim,
        now: Instant,
        status: VacancyJobState,
        resultOfferId: Long?,
        errorCode: String?,
    ): Boolean =
        WaitlistVacancyJobs.update({
            (WaitlistVacancyJobs.id eq claim.jobId) and
                (WaitlistVacancyJobs.status eq VacancyJobState.PROCESSING) and
                (WaitlistVacancyJobs.leaseOwner eq claim.owner) and
                (WaitlistVacancyJobs.version eq claim.version) and
                (WaitlistVacancyJobs.leaseVersion eq claim.leaseVersion) and
                (WaitlistVacancyJobs.leaseExpiresAt greater now)
        }) {
            it[WaitlistVacancyJobs.status] = status
            it[offeredWaitlistEntryId] = resultOfferId
            it[activeVacancyKey] = null
            it[leaseOwner] = null
            it[leaseExpiresAt] = null
            it[lastErrorCode] = errorCode
            it[version] = claim.version + 1L
            it[updatedAt] = now
        } == 1

    private fun findCommandRecord(key: WaitlistCommandKey): CommandRecord? =
        WaitlistCommandRecords
            .selectAll()
            .where {
                (WaitlistCommandRecords.tenantGroupId eq key.tenantGroupId) and
                    (WaitlistCommandRecords.clinicId eq key.clinicId) and
                    (WaitlistCommandRecords.commandType eq key.commandType) and
                    (WaitlistCommandRecords.keyDigest eq key.keyDigest)
            }
            .orderBy(WaitlistCommandRecords.id, SortOrder.ASC)
            .singleOrNull()
            ?.toCommandRecord()

    private fun validateDigest(value: String, name: String): String {
        val validValue = value.requireNotBlank(name)
        require(SHA256_REGEX.matches(validValue)) {
            "$name must be lowercase SHA-256"
        }
        return validValue
    }

    private fun resolvedClaimStrategy(): VacancyClaimStrategy =
        claimStrategy ?: VacancyClaimStrategies.current()

    private fun SQLException.isRetryableContention(strategy: VacancyClaimStrategy): Boolean =
        sqlState in RETRYABLE_SQL_STATES ||
            isPostgreSqlLockTimeout(strategy)

    private fun ExposedSQLException.isRetryableContention(strategy: VacancyClaimStrategy): Boolean =
        sqlState in RETRYABLE_SQL_STATES ||
            isPostgreSqlLockTimeout(strategy)

    private fun SQLException.isPostgreSqlLockTimeout(strategy: VacancyClaimStrategy): Boolean =
        sqlState == POSTGRESQL_LOCK_NOT_AVAILABLE_SQL_STATE &&
            strategy.dialect == VacancyClaimDialect.POSTGRESQL

    private fun ExposedSQLException.isPostgreSqlLockTimeout(strategy: VacancyClaimStrategy): Boolean =
        sqlState == POSTGRESQL_LOCK_NOT_AVAILABLE_SQL_STATE &&
            strategy.dialect == VacancyClaimDialect.POSTGRESQL

    private companion object {
        private const val MAX_OWNER_LENGTH = 160
        private const val SUCCESS_REPLAY_STATUS = 200
        private const val FAILURE_REPLAY_STATUS = 409
        private val COMMAND_RETENTION: Duration = Duration.ofHours(24)
        private val TERMINAL_STATES = setOf(
            VacancyJobState.OFFERED,
            VacancyJobState.NO_CANDIDATE,
            VacancyJobState.EXPIRED,
            VacancyJobState.FAILED,
        )
        private val SHA256_REGEX = Regex("[a-f0-9]{64}")
        private val STABLE_ERROR_CODE_REGEX = Regex("[A-Z0-9_]{1,96}")
        private val COMMAND_RESULT_TYPE_REGEX = Regex("[A-Z0-9_]{1,64}")
        private val RETRYABLE_SQL_STATES = setOf("40001", "40P01")
        private const val POSTGRESQL_LOCK_NOT_AVAILABLE_SQL_STATE = "55P03"
    }
}

private fun VacancyJobRecord.matchesFence(claim: VacancyClaim, now: Instant): Boolean =
    status == VacancyJobState.PROCESSING &&
        leaseOwner == claim.owner &&
        version == claim.version &&
        leaseVersion == claim.leaseVersion &&
        leaseExpiresAt != null &&
        leaseExpiresAt.isAfter(now) &&
        claim.expiresAt.isAfter(now)

/** command idempotency unique authority에서 발생한 duplicate insert 실패만 분류합니다. */
object WaitlistCommandDuplicateClassifier {
    fun isCommandReservationDuplicate(failure: Throwable): Boolean =
        failure.sqlFailureChain().any { sqlFailure ->
            sqlFailure.isKnownUniqueViolation() && sqlFailure.referencesCommandIdempotencyAuthority()
        }

    private fun Throwable.sqlFailureChain(): Sequence<SQLException> =
        sequence {
            val seen = mutableSetOf<Throwable>()
            var current: Throwable? = this@sqlFailureChain
            while (current != null && seen.add(current)) {
                if (current is SQLException) {
                    yield(current)
                    var next = current.nextException
                    while (next != null && seen.add(next)) {
                        yield(next)
                        next = next.nextException
                    }
                }
                current = current.cause
            }
        }

    private fun SQLException.isKnownUniqueViolation(): Boolean =
        sqlState == POSTGRES_UNIQUE_SQL_STATE

    private fun SQLException.referencesCommandIdempotencyAuthority(): Boolean {
        val lowerMessage = message?.lowercase() ?: return false
        return COMMAND_IDEMPOTENCY_AUTHORITY_HINTS.any { hint -> hint in lowerMessage }
    }

    private const val POSTGRES_UNIQUE_SQL_STATE = "23505"
    private val COMMAND_IDEMPOTENCY_AUTHORITY_HINTS = listOf(
        "uq_waitlist_command_idempotency",
        "uq_waitlist_command",
        "scheduling_waitlist_command_records",
    )
}

/** PostgreSQL vacancy claim의 획득 방식을 식별합니다. */
enum class VacancyClaimMode {
    LOCKED_SELECTION,
}

/** vacancy claim adapter가 지원하는 PostgreSQL dialect입니다. */
enum class VacancyClaimDialect {
    POSTGRESQL,
}

/**
 * DB session에 적용할 bounded lock wait 설정입니다.
 *
 * PostgreSQL은 `SET LOCAL`로 transaction scope에 묶어 lock wait 설정이 호출자 transaction
 * 바깥으로 새지 않도록 합니다.
 */
data class LockTimeoutPlan(
    val timeout: Duration,
    val beforeClaimSql: List<String>,
    val afterClaimSql: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * PostgreSQL vacancy claim의 public contract입니다.
 *
 * PostgreSQL은 row lock 기반 selection을 먼저 수행합니다. 저장소 public API는 동일하게 유지됩니다.
 */
interface VacancyClaimStrategy : Serializable {
    val dialect: VacancyClaimDialect
    val mode: VacancyClaimMode
    val lockTimeoutPlan: LockTimeoutPlan?

    fun claimSelectionSql(tableName: String = WaitlistVacancyJobs.tableName): String
}

/** Exposed dialect 이름에서 waitlist vacancy claim adapter를 선택합니다. */
object VacancyClaimStrategies {
    fun current(): VacancyClaimStrategy = PostgreSqlLockedVacancyClaimStrategy

    fun forDialectName(dialectName: String): VacancyClaimStrategy =
        when (dialectName.trim().lowercase()) {
            "postgresql", "postgres", "pgsql" -> PostgreSqlLockedVacancyClaimStrategy
            else -> throw IllegalArgumentException("Only PostgreSQL is supported for vacancy claims")
        }
}

private object PostgreSqlLockedVacancyClaimStrategy : VacancyClaimStrategy {
    override val dialect: VacancyClaimDialect = VacancyClaimDialect.POSTGRESQL
    override val mode: VacancyClaimMode = VacancyClaimMode.LOCKED_SELECTION
    override val lockTimeoutPlan: LockTimeoutPlan =
        LockTimeoutPlan(
            timeout = Duration.ofSeconds(2),
            beforeClaimSql = listOf("SET LOCAL lock_timeout = '2s'"),
            afterClaimSql = emptyList(),
        )

    override fun claimSelectionSql(tableName: String): String =
        "SELECT * FROM $tableName WHERE id = ? AND status IN ('READY', 'PROCESSING') FOR UPDATE"

    private fun readResolve(): Any = PostgreSqlLockedVacancyClaimStrategy
}

/** DB contention retry의 attempt 수, jitter 계산, sleep side effect를 주입합니다. */
class ContentionRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val jitterDelay: (Int) -> Duration = { Duration.ZERO },
    private val sleeper: (Duration) -> Unit = { delay ->
        if (!delay.isZero && !delay.isNegative) {
            Thread.sleep(delay.toMillis())
        }
    },
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "maxAttempts must be in 1..$MAX_ATTEMPTS"
        }
    }

    fun sleepBeforeRetry(attempt: Int) {
        val delay = jitterDelay(attempt)
        require(!delay.isNegative) { "retry delay must be zero or positive" }
        sleeper(delay)
    }

    private companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 3
        private const val MAX_ATTEMPTS = 3
    }
}

/** 새 durable vacancy job 입력입니다. */
data class NewVacancyJob(
    val tenantGroupId: Long,
    val clinicId: Long,
    val vacancyKey: String,
    val vacancyGeneration: Long,
    val activeVacancyKey: String?,
    val sourceAppointmentId: Long,
    val sourceTransitionId: String,
    val resourceType: ResourceType,
    val resourceId: String,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val treatmentTypeId: Long,
    val doctorId: Long?,
    val policyVersion: Long,
    val nextAttemptAt: Instant,
    val vacancyStartsAt: Instant,
    val vacancyEndsAt: Instant,
    val now: Instant,
) : Serializable {
    fun validate() {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        vacancyKey.requireNotBlank("vacancyKey")
        require(vacancyGeneration > 0L) { "vacancyGeneration must be positive" }
        activeVacancyKey?.requireNotBlank("activeVacancyKey")
        sourceAppointmentId.requirePositiveNumber("sourceAppointmentId")
        sourceTransitionId.requireNotBlank("sourceTransitionId")
        resourceId.requireNotBlank("resourceId")
        require(capacityUnits > 0) { "capacityUnits must be positive" }
        require(maximumCapacity > 0) { "maximumCapacity must be positive" }
        require(capacityUnits <= maximumCapacity) { "capacityUnits must be less than or equal to maximumCapacity" }
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        doctorId?.requirePositiveNumber("doctorId")
        policyVersion.requirePositiveNumber("policyVersion")
        require(vacancyStartsAt < vacancyEndsAt) { "vacancyStartsAt must be earlier than vacancyEndsAt" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 저장된 durable vacancy job snapshot입니다. */
data class VacancyJobRecord(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val vacancyKey: String,
    val vacancyGeneration: Long,
    val activeVacancyKey: String?,
    val sourceAppointmentId: Long,
    val sourceTransitionId: String,
    val resourceType: ResourceType,
    val resourceId: String,
    val capacityUnits: Int,
    val maximumCapacity: Int,
    val treatmentTypeId: Long,
    val doctorId: Long?,
    val policyVersion: Long,
    val status: VacancyJobState,
    val leaseOwner: String?,
    val leaseVersion: Long,
    val leaseExpiresAt: Instant?,
    val nextAttemptAt: Instant,
    val vacancyStartsAt: Instant,
    val vacancyEndsAt: Instant,
    val resultOfferId: Long?,
    val lastErrorCode: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** worker terminal write에 필요한 lease owner/version fence입니다. */
data class VacancyClaim(
    val jobId: Long,
    val owner: String,
    val version: Long,
    val leaseVersion: Long,
    val expiresAt: Instant,
) : Serializable {
    init {
        jobId.requirePositiveNumber("jobId")
        owner.requireNotBlank("owner")
        require(version >= 0L) { "version must be zero or positive" }
        require(leaseVersion >= 0L) { "leaseVersion must be zero or positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist command idempotency reservation 결과입니다. */
sealed interface CommandReservation : Serializable {
    data class Acquired(val recordId: Long) : CommandReservation {
        init {
            recordId.requirePositiveNumber("recordId")
        }

        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }

    data class InProgress(val retryAfterSeconds: Long = 1L) : CommandReservation {
        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }

    data class ReplaySucceeded(val status: Int, val resultBody: String) : CommandReservation {
        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }

    data class ReplayFailed(val status: Int, val errorBody: String) : CommandReservation {
        private companion object {
            const val serialVersionUID: Long = 1L
        }
    }
}

private data class CommandRecord(
    val id: Long,
    val requestDigest: String,
    val status: WaitlistCommandState,
    val resultType: String?,
    val resultId: Long?,
    val failureCode: String?,
)

private fun ResultRow.toVacancyJobRecord(): VacancyJobRecord =
    VacancyJobRecord(
        id = this[WaitlistVacancyJobs.id].value,
        tenantGroupId = this[WaitlistVacancyJobs.tenantGroupId].value,
        clinicId = this[WaitlistVacancyJobs.clinicId].value,
        vacancyKey = this[WaitlistVacancyJobs.vacancyKey],
        vacancyGeneration = this[WaitlistVacancyJobs.vacancyGeneration],
        activeVacancyKey = this[WaitlistVacancyJobs.activeVacancyKey],
        sourceAppointmentId = this[WaitlistVacancyJobs.sourceAppointmentId],
        sourceTransitionId = this[WaitlistVacancyJobs.sourceTransitionId],
        resourceType = this[WaitlistVacancyJobs.resourceType],
        resourceId = this[WaitlistVacancyJobs.resourceId],
        capacityUnits = this[WaitlistVacancyJobs.capacityUnits],
        maximumCapacity = this[WaitlistVacancyJobs.maximumCapacity],
        treatmentTypeId = this[WaitlistVacancyJobs.treatmentTypeId],
        doctorId = this[WaitlistVacancyJobs.doctorId],
        policyVersion = this[WaitlistVacancyJobs.policyVersion],
        status = this[WaitlistVacancyJobs.status],
        leaseOwner = this[WaitlistVacancyJobs.leaseOwner],
        leaseVersion = this[WaitlistVacancyJobs.leaseVersion],
        leaseExpiresAt = this[WaitlistVacancyJobs.leaseExpiresAt],
        nextAttemptAt = this[WaitlistVacancyJobs.nextAttemptAt],
        vacancyStartsAt = this[WaitlistVacancyJobs.vacancyStartsAt],
        vacancyEndsAt = this[WaitlistVacancyJobs.vacancyEndsAt],
        resultOfferId = this[WaitlistVacancyJobs.offeredWaitlistEntryId],
        lastErrorCode = this[WaitlistVacancyJobs.lastErrorCode],
        version = this[WaitlistVacancyJobs.version],
        createdAt = this[WaitlistVacancyJobs.createdAt],
        updatedAt = this[WaitlistVacancyJobs.updatedAt],
    )

private fun ResultRow.toCommandRecord(): CommandRecord =
    CommandRecord(
        id = this[WaitlistCommandRecords.id].value,
        requestDigest = this[WaitlistCommandRecords.requestDigest],
        status = this[WaitlistCommandRecords.status],
        resultType = this[WaitlistCommandRecords.resultType],
        resultId = this[WaitlistCommandRecords.resultId],
        failureCode = this[WaitlistCommandRecords.failureCode],
    )
