package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AppointmentEventLogger(
    private val metrics: AppointmentEventAuditMetrics? = null,
) {
    companion object : KLogging()

    @EventListener
    fun onCreated(event: AppointmentDomainEvent.Created) {
        saveEventLog(
            eventType = "Created",
            entityId = event.appointmentId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"eventType":"Created"}""",
        )
    }

    @EventListener
    fun onStatusChanged(event: AppointmentDomainEvent.StatusChanged) {
        val reasonPart = event.reason
            ?.toRegisteredReasonCode()
            ?.let { ""","reasonCode":${jsonString(it)}""" }
            ?: ""
        saveEventLog(
            eventType = "StatusChanged",
            entityId = event.appointmentId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"eventType":"StatusChanged","fromState":"${event.fromState}","toState":"${event.toState}"$reasonPart}""",
        )
    }

    @EventListener
    fun onCancelled(event: AppointmentDomainEvent.Cancelled) {
        val reasonPart = event.reason
            .toRegisteredReasonCode()
            ?.let { ""","reasonCode":${jsonString(it)}""" }
            ?: ""
        saveEventLog(
            eventType = "Cancelled",
            entityId = event.appointmentId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"eventType":"Cancelled"$reasonPart}""",
        )
    }

    @EventListener
    fun onRescheduled(event: AppointmentDomainEvent.Rescheduled) {
        saveEventLog(
            eventType = "Rescheduled",
            entityId = event.originalId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"eventType":"Rescheduled"}""",
        )
    }

    private fun saveEventLog(
        eventType: String,
        entityId: Long,
        tenantGroupId: Long,
        clinicId: Long,
        payloadJson: String,
    ) {
        try {
            transaction {
                AppointmentEventLogs.insert {
                    it[AppointmentEventLogs.eventType] = eventType
                    it[AppointmentEventLogs.entityType] = "Appointment"
                    it[AppointmentEventLogs.entityId] = entityId
                    it[AppointmentEventLogs.tenantGroupId] = tenantGroupId
                    it[AppointmentEventLogs.clinicId] = clinicId
                    it[AppointmentEventLogs.payloadJson] = payloadJson
                }
            }
        } catch (_: Exception) {
            // event log는 best-effort audit일 뿐이며 이미 commit된 API 결과를 바꾸면 안 됩니다.
            // raw SQL/driver 메시지에 tenant 데이터가 포함될 수 있으므로 진단 정보의 크기를 제한합니다.
            metrics?.recordEventLogWriteFailure("EVENT_LOG_WRITE_FAILED")
            log.warn {
                "예약 이벤트 감사 로그 저장에 실패했습니다: reason=EVENT_LOG_WRITE_FAILED, eventType=$eventType"
            }
        }
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun String.toRegisteredReasonCode(): String? =
        runCatching { CancellationReasonCode(this).value }.getOrNull()
}
