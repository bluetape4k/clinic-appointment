package io.bluetape4k.clinic.appointment.messaging

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** consumer dedup과 processing lifecycle을 보존하는 metadata-only inbox입니다. */
object AppointmentConsumerInboxTable : org.jetbrains.exposed.v1.core.Table("scheduling_appointment_consumer_inbox") {
    val logicalConsumerId = varchar("logical_consumer_id", 128)
    val logicalStreamId = varchar("logical_stream_id", 128)
    val eventId = varchar("event_id", 128)
    val topic = varchar("topic", 249)
    val partition = integer("partition_number")
    val offset = long("offset_value")
    val schemaVersion = integer("schema_version")
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val payloadSha256 = varchar("payload_sha256", 64)
    val status = enumerationByName("status", 32, AppointmentConsumerStatus::class)
    val attemptCount = integer("attempt_count")
    val failureCode = varchar("failure_code", 64).nullable()
    val receivedAt = timestamp("received_at").defaultExpression(CurrentTimestamp)
    val processedAt = timestamp("processed_at").nullable()

    override val primaryKey = PrimaryKey(
        logicalConsumerId,
        logicalStreamId,
        eventId,
        name = "pk_appointment_consumer_inbox",
    )

    init {
        index(
            "idx_appointment_consumer_inbox_status_received",
            false,
            logicalConsumerId,
            status,
            receivedAt,
        )
        index(
            "idx_appointment_consumer_inbox_scope",
            false,
            logicalConsumerId,
            tenantGroupId,
            clinicId,
            receivedAt,
        )
    }
}

/** retry/quarantine 운영 조회용 metadata입니다. raw Kafka value는 저장하지 않습니다. */
object AppointmentConsumerQuarantineTable : LongIdTable("scheduling_appointment_consumer_quarantine") {
    val logicalConsumerId = varchar("logical_consumer_id", 128)
    val logicalStreamId = varchar("logical_stream_id", 128)
    val eventId = varchar("event_id", 128)
    val failureCode = varchar("failure_code", 64)
    val topic = varchar("topic", 249)
    val partition = integer("partition_number")
    val offset = long("offset_value")
    val schemaVersion = integer("schema_version")
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val payloadSha256 = varchar("payload_sha256", 64)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_appointment_consumer_quarantine_event",
            logicalConsumerId,
            logicalStreamId,
            eventId,
        )
        index("idx_appointment_consumer_quarantine_created", false, createdAt)
    }
}
