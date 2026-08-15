package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryEntryResponse
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryPageResponse
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryQuery
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.tenant.TenantCodeRules
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.CancellationHistoryBoundary
import io.bluetape4k.clinic.appointment.model.dto.PatientCancellationHistoryPage
import io.bluetape4k.clinic.appointment.model.dto.PatientCancellationHistoryRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.AppointmentCancellationHistoryRepository
import io.bluetape4k.clinic.appointment.repository.CancellationHistoryAnchorMissingException
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.JoinType
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Instant
import java.sql.Connection
import java.sql.SQLException
import kotlin.math.min

/** 환자 취소 이력의 tenant/patient ownership, keyset, reference, ETag 경계를 소유합니다. */
internal class PatientCancellationHistoryService(
    private val database: Database,
    private val tenantGroupRepository: TenantGroupRepository,
    private val historyRepository: AppointmentCancellationHistoryRepository,
    private val patientSubjectFingerprintResolver: PatientSubjectFingerprintResolver,
    private val cursorCodec: PatientHistoryCursorCodec,
    private val referenceCodec: PatientHistoryReferenceCodec,
    private val etagCodec: PatientHistoryEtagCodec,
    private val readiness: PatientHistoryReadiness = PatientHistoryReadiness.ALLOW,
    private val metadataMetrics: PatientHistoryMetadataMetrics = PatientHistoryMetadataMetrics.NOOP,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelay: (Long) -> Unit = Thread::sleep,
) {
    /** page body와 strong ETag를 계산합니다. If-None-Match가 일치하면 body는 null입니다. */
    fun read(
        actor: ActorContext,
        tenantCode: String,
        query: PatientCancellationHistoryQuery,
        ifNoneMatch: String?,
    ): PatientCancellationHistoryReadResult {
        require(TenantCodeRules.isCanonical(tenantCode)) { "tenantCode must be canonical" }
        if (actor.actorType != ActorType.PATIENT || actor.patientSubjectId.isNullOrBlank()) {
            throw PatientHistoryApiException(PatientHistoryApiError.SCOPE_FORBIDDEN)
        }
        val limit = query.limit.takeIf { it in 1..50 }
            ?: throw PatientHistoryApiException(PatientHistoryApiError.LIMIT_INVALID)
        if (ifNoneMatch != null && !etagCodec.isStrongTag(ifNoneMatch)) {
            throw PatientHistoryApiException(PatientHistoryApiError.PAYLOAD_INVALID)
        }
        val deadlineNanos = System.nanoTime() + READ_DEADLINE_NANOS
        try {
            readiness.requireReady()
            cursorCodec.requireReady(deadlineNanos)
        } catch (failure: PatientHistoryCursorException) {
            throw failure.toApiException()
        } catch (failure: PatientHistoryApiException) {
            throw failure
        } catch (failure: Exception) {
            throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE, failure)
        }
        val scope = try {
            // Registry I/O must not hold a database connection. Resolve only the
            // authenticated tenant/patient scope in this short transaction first.
            readTransaction(deadlineNanos) {
                val tenant = tenantGroupRepository.findActiveByCode(tenantCode)
                    ?: throw PatientHistoryApiException(PatientHistoryApiError.TENANT_NOT_FOUND)
                val tenantId = tenant.id ?: throw PatientHistoryApiException(PatientHistoryApiError.TENANT_NOT_FOUND)
                HistoryScope(
                    tenantId = tenantId,
                    fingerprint = patientSubjectFingerprintResolver.fingerprint(tenantId, actor.patientSubjectId),
                )
            }
        } catch (failure: PatientHistoryApiException) {
            throw failure
        } catch (failure: Exception) {
            if (failure.hasSqlFailure()) {
                throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE, failure)
            }
            throw failure
        }
        val boundary = query.cursor?.let { cursor ->
            val payload = try {
                cursorCodec.decodeAuthenticated(cursor)
            } catch (failure: PatientHistoryCursorException) {
                throw failure.toApiException()
            }
            if (payload.tenantGroupId != scope.tenantId || payload.patientScopeFingerprint != scope.fingerprint) {
                throw PatientHistoryApiException(PatientHistoryApiError.SNAPSHOT_CONFLICT)
            }
            try {
                // The shared registry is outside the DB transaction and uses the same deadline.
                cursorCodec.verifyRegistry(cursor, payload, deadlineNanos)
            } catch (failure: PatientHistoryCursorException) {
                throw failure.toApiException()
            }
            CancellationHistoryBoundary(payload.occurredAt, payload.detailId)
        }
        val snapshot = try {
            readTransaction(deadlineNanos) {
                HistorySnapshot(
                    tenantId = scope.tenantId,
                    fingerprint = scope.fingerprint,
                    page = historyRepository.findPage(
                        tenantGroupId = scope.tenantId,
                        patientScopeFingerprint = scope.fingerprint,
                        boundary = boundary,
                        limit = limit,
                    ),
                )
            }
        } catch (failure: PatientHistoryApiException) {
            throw failure
        } catch (failure: CancellationHistoryAnchorMissingException) {
            throw PatientHistoryApiException(PatientHistoryApiError.SNAPSHOT_CONFLICT, failure)
        } catch (failure: Exception) {
            if (failure.hasSqlFailure()) {
                throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE, failure)
            }
            throw failure
        }
        val tenantId = snapshot.tenantId
        val fingerprint = snapshot.fingerprint
        val page = snapshot.page
        if (page.metadataAmbiguousCount > 0) {
            metadataMetrics.recordAmbiguous(page.metadataAmbiguousCount)
        }
        val mapped = page.entries.map { it.toResponse() }
        val nextCursor = try {
            if (page.hasNext) {
                val last = page.entries.last()
                val issuedAt = Instant.now(clock)
                cursorCodec.encode(
                    PatientHistoryCursorPayload(
                        issuedKeyId = cursorCodec.activeKeyId,
                        issuedAt = issuedAt,
                        issuedAtBucket = floorBucket(issuedAt),
                        tenantGroupId = tenantId,
                        patientScopeFingerprint = fingerprint,
                        occurredAt = last.occurredAt,
                        detailId = last.detailId,
                    ),
                    deadlineNanos,
                )
            } else {
                null
            }
        } catch (failure: PatientHistoryCursorException) {
            throw failure.toApiException()
        }
        val response = PatientCancellationHistoryPageResponse(limit, mapped, nextCursor)
        val etagEntries = page.entries.zip(mapped).map { (record, entry) ->
            PatientHistoryEtagEntry.from(
                record = record,
                appointmentRef = entry.appointmentRef,
                fromStatusLabel = entry.fromStatusLabel,
                toStatusLabel = entry.toStatusLabel,
                reasonLabel = entry.reasonLabel,
                actorLabel = entry.actorLabel,
            )
        }
        val etag = try {
            etagCodec.strongTag(limit, query.cursor, etagEntries, nextCursor)
        } catch (failure: IllegalArgumentException) {
            // canonical field/response-size overflow is a server-side contract failure,
            // not a client payload error. Keep the public route-specific envelope.
            throw PatientHistoryApiException(PatientHistoryApiError.RESPONSE_TOO_LARGE, failure)
        }
        return PatientCancellationHistoryReadResult(
            body = if (ifNoneMatch == etag) null else response,
            etag = etag,
            notModified = ifNoneMatch == etag,
        )
    }

    private fun PatientCancellationHistoryRecord.toResponse(): PatientCancellationHistoryEntryResponse {
        val normalizedActor = actorRole.takeIf { it in KNOWN_ACTORS } ?: "UNKNOWN"
        return PatientCancellationHistoryEntryResponse(
            appointmentRef = referenceCodec.encode(tenantGroupId, patientScopeFingerprint, appointmentId, detailId),
            productName = productName,
            sessionNumber = sessionNumber,
            totalSessions = totalSessions,
            visitStartAt = visitStartAt.toString(),
            visitEndAt = visitEndAt.toString(),
            fromStatus = fromCommitmentStatus?.name,
            fromStatusLabel = fromCommitmentStatus?.let(::statusLabel),
            toStatus = AppointmentCommitmentStatus.CANCELLED.name,
            toStatusLabel = "취소",
            reasonCode = reasonCode.takeIf { it in REASON_LABELS } ?: "UNKNOWN",
            reasonLabel = REASON_LABELS[reasonCode] ?: "확인 불가",
            reasonDetail = reasonDetail,
            actorRole = normalizedActor,
            actorLabel = ACTOR_LABELS[normalizedActor] ?: "확인 불가",
            occurredAt = occurredAt.toString(),
        )
    }

    private fun statusLabel(status: AppointmentCommitmentStatus): String = when (status) {
        AppointmentCommitmentStatus.CONFIRMED -> "확정"
        AppointmentCommitmentStatus.HELD -> "선점"
        AppointmentCommitmentStatus.PROPOSED -> "제안"
        AppointmentCommitmentStatus.CANCELLED -> "취소"
        AppointmentCommitmentStatus.EXPIRED -> "만료"
    }

    private fun floorBucket(instant: Instant): Instant =
        Instant.ofEpochSecond(Math.floorDiv(instant.epochSecond, 30L * 60L) * (30L * 60L))

    /** DB read retry의 유일한 owner입니다. 각 시도는 새 read-only repeatable-read transaction입니다. */
    private fun <T> readTransaction(deadlineNanos: Long, block: () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return transaction(
                    database,
                    transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                    readOnly = true,
                    statement = { block() },
                )
            } catch (failure: Exception) {
                if (attempts >= MAX_READ_RETRIES || !failure.isRetryableSqlFailure()) throw failure
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) throw failure
                val delayMillis = min(RETRY_DELAY_MILLIS, remainingNanos / 1_000_000L)
                if (delayMillis > 0) retryDelay(delayMillis)
                attempts++
            }
        }
    }

    private fun Throwable.isRetryableSqlFailure(): Boolean =
        sqlExceptions().any { exception ->
            exception.sqlState == "40001" || exception.sqlState == "40P01" ||
                exception.sqlState?.startsWith("08") == true
        }

    private fun Throwable.hasSqlFailure(): Boolean = sqlExceptions().any()

    private fun Throwable.sqlExceptions(): Sequence<SQLException> = sequence {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = this@sqlExceptions
        while (current != null && seen.add(current)) {
            if (current is SQLException) yield(current)
            current = current.cause
        }
    }

    private data class HistorySnapshot(
        val tenantId: Long,
        val fingerprint: String,
        val page: PatientCancellationHistoryPage,
    )

    private data class HistoryScope(
        val tenantId: Long,
        val fingerprint: String,
    )

    companion object {
        private val KNOWN_ACTORS = setOf("ADMIN", "STAFF", "PATIENT", "SYSTEM")
        private val ACTOR_LABELS = mapOf(
            "ADMIN" to "관리자",
            "STAFF" to "직원",
            "PATIENT" to "환자",
            "SYSTEM" to "시스템",
            "UNKNOWN" to "확인 불가",
        )
        private val REASON_LABELS = mapOf(
            "CUSTOMER_REQUEST" to "고객 요청",
            "REFUND" to "환불 처리",
            "EQUIPMENT_FAILURE" to "장비 문제",
            "CLINIC_REQUEST" to "병원 요청",
        )
        private const val MAX_READ_RETRIES = 1
        private const val RETRY_DELAY_MILLIS = 25L
        private val READ_DEADLINE_NANOS = 750_000_000L
    }
}

/** migration residual과 shared cursor registry를 API readiness gate로 묶습니다. */
fun interface PatientHistoryReadiness {
    fun requireReady()

    companion object {
        val ALLOW: PatientHistoryReadiness = PatientHistoryReadiness { }
    }
}

/** 상품/회차 fan-out 모호성을 저카디널리티로 기록하는 관측 경계입니다. */
fun interface PatientHistoryMetadataMetrics {
    fun recordAmbiguous(count: Int)

    companion object {
        val NOOP: PatientHistoryMetadataMetrics = PatientHistoryMetadataMetrics { }
    }
}

/** V28 backfill이 끝나지 않은 상태에서는 legacy null row를 환자에게 노출하지 않습니다. */
internal class DatabasePatientHistoryReadiness(
    private val database: Database,
    private val registry: PatientHistoryTokenRegistry,
    private val writerVersionProvider: PatientHistoryWriterVersionProvider,
    private val probeIntervalNanos: Long = READINESS_PROBE_INTERVAL_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) : PatientHistoryReadiness {
    override fun requireReady() {
        if (!currentReadiness()) throw PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE)
    }

    /**
     * 요청 경로에서는 최근 probe 결과만 읽고 전역 null scan을 반복하지 않습니다.
     * 첫 요청과 60초 probe 경계에서는 동시 요청 하나만 짧은 read-only scan을 수행합니다.
     */
    private fun currentReadiness(): Boolean {
        val now = nanoTime()
        snapshot?.takeIf { now - it.checkedAtNanos < probeIntervalNanos }?.let { return it.ready }
        return synchronized(refreshLock) {
            val refreshedNow = nanoTime()
            snapshot?.takeIf { refreshedNow - it.checkedAtNanos < probeIntervalNanos }?.ready
                ?: refresh(refreshedNow)
        }
    }

    /** steady-state probe가 endpoint를 자동으로 fail-closed할 수 있도록 합니다. */
    @Scheduled(fixedDelayString = "\${appointment.patient-history.readiness-probe-interval:60s}")
    internal fun scheduledProbe() {
        synchronized(refreshLock) {
            refresh(nanoTime())
        }
    }

    private fun refresh(now: Long): Boolean {
        val ready = runCatching {
            registry.isReady() &&
                writerVersionProvider.minimumVersion() >= PatientHistoryWriterVersionProvider.REQUIRED_VERSION &&
                transaction(database, readOnly = true) {
                    val residual = AppointmentCancellationDetails
                        .selectAll()
                        .where { AppointmentCancellationDetails.patientScopeFingerprint.isNull() }
                        .limit(1)
                        .any()
                    if (residual) return@transaction false

                    val scopeMismatch = AppointmentCancellationDetails
                        .join(
                            Appointments,
                            JoinType.LEFT,
                            AppointmentCancellationDetails.appointmentId,
                            Appointments.id,
                        )
                        .join(
                            Clinics,
                            JoinType.LEFT,
                            AppointmentCancellationDetails.clinicId,
                            Clinics.id,
                        )
                        .select(AppointmentCancellationDetails.id)
                        .where {
                            Appointments.id.isNull() or Clinics.id.isNull() or
                                (AppointmentCancellationDetails.clinicId neq Appointments.clinicId) or
                                (AppointmentCancellationDetails.tenantGroupId neq Clinics.tenantGroupId)
                        }
                        .limit(1)
                        .any()
                    !scopeMismatch
                }
        }.getOrDefault(false)
        snapshot = ReadinessSnapshot(ready, now)
        return ready
    }

    private data class ReadinessSnapshot(
        val ready: Boolean,
        val checkedAtNanos: Long,
    )

    private companion object {
        const val READINESS_PROBE_INTERVAL_NANOS = 60_000_000_000L
    }

    private val refreshLock = Any()
    @Volatile private var snapshot: ReadinessSnapshot? = null
}

/** history service가 반환하는 body/304 결과입니다. */
data class PatientCancellationHistoryReadResult(
    val body: PatientCancellationHistoryPageResponse?,
    val etag: String,
    val notModified: Boolean,
)

/** 환자 이력 API의 안정적인 오류 registry입니다. */
enum class PatientHistoryApiError(
    val httpStatus: org.springframework.http.HttpStatus,
    val safeMessage: String,
    val retryable: Boolean = false,
) {
    PAYLOAD_INVALID(org.springframework.http.HttpStatus.BAD_REQUEST, "취소 이력 요청이 올바르지 않습니다."),
    LIMIT_INVALID(org.springframework.http.HttpStatus.BAD_REQUEST, "취소 이력 조회 건수가 올바르지 않습니다."),
    SCOPE_FORBIDDEN(org.springframework.http.HttpStatus.FORBIDDEN, "취소 이력을 조회할 권한이 없습니다."),
    TENANT_NOT_FOUND(org.springframework.http.HttpStatus.NOT_FOUND, "요청한 병원을 찾을 수 없습니다."),
    SNAPSHOT_CONFLICT(org.springframework.http.HttpStatus.CONFLICT, "취소 이력 조회 기준이 현재 예약과 일치하지 않습니다."),
    UNAVAILABLE(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "취소 이력을 잠시 불러올 수 없습니다.", true),
    RESPONSE_TOO_LARGE(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "취소 이력을 표시할 수 없습니다."),
}

class PatientHistoryApiException(
    val error: PatientHistoryApiError,
    cause: Throwable? = null,
) : RuntimeException(error.name, cause)

private fun PatientHistoryCursorException.toApiException(): PatientHistoryApiException =
    when (failure) {
        PatientHistoryCursorFailure.MISSING_ENTRY,
        PatientHistoryCursorFailure.REGISTRY_UNAVAILABLE,
        -> PatientHistoryApiException(PatientHistoryApiError.UNAVAILABLE, this)

        PatientHistoryCursorFailure.MALFORMED,
        PatientHistoryCursorFailure.UNKNOWN_KEY,
        PatientHistoryCursorFailure.AUTHENTICATION_FAILED,
        PatientHistoryCursorFailure.EXPIRED,
        -> PatientHistoryApiException(PatientHistoryApiError.PAYLOAD_INVALID, this)
    }
