package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

/**
 * 내부 운영 endpoint의 조회와 redrive를 bounded 명령으로 제한합니다.
 *
 * preview는 저장 상태를 변경하지 않습니다. execute는 실패 원본을 수정하지 않고 저장소의
 * lineage/CAS 계약으로 새 attempt만 생성합니다. idempotency key 원문은 보관하지 않습니다.
 */
class ProfileReevaluationAdminService(
    private val store: ProfileReevaluationAdminStore,
    private val redriveCooldown: Duration = Duration.ofMinutes(30),
    private val maximumPageSize: Int = 100,
    private val auditSink: ProfileReevaluationAdminAuditSink =
        LoggingProfileReevaluationAdminAuditSink,
) : ProfileReevaluationHealthSource {
    private val replayCache = object : LinkedHashMap<String, CachedAdminResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAdminResult>?): Boolean =
            size > MAX_REPLAY_CACHE_SIZE
    }
    private val executeMutex = Mutex()

    init {
        require(!redriveCooldown.isNegative) { "redriveCooldown must be non-negative" }
        require(maximumPageSize in 1..100) { "maximumPageSize must be between 1 and 100" }
    }

    override suspend fun snapshot(): ProfileReevaluationOperationalSnapshot = store.snapshot()

    suspend fun redrive(command: ProfileReevaluationAdminCommand): ProfileReevaluationAdminResult {
        command.validate(maximumPageSize)
        val requestHash = command.requestHash()
        if (command.action == ProfileReevaluationAdminAction.PREVIEW) {
            return preview(command).also { result ->
                auditSink.record(command.audit(result, replayed = false))
            }
        }
        return executeMutex.withLock {
            replayCache[command.idempotencyDigest]?.let { cached ->
                require(cached.requestHash == requestHash) {
                    "idempotencyKey cannot be reused for a different redrive request"
                }
                auditSink.record(command.audit(cached.result, replayed = true))
                return@withLock cached.result
            }

            val preview = preview(command)
            val created = preview.jobs.mapNotNull { job ->
                store.redrive(
                    RedriveProfileReevaluationJob(
                        jobId = job.jobId,
                        cooldown = redriveCooldown,
                        expectedRedriveCount = job.redriveCount,
                    ),
                )
            }
            val result = ProfileReevaluationAdminResult(
                action = ProfileReevaluationAdminAction.EXECUTE,
                matched = preview.matched,
                created = created.size,
                jobs = created.map(ProfileReevaluationAdminJob::from),
            )
            replayCache[command.idempotencyDigest] = CachedAdminResult(requestHash, result)
            auditSink.record(command.audit(result, replayed = false))
            result
        }
    }

    private suspend fun preview(
        command: ProfileReevaluationAdminCommand,
    ): ProfileReevaluationAdminResult {
        val jobs = store.findFailed(command.scope, command.limit)
        return ProfileReevaluationAdminResult(
            action = ProfileReevaluationAdminAction.PREVIEW,
            matched = jobs.size,
            created = 0,
            jobs = jobs.map(ProfileReevaluationAdminJob::from),
        )
    }

    private data class CachedAdminResult(
        val requestHash: String,
        val result: ProfileReevaluationAdminResult,
    )

    private companion object {
        const val MAX_REPLAY_CACHE_SIZE = 1_000
    }
}

fun interface ProfileReevaluationAdminAuditSink {
    fun record(event: ProfileReevaluationAdminAuditEvent)
}

/**
 * 운영 명령의 감사 정보를 구조화된 로그로 남깁니다.
 *
 * 자유 입력인 사유와 idempotency key 원문은 로그에 남기지 않고 SHA-256 digest만 기록합니다.
 * 환자 fingerprint와 개별 작업 식별자도 기록하지 않습니다.
 */
object LoggingProfileReevaluationAdminAuditSink :
    ProfileReevaluationAdminAuditSink,
    KLogging() {
    override fun record(event: ProfileReevaluationAdminAuditEvent) {
        log.info {
            "Profile reevaluation admin command: " +
                "action=${event.action}, actor=${event.actor}, reasonDigest=${event.reasonDigest}, " +
                "idempotencyDigest=${event.idempotencyDigest}, " +
                "tenantGroupId=${event.scope.tenantGroupId}, clinicId=${event.scope.clinicId}, " +
                "targetRevision=${event.scope.targetRevision}, matched=${event.matched}, " +
                "created=${event.created}, replayed=${event.replayed}"
        }
    }
}

data class ProfileReevaluationAdminAuditEvent(
    val action: ProfileReevaluationAdminAction,
    val actor: String,
    val reasonDigest: String,
    val idempotencyDigest: String,
    val scope: ProfileReevaluationAdminScope,
    val matched: Int,
    val created: Int,
    val replayed: Boolean,
)

interface ProfileReevaluationAdminStore {
    suspend fun snapshot(): ProfileReevaluationOperationalSnapshot

    suspend fun findFailed(
        scope: ProfileReevaluationAdminScope,
        limit: Int,
    ): List<ProfileReevaluationJobRecord>

    suspend fun redrive(command: RedriveProfileReevaluationJob): ProfileReevaluationJobRecord?
}

/**
 * 운영 조회마다 짧은 Exposed transaction을 여는 저장소 adapter입니다.
 */
class ExposedProfileReevaluationAdminStore(
    private val database: Database,
    private val repository: ProfileReevaluationRepository,
    private val runtimeGate: ProfileReevaluationRuntimeGate,
    private val monitor: ProfileReevaluationOperationalMonitor,
) : ProfileReevaluationAdminStore {
    override suspend fun snapshot(): ProfileReevaluationOperationalSnapshot =
        io {
            val summary = repository.summarizeOperations()
            val access = runtimeGate.read()
            val backlog = summary.pendingJobs + summary.runningJobs + summary.retryWaitJobs
            monitor.enrich(ProfileReevaluationOperationalSnapshot(
                pendingJobs = summary.pendingJobs,
                runningJobs = summary.runningJobs,
                retryWaitJobs = summary.retryWaitJobs,
                failedJobs = summary.failedJobs,
                activeLeases = summary.activeLeases,
                oldestBacklogAge = summary.oldestBacklogAge,
                drainState = when {
                    access.mode != ProfileReevaluationMutationMode.DISABLED ->
                        ProfileReevaluationDrainState.ACTIVE
                    backlog > 0 || summary.activeLeases > 0 ->
                        ProfileReevaluationDrainState.DRAINING
                    else -> ProfileReevaluationDrainState.DRAINED
                },
            ))
        }

    override suspend fun findFailed(
        scope: ProfileReevaluationAdminScope,
        limit: Int,
    ): List<ProfileReevaluationJobRecord> =
        io {
            repository.findFailedJobs(
                tenantGroupId = scope.tenantGroupId,
                clinicId = scope.clinicId,
                targetRevision = scope.targetRevision,
                limit = limit,
            )
        }

    override suspend fun redrive(
        command: RedriveProfileReevaluationJob,
    ): ProfileReevaluationJobRecord? = io { repository.redriveFailed(command) }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }
}

enum class ProfileReevaluationAdminAction {
    PREVIEW,
    EXECUTE,
}

data class ProfileReevaluationAdminScope(
    val tenantGroupId: Long? = null,
    val clinicId: Long? = null,
    val targetRevision: Long? = null,
) {
    init {
        require(tenantGroupId == null || tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId == null || clinicId > 0) { "clinicId must be positive" }
        require(targetRevision == null || targetRevision > 0) { "targetRevision must be positive" }
        require(clinicId == null || tenantGroupId != null) {
            "clinicId scope requires tenantGroupId"
        }
    }
}

data class ProfileReevaluationAdminCommand(
    val action: ProfileReevaluationAdminAction,
    val actor: String,
    val reason: String,
    val idempotencyKey: String,
    val scope: ProfileReevaluationAdminScope = ProfileReevaluationAdminScope(),
    val limit: Int = 50,
) {
    internal val idempotencyDigest: String
        get() = sha256(idempotencyKey)

    internal fun validate(maximumPageSize: Int) {
        require(SAFE_ACTOR.matches(actor)) { "actor must be a bounded identifier" }
        require(reason.isNotBlank() && reason.length <= 500) {
            "reason must contain 1..500 characters"
        }
        require(SAFE_IDEMPOTENCY_KEY.matches(idempotencyKey)) {
            "idempotencyKey must be a bounded safe key"
        }
        require(limit in 1..maximumPageSize) {
            "limit must be between 1 and $maximumPageSize"
        }
    }

    internal fun requestHash(): String =
        sha256("$action|$actor|$reason|$scope|$limit")

    internal fun audit(
        result: ProfileReevaluationAdminResult,
        replayed: Boolean,
    ): ProfileReevaluationAdminAuditEvent =
        ProfileReevaluationAdminAuditEvent(
            action = action,
            actor = actor,
            reasonDigest = sha256(reason),
            idempotencyDigest = idempotencyDigest,
            scope = scope,
            matched = result.matched,
            created = result.created,
            replayed = replayed,
        )

    private companion object {
        val SAFE_ACTOR = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,159}")
        val SAFE_IDEMPOTENCY_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}")
    }
}

data class ProfileReevaluationAdminResult(
    val action: ProfileReevaluationAdminAction,
    val matched: Int,
    val created: Int,
    val jobs: List<ProfileReevaluationAdminJob>,
)

data class ProfileReevaluationAdminJob(
    val jobId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val targetRevision: Long,
    val redriveCount: Int,
    val redriveGeneration: Int,
    val updatedAt: Instant,
) {
    companion object {
        fun from(job: ProfileReevaluationJobRecord) =
            ProfileReevaluationAdminJob(
                jobId = job.id,
                tenantGroupId = job.scope.tenantGroupId,
                clinicId = job.scope.clinicId,
                targetRevision = job.targetRevision,
                redriveCount = job.redriveCount,
                redriveGeneration = job.redriveGeneration,
                updatedAt = job.updatedAt,
            )
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
