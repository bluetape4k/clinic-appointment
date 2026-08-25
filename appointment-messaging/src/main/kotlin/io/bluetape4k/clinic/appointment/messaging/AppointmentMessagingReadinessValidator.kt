package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** V22~V25 schema와 serializer 계약을 relay claim 전에 한 번 검증하는 fail-closed gate다. */
class AppointmentMessagingReadinessValidator(
    private val codec: AppointmentEventEnvelopeCodec,
    private val dataSource: DataSource? = null,
    private val requireConsumerSchema: Boolean = false,
    private val schemaRegistry: AppointmentSchemaRegistry = StaticAppointmentSchemaRegistry(),
) {
    private val checked = AtomicBoolean(false)

    /** startup 이후 첫 relay tick에서 bounded self-check를 실행한다. */
    fun validate(probe: AppointmentMessagingReadinessProbe) {
        if (checked.get()) return

        val serializerDiagnostic = serializerDiagnostic()
        val schemaDiagnostic = schemaDiagnostic()
        val registryDiagnostic = registryDiagnostic()
        val diagnostics = listOfNotNull(serializerDiagnostic, schemaDiagnostic, registryDiagnostic)
        probe.replaceDiagnostics(diagnostics)

        if (serializerDiagnostic == null) {
            probe.markSerializerAvailable()
        } else {
            probe.markSerializerInvalid()
        }

        if (schemaDiagnostic == null) {
            probe.markSchemaAvailable()
        } else {
            probe.markSchemaInvalid()
        }
        if (registryDiagnostic == null) {
            probe.markRegistryAvailable()
        } else {
            probe.markRegistryInvalid()
        }
        if (diagnostics.isEmpty()) checked.set(true)
    }

    private fun serializerDiagnostic(): AppointmentReadinessDiagnostic? = try {
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
        if (decoded.eventType == AppointmentEventType.CREATED &&
            decoded.aggregateId == AppointmentAggregateId(1) &&
            decoded.schemaVersion == AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION
        ) {
            null
        } else {
            diagnostic(
                operation = OPERATION_SERIALIZER,
                target = TARGET_ENVELOPE,
                code = CODE_SERIALIZER_CONTRACT,
            )
        }
    } catch (failure: Exception) {
        diagnostic(
            operation = OPERATION_SERIALIZER,
            target = TARGET_ENVELOPE,
            code = CODE_SERIALIZER_FAILURE,
            errorClass = safeErrorClass(failure),
        )
    }

    private fun schemaDiagnostic(): AppointmentReadinessDiagnostic? {
        val source = dataSource ?: return diagnostic(
            operation = OPERATION_SCHEMA_CONNECTION,
            target = TARGET_DATABASE,
            code = CODE_DATASOURCE_MISSING,
        )

        return try {
            source.connection.use { connection ->
                val metadata = connection.metaData
                // JDBC driver에 따라 schema 또는 catalog 중 하나만 노출될 수 있습니다.
                // 두 값이 모두 비어 있을 때만 namespace 계약을 충족하지 않은 것으로 판단합니다.
                val schema = connection.schema.takeIf { it.isNotBlank() }
                val catalog = connection.catalog.takeIf { it.isNotBlank() }
                if (schema == null && catalog == null) {
                    return@use diagnostic(
                        operation = OPERATION_SCHEMA_CONNECTION,
                        target = TARGET_JDBC_NAMESPACE,
                        code = CODE_NAMESPACE_MISSING,
                    )
                }

                REQUIRED_CONTRACTS
                    .filter { requireConsumerSchema || it.table == OUTBOX_TABLE }
                    .forEach { contract ->
                        val columns = readColumnNames(metadata, connection.catalog, schema, contract.table)
                        if (columns.isEmpty()) {
                            return@use diagnostic(
                                operation = OPERATION_SCHEMA_COLUMNS,
                                target = "${contract.table}.columns",
                                code = CODE_TABLE_MISSING,
                            )
                        }
                        if (!contract.columns.all(columns::contains)) {
                            return@use diagnostic(
                                operation = OPERATION_SCHEMA_COLUMNS,
                                target = "${contract.table}.columns",
                                code = CODE_COLUMNS_MISSING,
                            )
                        }

                        val indexes = readIndexNames(metadata, connection.catalog, schema, contract.table)
                        if (!contract.indexes.all(indexes::contains)) {
                            return@use diagnostic(
                                operation = OPERATION_SCHEMA_INDEXES,
                                target = "${contract.table}.indexes",
                                code = CODE_INDEXES_MISSING,
                            )
                        }
                    }
                null
            }
        } catch (failure: MetadataFailure) {
            diagnosticForFailure(failure.operation, failure.target, failure.failure)
        } catch (failure: Exception) {
            diagnosticForFailure(OPERATION_SCHEMA_CONNECTION, TARGET_DATABASE, failure)
        }
    }

    private fun registryDiagnostic(): AppointmentReadinessDiagnostic? {
        if (!requireConsumerSchema) return null

        return try {
            val readiness = schemaRegistry.readiness()
            when {
                readiness.ready -> null
                !readiness.localSchemaValid -> diagnostic(
                    operation = OPERATION_SCHEMA_REGISTRY,
                    target = readiness.subject,
                    code = CODE_REGISTRY_LOCAL_SCHEMA,
                )
                !readiness.registryReachable -> diagnostic(
                    operation = OPERATION_SCHEMA_REGISTRY,
                    target = readiness.subject,
                    code = CODE_REGISTRY_UNAVAILABLE,
                    retryable = true,
                )
                else -> diagnostic(
                    operation = OPERATION_SCHEMA_REGISTRY,
                    target = readiness.subject,
                    code = CODE_REGISTRY_COMPATIBILITY,
                )
            }
        } catch (failure: Exception) {
            diagnostic(
                operation = OPERATION_SCHEMA_REGISTRY,
                target = schemaRegistry.subject,
                code = CODE_REGISTRY_UNAVAILABLE,
                errorClass = safeErrorClass(failure),
                retryable = true,
            )
        }
    }

    private fun readColumnNames(
        metadata: java.sql.DatabaseMetaData,
        catalog: String?,
        schema: String?,
        table: String,
    ): Set<String> = readMetadata(
        operation = OPERATION_SCHEMA_COLUMNS,
        target = "$table.columns",
        catalog = catalog,
        table = table,
    ) { candidateCatalog, tablePattern ->
        metadata.getColumns(candidateCatalog, schema, tablePattern, null).use { result ->
            buildSet {
                while (result.next()) add(result.getString("COLUMN_NAME").lowercase())
            }
        }
    }

    private fun readIndexNames(
        metadata: java.sql.DatabaseMetaData,
        catalog: String?,
        schema: String?,
        table: String,
    ): Set<String> = readMetadata(
        operation = OPERATION_SCHEMA_INDEXES,
        target = "$table.indexes",
        catalog = catalog,
        table = table,
    ) { candidateCatalog, tablePattern ->
        metadata.getIndexInfo(candidateCatalog, schema, tablePattern, false, false).use { result ->
            buildSet {
                while (result.next()) {
                    result.getString("INDEX_NAME")?.let { add(it.lowercase()) }
                }
            }
        }
    }

    private fun readMetadata(
        operation: String,
        target: String,
        catalog: String?,
        table: String,
        reader: (catalog: String?, tablePattern: String) -> Set<String>,
    ): Set<String> {
        var lastFailure: Exception? = null
        var successfulRead = false
        for (candidateCatalog in sequenceOf(catalog, null).distinct()) {
            for (tablePattern in identifierCandidates(table)) {
                try {
                    val values = reader(candidateCatalog, tablePattern)
                    successfulRead = true
                    if (values.isNotEmpty()) return values
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
        }
        if (!successfulRead && lastFailure != null) {
            throw MetadataFailure(operation, target, lastFailure)
        }
        return emptySet()
    }

    private fun diagnosticForFailure(
        operation: String,
        target: String,
        failure: Exception,
    ): AppointmentReadinessDiagnostic {
        val root = rootCause(failure)
        val sqlState = generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .mapNotNull { it.sqlState }
            .firstOrNull()
        val code = when {
            root is SQLTimeoutException || sqlState == "HYT00" -> CODE_METADATA_TIMEOUT
            root is SQLInvalidAuthorizationSpecException || sqlState?.startsWith("28") == true ->
                CODE_PERMISSION_DENIED
            root is SQLNonTransientConnectionException || sqlState?.startsWith("08") == true ->
                CODE_METADATA_DRIVER
            else -> CODE_METADATA_UNAVAILABLE
        }
        return diagnostic(
            operation = operation,
            target = target,
            code = code,
            errorClass = safeErrorClass(root),
            retryable = code == CODE_METADATA_TIMEOUT || code == CODE_METADATA_DRIVER,
        )
    }

    private fun diagnostic(
        operation: String,
        target: String,
        code: String,
        errorClass: String? = null,
        retryable: Boolean = false,
    ): AppointmentReadinessDiagnostic = AppointmentReadinessDiagnostic(
        operation = operation,
        target = target,
        code = code,
        errorClass = errorClass,
        retryable = retryable,
    )

    private fun safeErrorClass(failure: Throwable): String =
        (failure.javaClass.simpleName.ifBlank { failure.javaClass.name.substringAfterLast('.') })
            .take(128)

    private fun rootCause(failure: Throwable): Throwable {
        var current = failure
        while (true) {
            val next = current.cause ?: break
            if (next === current) break
            current = next
        }
        return current
    }

    private class MetadataFailure(
        val operation: String,
        val target: String,
        val failure: Exception,
    ) : Exception(failure)

    private fun identifierCandidates(identifier: String): Sequence<String> =
        sequenceOf(identifier, identifier.uppercase(), identifier.lowercase()).distinct()

    companion object {
        private const val OPERATION_SERIALIZER = "serializer.self-check"
        private const val OPERATION_SCHEMA_CONNECTION = "schema.connection"
        private const val OPERATION_SCHEMA_COLUMNS = "schema.columns"
        private const val OPERATION_SCHEMA_INDEXES = "schema.indexes"
        private const val OPERATION_SCHEMA_REGISTRY = "schema.registry"
        private const val TARGET_ENVELOPE = "appointment-event-envelope"
        private const val TARGET_DATABASE = "database"
        private const val TARGET_JDBC_NAMESPACE = "jdbc.namespace"
        private const val CODE_DATASOURCE_MISSING = "SCHEMA_DATASOURCE_MISSING"
        private const val CODE_NAMESPACE_MISSING = "SCHEMA_NAMESPACE_MISSING"
        private const val CODE_TABLE_MISSING = "SCHEMA_TABLE_MISSING"
        private const val CODE_COLUMNS_MISSING = "SCHEMA_COLUMNS_MISSING"
        private const val CODE_INDEXES_MISSING = "SCHEMA_INDEXES_MISSING"
        private const val CODE_METADATA_TIMEOUT = "SCHEMA_METADATA_TIMEOUT"
        private const val CODE_PERMISSION_DENIED = "SCHEMA_PERMISSION_DENIED"
        private const val CODE_METADATA_DRIVER = "SCHEMA_METADATA_DRIVER_ERROR"
        private const val CODE_METADATA_UNAVAILABLE = "SCHEMA_METADATA_UNAVAILABLE"
        private const val CODE_SERIALIZER_CONTRACT = "SERIALIZER_CONTRACT_INVALID"
        private const val CODE_SERIALIZER_FAILURE = "SERIALIZER_SELF_CHECK_FAILED"
        private const val CODE_REGISTRY_LOCAL_SCHEMA = "SCHEMA_REGISTRY_LOCAL_INVALID"
        private const val CODE_REGISTRY_UNAVAILABLE = "SCHEMA_REGISTRY_UNAVAILABLE"
        private const val CODE_REGISTRY_COMPATIBILITY = "SCHEMA_REGISTRY_COMPATIBILITY_INVALID"
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
                columns = setOf(
                    "request_id",
                    "request_hash",
                    "hash_version",
                    "partition_number",
                    "status",
                    "completed_at",
                ),
                indexes = setOf("idx_appointment_consumer_replay_audit_scope_created"),
            ),
            SchemaContract(
                table = "scheduling_appointment_stats_projection",
                columns = setOf(
                    "tenant_group_id",
                    "clinic_id",
                    "event_date",
                    "status",
                    "appointment_count",
                    "last_event_version",
                    "last_event_id",
                ),
                indexes = setOf(
                    "idx_appointment_stats_projection_scope_date",
                    "idx_appointment_stats_projection_scope_status_date",
                ),
            ),
            SchemaContract(
                table = "scheduling_appointment_stats_projection_events",
                columns = setOf("tenant_group_id", "clinic_id", "aggregate_id", "event_id", "event_version"),
                indexes = setOf("idx_appointment_stats_projection_events_scope_date"),
            ),
            SchemaContract(
                table = "scheduling_appointment_stats_projection_aggregate_locks",
                columns = setOf("tenant_group_id", "clinic_id", "aggregate_id"),
                indexes = emptySet(),
            ),
        )
    }
}
