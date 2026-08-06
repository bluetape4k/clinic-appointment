package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** V22 schema와 serializer 계약을 relay claim 전에 한 번 검증하는 fail-closed gate다. */
class AppointmentMessagingReadinessValidator(
    private val codec: AppointmentEventEnvelopeCodec,
    private val dataSource: DataSource? = null,
    private val requireConsumerSchema: Boolean = false,
) {
    private val checked = AtomicBoolean(false)

    /** startup 이후 첫 relay tick에서 bounded self-check를 실행한다. */
    fun validate(probe: AppointmentMessagingReadinessProbe) {
        if (checked.get()) return

        val serializerValid = serializerSelfCheck()
        val schemaValid = dataSource != null && schemaContractExists()
        if (serializerValid) {
            probe.markSerializerAvailable()
        } else {
            probe.markSerializerInvalid()
        }

        if (schemaValid) {
            probe.markSchemaAvailable()
        } else {
            probe.markSchemaInvalid()
        }
        if (serializerValid && schemaValid) checked.set(true)
    }

    private fun serializerSelfCheck(): Boolean = runCatching {
        val context = AppointmentCommandContext.root("appointment-messaging-readiness")
        val envelope = AppointmentEventEnvelope(
            eventId = AppointmentEventId("readiness-self-check"),
            eventType = AppointmentEventType.CREATED,
            schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
            occurredAt = java.time.Instant.EPOCH,
            tenantGroupId = 1,
            clinicId = 1,
            aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
            aggregateId = AppointmentAggregateId(1),
            correlationId = context.correlationId,
            causationId = context.causationId,
            payload = AppointmentCreatedPayload(
                appointmentId = AppointmentAggregateId(1),
                version = 1,
                status = AppointmentState.CONFIRMED,
            ),
        )
        val decoded = codec.decode(codec.encode(envelope))
        decoded.eventType == AppointmentEventType.CREATED &&
            decoded.aggregateId == AppointmentAggregateId(1) &&
            decoded.schemaVersion == AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION
    }.getOrDefault(false)

    private fun schemaContractExists(): Boolean = runCatching {
        dataSource?.connection?.use { connection ->
            val metadata = connection.metaData
            val schema = connection.schema.takeIf { it.isNotBlank() } ?: return@use false
            REQUIRED_CONTRACTS
                .filter { requireConsumerSchema || it.table == OUTBOX_TABLE }
                .all { contract ->
                    val columns = readColumnNames(metadata, connection.catalog, schema, contract.table)
                    val indexes = readIndexNames(metadata, connection.catalog, schema, contract.table)
                    contract.columns.all(columns::contains) && contract.indexes.all(indexes::contains)
                }
        } ?: false
    }.getOrDefault(false)

    private fun readColumnNames(
        metadata: java.sql.DatabaseMetaData,
        catalog: String?,
        schema: String,
        table: String,
    ): Set<String> = sequenceOf(catalog, null).distinct()
        .mapNotNull { candidateCatalog ->
            runCatching {
                metadata.getColumns(candidateCatalog, schema, table.uppercase(), null).use { result ->
                    buildSet {
                        while (result.next()) add(result.getString("COLUMN_NAME").lowercase())
                    }
                }
            }.getOrNull()
        }
        .firstOrNull { it.isNotEmpty() }
        ?: emptySet()

    private fun readIndexNames(
        metadata: java.sql.DatabaseMetaData,
        catalog: String?,
        schema: String,
        table: String,
    ): Set<String> = sequenceOf(catalog, null).distinct()
        .flatMap { candidateCatalog ->
            sequenceOf(table, table.uppercase(), table.lowercase()).asSequence()
            .mapNotNull { tablePattern ->
                runCatching {
                    metadata.getIndexInfo(candidateCatalog, schema, tablePattern.uppercase(), false, false).use { result ->
                        buildSet {
                            while (result.next()) {
                                result.getString("INDEX_NAME")?.let { add(it.lowercase()) }
                            }
                        }
                    }
                }.getOrNull()
            }
        }
        .firstOrNull { it.isNotEmpty() }
        ?: emptySet()

    companion object {
        private const val OUTBOX_TABLE = "scheduling_outbox_events"
        private data class SchemaContract(
            val table: String,
            val columns: Set<String>,
            val indexes: Set<String>,
        )

        private val REQUIRED_CONTRACTS = listOf(
            SchemaContract(
                table = OUTBOX_TABLE,
                columns = setOf(
                    "occurred_at",
                    "topic",
                    "partition_key",
                    "lease_owner",
                    "lease_token",
                    "lease_until",
                    "last_failure_code",
                    "last_failure_at",
                ),
                indexes = setOf("idx_outbox_appointment_ready", "idx_outbox_appointment_lease_recovery"),
            ),
            SchemaContract(
                table = "scheduling_appointment_consumer_inbox",
                columns = setOf(
                    "logical_consumer_id",
                    "logical_stream_id",
                    "event_id",
                    "status",
                    "processed_at",
                    "processing_lease_until",
                ),
                indexes = setOf("idx_appointment_consumer_inbox_status_processed"),
            ),
            SchemaContract(
                table = "scheduling_appointment_consumer_rejected",
                columns = setOf("logical_consumer_id", "topic", "partition_number", "offset_value", "payload_sha256"),
                indexes = setOf("idx_appointment_consumer_rejected_created"),
            ),
            SchemaContract(
                table = "scheduling_appointment_consumer_quarantine",
                columns = setOf("logical_consumer_id", "event_id", "failure_code", "payload_sha256"),
                indexes = setOf("idx_appointment_consumer_quarantine_created"),
            ),
            SchemaContract(
                table = "scheduling_appointment_consumer_replay_audit",
                columns = setOf("request_id", "request_hash", "status", "completed_at"),
                indexes = setOf("idx_appointment_consumer_replay_audit_scope_created"),
            ),
            SchemaContract(
                table = "scheduling_appointment_stats_projection_events",
                columns = setOf("tenant_group_id", "clinic_id", "aggregate_id", "event_id", "event_version"),
                indexes = setOf("idx_appointment_stats_projection_events_scope_date"),
            ),
        )
    }
}
