package io.bluetape4k.clinic.appointment.event

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
            payloadJson = """{"appointmentId":${event.appointmentId},"tenantGroupId":${event.tenantGroupId},"clinicId":${event.clinicId}}"""
        )
    }

    @EventListener
    fun onStatusChanged(event: AppointmentDomainEvent.StatusChanged) {
        val reasonPart = event.reason?.let { ""","reason":${jsonString(it)}""" } ?: ""
        saveEventLog(
            eventType = "StatusChanged",
            entityId = event.appointmentId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"appointmentId":${event.appointmentId},"tenantGroupId":${event.tenantGroupId},"clinicId":${event.clinicId},"fromState":"${event.fromState}","toState":"${event.toState}"$reasonPart}"""
        )
    }

    @EventListener
    fun onCancelled(event: AppointmentDomainEvent.Cancelled) {
        saveEventLog(
            eventType = "Cancelled",
            entityId = event.appointmentId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"appointmentId":${event.appointmentId},"tenantGroupId":${event.tenantGroupId},"clinicId":${event.clinicId},"reason":${jsonString(event.reason)}}"""
        )
    }

    @EventListener
    fun onRescheduled(event: AppointmentDomainEvent.Rescheduled) {
        saveEventLog(
            eventType = "Rescheduled",
            entityId = event.originalId,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
            payloadJson = """{"originalId":${event.originalId},"newId":${event.newId},"tenantGroupId":${event.tenantGroupId},"clinicId":${event.clinicId}}"""
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
            // event log is best-effort audit only; it must not change the committed API result.
            // Keep the diagnostic bounded: raw SQL/driver messages can contain tenant data.
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
}
