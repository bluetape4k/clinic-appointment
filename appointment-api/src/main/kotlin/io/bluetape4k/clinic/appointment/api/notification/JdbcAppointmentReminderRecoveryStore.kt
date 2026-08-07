package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.AppointmentReminderParameters
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.LegacySuppressionDraft
import io.bluetape4k.clinic.appointment.event.notification.NotificationAuditInput
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyInput
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryCandidate
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryMaterializationResult
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryMaterializer
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryPayload
import io.bluetape4k.clinic.appointment.notification.ReminderRecoveryProgress
import io.bluetape4k.clinic.appointment.notification.ReminderRecoverySource
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max

/**
 * 확정 예약 projection을 제한된 keyset page로 읽어 누락된 reminder outbox를 복구합니다.
 *
 * 별도 환자 목록이나 연락처 snapshot을 만들지 않습니다. outbox의 unique 멱등성 key와
 * DB 순회 checkpoint를 함께 사용해 대규모 backlog 처리 중 재시작과 leader 교체에도
 * 마지막으로 완료한 예약 다음부터 이어갑니다.
 */
class JdbcAppointmentReminderRecoveryStore(
    private val database: Database,
    private val repository: NotificationOutboxRepository,
    private val hasher: NotificationOutboxHasher,
    private val sameDayReminderLeadTime: Duration,
    private val dayBeforeEnabled: Boolean,
    private val sameDayEnabled: Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReminderRecoverySource, ReminderRecoveryMaterializer {

    private val cursorMutex = Mutex()
    private val pendingCandidates = ArrayDeque<ReminderRecoveryCandidate>()

    init {
        require(!sameDayReminderLeadTime.isNegative && !sameDayReminderLeadTime.isZero) {
            "sameDayReminderLeadTime must be positive"
        }
    }

    override suspend fun findCandidates(now: Instant, limit: Int): List<ReminderRecoveryCandidate> {
        require(limit > 0) { "limit must be positive" }
        if (!dayBeforeEnabled && !sameDayEnabled) return emptyList()
        return withContext(ioDispatcher) {
            cursorMutex.withLock {
                val result = buildList {
                    while (size < limit && pendingCandidates.isNotEmpty()) {
                        add(pendingCandidates.removeFirst())
                    }
                }.toMutableList()
                if (result.size == limit) return@withLock result
                transaction(database) {
                    val checkpoint = activeCheckpoint()
                    val slotCount = listOf(dayBeforeEnabled, sameDayEnabled).count { it }
                    val remaining = limit - result.size
                    val appointmentLimit = max(1, (remaining + slotCount - 1) / slotCount)
                    val fromDate = now.minus(Duration.ofDays(2)).atZone(UTC).toLocalDate()
                    val toDate = now.plus(Duration.ofDays(3)).atZone(UTC).toLocalDate()
                    val rows = (Appointments innerJoin Clinics)
                        .selectAll()
                        .where {
                            (Appointments.status eq AppointmentState.CONFIRMED) and
                                (Appointments.id greater checkpoint.lastAppointmentId) and
                                Appointments.appointmentDate.between(fromDate, toDate)
                        }
                        .orderBy(Appointments.id to SortOrder.ASC)
                        .limit(appointmentLimit)
                        .toList()

                    if (rows.isEmpty()) {
                        completeCheckpoint(checkpoint.runId)
                        return@transaction result
                    }
                    val v2Schedules = loadCommitmentSchedules(rows)
                    val lastRowId = rows.last()[Appointments.id].value
                    val completesRun = rows.size < appointmentLimit
                    val generated = rows.flatMap { row ->
                        val rowId = row[Appointments.id].value
                        row.toCandidates(
                            now = now,
                            commitmentSchedule = v2Schedules[rowId],
                            runId = checkpoint.runId,
                            completesRun = completesRun && rowId == lastRowId,
                        )
                    }
                    result += generated.take(remaining)
                    pendingCandidates.addAll(generated.drop(remaining))
                    result
                }
            }
        }
    }

    override suspend fun enqueue(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult =
        withContext(ioDispatcher) {
            transaction(database) {
                lockCheckpoint(candidate.progress)
                val payload = requireNotNull(candidate.payload) { "recovery candidate payload is required" }
                val draft = payload.sendableDraft
                val result = if (draft == null) {
                    val existed = repository.containsIdempotency(payload.suppressionDraft.idempotencyDigest)
                    repository.suppressLegacy(payload.suppressionDraft)
                    if (existed) {
                        ReminderRecoveryMaterializationResult.ALREADY_EXISTS
                    } else {
                        ReminderRecoveryMaterializationResult.SUPPRESSED
                    }
                } else {
                    val existed = repository.containsIdempotency(draft.idempotencyDigest)
                    repository.enqueue(draft)
                    if (existed) {
                        ReminderRecoveryMaterializationResult.ALREADY_EXISTS
                    } else {
                        ReminderRecoveryMaterializationResult.ENQUEUED
                    }
                }
                advanceCheckpoint(candidate.progress)
                result
            }
        }

    override suspend fun suppressMissed(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult =
        withContext(ioDispatcher) {
            transaction(database) {
                lockCheckpoint(candidate.progress)
                val payload = requireNotNull(candidate.payload) { "recovery candidate payload is required" }
                val missed = payload.suppressionDraft.copy(
                    suppressionReason = NotificationSuppressionReasonCode.REMINDER_WINDOW_MISSED,
                )
                val existed = repository.containsIdempotency(missed.idempotencyDigest)
                repository.suppressLegacy(missed)
                val result = if (existed) {
                    ReminderRecoveryMaterializationResult.ALREADY_EXISTS
                } else {
                    ReminderRecoveryMaterializationResult.SUPPRESSED
                }
                advanceCheckpoint(candidate.progress)
                result
            }
        }

    override suspend fun scheduleFuture(candidate: ReminderRecoveryCandidate): ReminderRecoveryMaterializationResult =
        enqueue(candidate)

    private fun activeCheckpoint(): RecoveryCheckpoint {
        ReminderRecoveryCheckpoints.upsert(
            ReminderRecoveryCheckpoints.scope,
            onUpdate = { it[ReminderRecoveryCheckpoints.scope] = ReminderRecoveryCheckpoints.GLOBAL_SCOPE },
        ) {
            it[scope] = ReminderRecoveryCheckpoints.GLOBAL_SCOPE
            it[runId] = UUID.randomUUID().toString()
            it[lastAppointmentId] = 0L
            it[active] = true
        }
        var row = ReminderRecoveryCheckpoints
            .selectAll()
            .where { ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE }
            .forUpdate()
            .single()
        if (!row[ReminderRecoveryCheckpoints.active]) {
            val nextRunId = UUID.randomUUID().toString()
            ReminderRecoveryCheckpoints.update({
                ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE
            }) {
                it[runId] = nextRunId
                it[lastAppointmentId] = 0L
                it[active] = true
                it[updatedAt] = repository.currentDatabaseTime()
            }
            row = ReminderRecoveryCheckpoints
                .selectAll()
                .where { ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE }
                .single()
        }
        return RecoveryCheckpoint(
            runId = row[ReminderRecoveryCheckpoints.runId],
            lastAppointmentId = row[ReminderRecoveryCheckpoints.lastAppointmentId],
        )
    }

    private fun lockCheckpoint(progress: ReminderRecoveryProgress?) {
        if (progress == null || !progress.advancesCursor) return
        ReminderRecoveryCheckpoints
            .selectAll()
            .where { ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE }
            .forUpdate()
            .singleOrNull()
    }

    private fun advanceCheckpoint(progress: ReminderRecoveryProgress?) {
        if (progress == null) return
        ReminderRecoveryCheckpoints.update({
            (ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE) and
                (ReminderRecoveryCheckpoints.runId eq progress.runId) and
                (ReminderRecoveryCheckpoints.active eq true) and
                (ReminderRecoveryCheckpoints.lastAppointmentId less progress.appointmentId)
        }) {
            it[lastAppointmentId] = progress.appointmentId
            it[active] = !progress.completesRun
            it[updatedAt] = repository.currentDatabaseTime()
        }
    }

    private fun completeCheckpoint(runId: String) {
        ReminderRecoveryCheckpoints.update({
            (ReminderRecoveryCheckpoints.scope eq ReminderRecoveryCheckpoints.GLOBAL_SCOPE) and
                (ReminderRecoveryCheckpoints.runId eq runId)
        }) {
            it[active] = false
            it[updatedAt] = repository.currentDatabaseTime()
        }
    }

    private fun loadCommitmentSchedules(rows: List<ResultRow>): Map<Long, CommitmentSchedule> {
        val appointmentIds = rows
            .filter { it[Appointments.modelVersion] == AppointmentModelVersion.COMMITMENT_V2 }
            .map { it[Appointments.id] }
        if (appointmentIds.isEmpty()) return emptyMap()

        val commitments = AppointmentCommitments
            .selectAll()
            .where {
                (AppointmentCommitments.appointmentId inList appointmentIds) and
                    (AppointmentCommitments.status eq AppointmentCommitmentStatus.CONFIRMED)
            }
            .toList()
        val proposalIds = commitments.mapNotNull { it[AppointmentCommitments.confirmedProposalId] }
        if (proposalIds.isEmpty()) return emptyMap()
        val proposals = AppointmentProposals
            .selectAll()
            .where { AppointmentProposals.id inList proposalIds.map { EntityID(it, AppointmentProposals) } }
            .associateBy { it[AppointmentProposals.id].value }

        return commitments.mapNotNull { commitment ->
            val appointmentId = commitment[AppointmentCommitments.appointmentId].value
            val proposalId = commitment[AppointmentCommitments.confirmedProposalId] ?: return@mapNotNull null
            val proposal = proposals[proposalId] ?: return@mapNotNull null
            appointmentId to CommitmentSchedule(
                revision = proposal[AppointmentProposals.revision],
                startsAt = proposal[AppointmentProposals.proposedStartAt],
            )
        }.toMap()
    }

    private fun ResultRow.toCandidates(
        now: Instant,
        commitmentSchedule: CommitmentSchedule?,
        runId: String,
        completesRun: Boolean,
    ): List<ReminderRecoveryCandidate> {
        val appointmentId = this[Appointments.id].value
        val clinicId = this[Appointments.clinicId].value
        val tenantGroupId = this[Clinics.tenantGroupId].value
        val zoneId = ZoneId.of(this[Clinics.timezone])
        val schedule = when (this[Appointments.modelVersion]) {
            AppointmentModelVersion.LEGACY -> {
                val date = this[Appointments.appointmentDate] ?: return emptyList()
                val time = this[Appointments.startTime] ?: return emptyList()
                ReminderSchedule(
                    version = this[Appointments.version],
                    startsAt = date.atTime(time).atZone(zoneId).toInstant(),
                    appointmentDate = date,
                    startTime = time,
                )
            }
            AppointmentModelVersion.COMMITMENT_V2 -> {
                val current = commitmentSchedule ?: return emptyList()
                val local = current.startsAt.atZone(zoneId)
                ReminderSchedule(
                    version = current.revision,
                    startsAt = current.startsAt,
                    appointmentDate = local.toLocalDate(),
                    startTime = local.toLocalTime(),
                )
            }
        }
        val slots = buildList {
            if (dayBeforeEnabled) add(NotificationSlot.REMINDER_24H to schedule.startsAt.minus(Duration.ofHours(24)))
            if (sameDayEnabled) add(NotificationSlot.REMINDER_SAME_DAY to schedule.startsAt.minus(sameDayReminderLeadTime))
        }
        return slots.mapIndexed { index, (slot, dueAt) ->
            recoveryCandidate(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                appointmentId = appointmentId,
                memberId = this[Appointments.patientExternalId],
                clinicDisplayName = this[Clinics.name],
                schedule = schedule,
                slot = slot,
                dueAt = dueAt,
                now = now,
                progress = ReminderRecoveryProgress(
                    runId = runId,
                    appointmentId = appointmentId,
                    completesRun = completesRun && index == slots.lastIndex,
                    advancesCursor = index == slots.lastIndex,
                ),
            )
        }
    }

    private fun recoveryCandidate(
        tenantGroupId: Long,
        clinicId: Long,
        appointmentId: Long,
        memberId: String?,
        clinicDisplayName: String,
        schedule: ReminderSchedule,
        slot: NotificationSlot,
        dueAt: Instant,
        now: Instant,
        progress: ReminderRecoveryProgress?,
    ): ReminderRecoveryCandidate {
        val tenant = TenantGroupId(tenantGroupId)
        val clinic = ClinicId(clinicId)
        val appointment = AppointmentId(appointmentId)
        val input = NotificationIdempotencyInput(
            tenantGroupId = tenant,
            clinicId = clinic,
            appointmentId = appointment,
            appointmentVersionOrRevision = schedule.version,
            eventType = NotificationEventType.REMINDER,
            channel = NotificationChannelType.DUMMY,
            notificationSlot = slot,
        )
        val digest = hasher.idempotencyCandidates(input).first()
        val audit = hasher.auditFingerprint(
            NotificationAuditInput(
                tenantGroupId = tenant,
                stableSubject = appointmentId.toString(),
                purpose = NotificationEventType.REMINDER.name,
            )
        )
        val suppression = LegacySuppressionDraft(
            idempotencyDigest = digest,
            auditFingerprint = audit,
            tenantGroupId = tenant,
            clinicId = clinic,
            eventId = NotificationEventId(digest.value),
            suppressionReason = NotificationSuppressionReasonCode.MEMBER_ID_MISSING_LEGACY,
            availableAt = dueAt,
        )
        val sendable = memberId?.let {
            val parameters = AppointmentReminderParameters(
                clinicDisplayName = clinicDisplayName,
                appointmentDate = schedule.appointmentDate,
                startTime = schedule.startTime,
            )
            SendableNotificationDraft(
                envelope = NotificationOutboxEnvelope(
                    schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                    eventId = NotificationEventId(digest.value),
                    idempotencyKey = NotificationIdempotencyKey(digest.value),
                    tenantGroupId = tenant,
                    clinicId = clinic,
                    appointmentId = appointment,
                    memberId = MemberId(it),
                    channel = NotificationChannelType.DUMMY,
                    eventType = NotificationEventType.REMINDER,
                    notificationSlot = slot,
                    templateKey = templateKey(slot),
                    templateVersion = NotificationTemplateVersion(1),
                    parameterType = parameters.parameterType,
                    parameters = parameters,
                    occurredAt = now,
                    availableAt = dueAt,
                ),
                idempotencyDigest = digest,
                auditFingerprint = audit,
                providerKey = "dummy",
            )
        }
        return ReminderRecoveryCandidate(
            tenantGroupId = tenant,
            clinicId = clinic,
            appointmentId = appointment,
            slot = slot,
            idempotencyKey = NotificationIdempotencyKey(digest.value),
            dueAt = dueAt,
            payload = ReminderRecoveryPayload(sendable, suppression),
            progress = progress,
        )
    }

    private fun templateKey(slot: NotificationSlot): NotificationTemplateKey =
        when (slot) {
            NotificationSlot.REMINDER_24H -> NotificationTemplateKey("appointment-reminder-24h")
            NotificationSlot.REMINDER_SAME_DAY -> NotificationTemplateKey("appointment-reminder-same-day")
            else -> error("unsupported reminder slot: $slot")
        }

    private data class CommitmentSchedule(
        val revision: Long,
        val startsAt: Instant,
    )

    private data class ReminderSchedule(
        val version: Long,
        val startsAt: Instant,
        val appointmentDate: java.time.LocalDate,
        val startTime: java.time.LocalTime,
    )

    private data class RecoveryCheckpoint(
        val runId: String,
        val lastAppointmentId: Long,
    )

    companion object {
        private val UTC: ZoneId = ZoneId.of("UTC")
    }
}
