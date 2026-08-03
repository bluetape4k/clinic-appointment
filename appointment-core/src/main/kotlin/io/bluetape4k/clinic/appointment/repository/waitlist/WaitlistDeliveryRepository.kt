package io.bluetape4k.clinic.appointment.repository.waitlist

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCommandRecords
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.clinic.appointment.model.waitlist.IdempotencyRequestMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistContention
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
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
    private val maxContentionRetries: Int = DEFAULT_MAX_CONTENTION_RETRIES,
    private val retryDelay: (Int) -> Unit = {},
) {
    init {
        require(maxContentionRetries in 1..MAX_CONTENTION_RETRIES) {
            "maxContentionRetries must be in 1..$MAX_CONTENTION_RETRIES"
        }
    }

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

        val current = findVacancy(validJobId) ?: return null
        val eligible = current.status == VacancyJobState.READY ||
            (current.status == VacancyJobState.PROCESSING && current.leaseExpiresAt != null && current.leaseExpiresAt <= now)
        if (!eligible) return null

        val nextVersion = current.version + 1L
        val affected = WaitlistVacancyJobs.update({
            (WaitlistVacancyJobs.id eq validJobId) and
                (WaitlistVacancyJobs.version eq current.version) and
                (
                    (WaitlistVacancyJobs.status eq VacancyJobState.READY) or
                        (
                            (WaitlistVacancyJobs.status eq VacancyJobState.PROCESSING) and
                                (WaitlistVacancyJobs.leaseExpiresAt lessEq now)
                            )
                    )
        }) {
            it[status] = VacancyJobState.PROCESSING
            it[leaseOwner] = validOwner
            it[leaseVersion] = current.leaseVersion + 1L
            it[leaseExpiresAt] = leaseUntil
            it[version] = nextVersion
            it[updatedAt] = now
        }
        return if (affected == 1) {
            VacancyClaim(validJobId, validOwner, nextVersion, leaseUntil)
        } else {
            null
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
                if (!failure.isUniqueConstraintViolation()) {
                    throw failure
                }
            } catch (failure: SQLException) {
                if (!failure.isUniqueConstraintViolation()) {
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

    fun <T> withContentionRetry(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (failure: ExposedSQLException) {
                if (!failure.isRetryableContention() || attempt >= maxContentionRetries) {
                    throw WaitlistContention()
                }
                retryDelay(attempt)
                attempt += 1
            } catch (failure: SQLException) {
                if (!failure.isRetryableContention() || attempt >= maxContentionRetries) {
                    throw WaitlistContention()
                }
                retryDelay(attempt)
                attempt += 1
            }
        }
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

    private fun SQLException.isRetryableContention(): Boolean =
        sqlState in RETRYABLE_SQL_STATES || errorCode in LOCK_TIMEOUT_ERROR_CODES

    private fun ExposedSQLException.isRetryableContention(): Boolean =
        sqlState in RETRYABLE_SQL_STATES || errorCode in LOCK_TIMEOUT_ERROR_CODES

    private fun SQLException.isUniqueConstraintViolation(): Boolean =
        sqlState == UNIQUE_CONSTRAINT_SQL_STATE || errorCode == H2_UNIQUE_CONSTRAINT_ERROR_CODE

    private fun ExposedSQLException.isUniqueConstraintViolation(): Boolean =
        sqlState == UNIQUE_CONSTRAINT_SQL_STATE || errorCode == H2_UNIQUE_CONSTRAINT_ERROR_CODE

    private companion object {
        private const val DEFAULT_MAX_CONTENTION_RETRIES = 3
        private const val MAX_CONTENTION_RETRIES = 3
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
        private val LOCK_TIMEOUT_ERROR_CODES = setOf(1205)
        private const val UNIQUE_CONSTRAINT_SQL_STATE = "23505"
        private const val H2_UNIQUE_CONSTRAINT_ERROR_CODE = 23505
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
    val expiresAt: Instant,
) : Serializable {
    init {
        jobId.requirePositiveNumber("jobId")
        owner.requireNotBlank("owner")
        require(version >= 0L) { "version must be zero or positive" }
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
    }

    data class InProgress(val retryAfterSeconds: Long = 1L) : CommandReservation
    data class ReplaySucceeded(val status: Int, val resultBody: String) : CommandReservation
    data class ReplayFailed(val status: Int, val errorBody: String) : CommandReservation
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
