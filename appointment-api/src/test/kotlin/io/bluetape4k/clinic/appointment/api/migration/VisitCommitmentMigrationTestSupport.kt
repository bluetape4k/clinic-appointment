package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.tables.AppointmentAuditEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.TreatmentSpaces
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Date
import java.sql.Time
import javax.sql.DataSource

/**
 * Flyway V10·V11·V12의 방문 commitment 스키마를 세 dialect에서 동일하게 검증하는 지원 객체입니다.
 *
 * V9까지 기존 예약을 먼저 저장한 다음 V10·V11·V12를 순서대로 적용한다. 이 순서로 기존
 * row의 `LEGACY` backfill, 확정 projection 완화, quarantine 해결 시각 추가가 데이터
 * 손실 없이 수행되는지 검증하며 metadata 조회는 어떤 DDL도 실행하지 않는다.
 */
internal object VisitCommitmentMigrationTestSupport {

    /**
     * [dataSource]를 clean한 뒤 V1→V9와 V10·V11·V12를 분리 적용하고 신규 계약을 검증합니다.
     */
    fun verifyVisitCommitmentMigrations(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("9")
            .load()
            .migrate()

        dataSource.connection.use(::seedLegacyAppointment)

        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 3

        dataSource.connection.use { connection ->
            val tables = tableNames(connection)
            check(EXPECTED_TABLES.all(tables::contains)) {
                "Missing V10 tables: ${EXPECTED_TABLES - tables}"
            }
            verifyAppointmentExpansion(connection)
            verifyQuarantineResolutionExpansion(connection)
            verifyIndexOrder(connection)
            verifyRetentionIndexes(connection)
            verifyForeignKeys(connection)
            verifyUniqueConstraints(connection)
            verifyIncompleteCommitmentProjection(connection)
        }

        val appliedV10 = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .info()
            .applied()
            .single { it.version?.version == "10" }
        check(appliedV10.checksum != null) { "Applied V10 checksum must be recorded" }
        val appliedV11 = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .info()
            .applied()
            .single { it.version?.version == "11" }
        check(appliedV11.checksum != null) { "Applied V11 checksum must be recorded" }
        val appliedV12 = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .load()
            .info()
            .applied()
            .single { it.version?.version == "12" }
        check(appliedV12.checksum != null) { "Applied V12 checksum must be recorded" }
        verifyExposedModelHasNoAdditiveDrift(dataSource)
        verifyCleanMigration(dataSource, location)
    }

    /**
     * 운영 신규 설치와 같은 빈 database에서 V1부터 V12까지 한 번에 적용되는지 검증합니다.
     *
     * 앞선 검사는 기존 V9 데이터의 보존을 책임지고, 이 검사는 신규 tenant 환경의 bootstrap을
     * 책임진다. 두 경로를 분리해 검증해야 upgrade 성공이 clean install 성공으로 오인되지 않는다.
     */
    private fun verifyCleanMigration(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()

        val result = flyway.migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 12
        dataSource.connection.use { connection ->
            val tables = tableNames(connection)
            check(EXPECTED_TABLES.all(tables::contains)) {
                "Clean V1→V12 migration is missing tables: ${EXPECTED_TABLES - tables}"
            }
            verifyQuarantineResolutionExpansion(connection)
            verifyRetentionIndexes(connection)
        }
    }

    /**
     * tenant·clinic별 보존 삭제가 오래된 row를 정렬할 때 full scan을 요구하지 않도록
     * V12의 predicate/order 복합 index 열 순서를 검증합니다.
     */
    private fun verifyRetentionIndexes(connection: Connection) {
        indexDefinition(
            connection,
            "scheduling_appointment_command_idempotencies",
            "idx_appointment_idempotency_retention",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "created_at:A",
            "id:A",
        )
        indexDefinition(
            connection,
            "scheduling_inbox_events",
            "idx_inbox_retention",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "status:A",
            "received_at:A",
            "id:A",
        )
        indexDefinition(
            connection,
            "scheduling_outbox_events",
            "idx_outbox_retention",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "status:A",
            "published_at:A",
            "id:A",
        )
    }

    /**
     * V11이 해결 시각을 nullable로 추가하고 tenant·clinic별 retention index를 구성했는지 검증합니다.
     *
     * 기존 quarantine은 해결 시점을 추정해 backfill하지 않는다. `null`은 안전하게 보존하는
     * 의미이며 이후 release 승인·거절 전이만 권위 있는 `resolved_at`을 기록합니다.
     */
    private fun verifyQuarantineResolutionExpansion(connection: Connection) {
        val quarantineColumns = columns(connection, "scheduling_quarantine_events")
        quarantineColumns.getValue("resolved_at").nullable shouldBeEqualTo true
        indexDefinition(
            connection,
            "scheduling_quarantine_events",
            "idx_quarantine_resolved_retention",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "legal_hold:A",
            "status:A",
            "resolved_at:A",
            "id:A",
        )
    }

    /**
     * V10 이전의 non-null projection을 가진 예약 한 건을 고정 ID로 저장합니다.
     */
    private fun seedLegacyAppointment(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO scheduling_clinics(id, tenant_group_id, name) " +
                    "VALUES (91001, 1, 'Migration Clinic')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_doctors(id, clinic_id, name) " +
                    "VALUES (91002, 91001, 'Migration Doctor')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_treatment_types(" +
                    "id, clinic_id, name, default_duration_minutes" +
                    ") VALUES (91003, 91001, 'Migration Treatment', 30)",
            )
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments(
                id, clinic_id, doctor_id, treatment_type_id, patient_name,
                appointment_date, start_time, end_time, status
            ) VALUES (91004, 91001, 91002, 91003, ?, ?, ?, ?, 'CONFIRMED')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, "Legacy Patient")
            statement.setDate(2, Date.valueOf("2026-08-10"))
            statement.setTime(3, Time.valueOf("10:00:00"))
            statement.setTime(4, Time.valueOf("10:30:00"))
            statement.executeUpdate()
        }
    }

    /**
     * 기존 row의 backfill과 v2 확정 projection nullable 계약을 metadata와 DML로 검증합니다.
     */
    private fun verifyAppointmentExpansion(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT model_version, doctor_id, treatment_type_id,
                   appointment_date, start_time, end_time
              FROM scheduling_appointments
             WHERE id = 91004
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                check(result.next()) { "Legacy appointment must survive V10" }
                result.getString("model_version") shouldBeEqualTo "LEGACY"
                result.getLong("doctor_id") shouldBeEqualTo 91002L
                result.getLong("treatment_type_id") shouldBeEqualTo 91003L
                result.getDate("appointment_date") shouldBeEqualTo Date.valueOf("2026-08-10")
            }
        }

        val appointmentColumns = columns(connection, "scheduling_appointments")
        appointmentColumns.getValue("model_version").nullable shouldBeEqualTo false
        appointmentColumns.getValue("doctor_id").nullable shouldBeEqualTo true
        appointmentColumns.getValue("treatment_type_id").nullable shouldBeEqualTo true
        appointmentColumns.getValue("appointment_date").nullable shouldBeEqualTo true
        appointmentColumns.getValue("start_time").nullable shouldBeEqualTo true
        appointmentColumns.getValue("end_time").nullable shouldBeEqualTo true
    }

    /**
     * 실제 일정이 없는 commitment v2 identity를 데이터베이스가 허용하는지 검증합니다.
     */
    private fun verifyIncompleteCommitmentProjection(connection: Connection) {
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments(
                id, clinic_id, model_version, patient_name, status
            ) VALUES (91005, 91001, 'COMMITMENT_V2', 'Pending Patient', 'REQUESTED')
            """.trimIndent(),
        ).use { statement ->
            statement.executeUpdate() shouldBeEqualTo 1
        }
    }

    /**
     * 충돌·현재 proposal·감사 조회의 복합 index 열 순서와 DESC 방향을 검증합니다.
     */
    private fun verifyIndexOrder(connection: Connection) {
        indexDefinition(
            connection,
            "scheduling_resource_allocations",
            "idx_resource_allocation_overlap",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "resource_type:A",
            "resource_id:A",
            "allocation_status:A",
            "starts_at:A",
            "ends_at:A",
        )
        indexDefinition(
            connection,
            "scheduling_appointment_proposals",
            "idx_proposal_current",
        ) shouldBeEqualTo listOf("commitment_id:A", "revision:D")
        indexDefinition(
            connection,
            "scheduling_appointment_audit_events",
            "idx_appointment_audit_aggregate",
        ) shouldBeEqualTo listOf(
            "tenant_group_id:A",
            "clinic_id:A",
            "aggregate_id:A",
            "occurred_at:D",
        )
    }

    /**
     * 주요 child가 올바른 aggregate와 tenant/clinic authority를 참조하는지 검증합니다.
     */
    private fun verifyForeignKeys(connection: Connection) {
        val foreignKeys = EXPECTED_TABLES.flatMap { table ->
            importedKeys(connection, table).map { "$table.$it" }
        }.toSet()
        check(EXPECTED_FOREIGN_KEYS.all(foreignKeys::contains)) {
            "Missing V10 foreign keys: ${EXPECTED_FOREIGN_KEYS - foreignKeys}"
        }
    }

    /**
     * 중복 proposal revision, 공간 code, command key가 DB에서도 차단되는지 검증합니다.
     */
    private fun verifyUniqueConstraints(connection: Connection) {
        EXPECTED_UNIQUE_IDENTITIES.forEach { (table, expectedColumns) ->
            val identities = uniqueIndexDefinitions(connection, table)
            check(expectedColumns in identities) {
                "Missing V10 unique identity $table$expectedColumns; actual=$identities"
            }
        }
    }

    /**
     * Flyway 적용 후 Exposed 모델이 추가 table, column, index를 요구하지 않는지 읽기 전용으로 검증합니다.
     *
     * [MigrationUtils] 결과는 실행하지 않는다. nullable 변경이나 dialect별 type 표현처럼 destructive
     * 또는 재작성 DDL은 이 검사 대상이 아니며, 운영 DDL 권위는 계속 Flyway가 소유한다.
     */
    private fun verifyExposedModelHasNoAdditiveDrift(dataSource: DataSource) {
        val database = Database.connect(dataSource)
        val additiveDrift = transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                *EXPOSED_VISIT_MIGRATION_TABLES,
                withLogs = false,
            ).filter(::isAdditiveSchemaChange)
        }
        check(additiveDrift.isEmpty()) {
            "Flyway V10/V11/V12 is missing additive DDL required by Exposed:\n" +
                additiveDrift.joinToString(separator = "\n")
        }
    }

    private fun isAdditiveSchemaChange(statement: String): Boolean {
        val normalized = statement
            .trim()
            .replace(Regex("\\s+"), " ")
            .uppercase()
        return normalized.startsWith("CREATE TABLE ") ||
            normalized.startsWith("CREATE INDEX ") ||
            normalized.startsWith("CREATE UNIQUE INDEX ") ||
            (normalized.startsWith("ALTER TABLE ") && normalized.contains(" ADD COLUMN "))
    }

    private fun tableNames(connection: Connection): Set<String> =
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { result ->
            buildSet {
                while (result.next()) {
                    add(result.getString("TABLE_NAME").lowercase())
                }
            }
        }

    private fun columns(
        connection: Connection,
        table: String,
    ): Map<String, ColumnMetadata> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getColumns(null, null, candidate, "%").use { result ->
                buildMap {
                    while (result.next()) {
                        put(
                            result.getString("COLUMN_NAME").lowercase(),
                            ColumnMetadata(
                                nullable = result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                            ),
                        )
                    }
                }.takeIf(Map<String, ColumnMetadata>::isNotEmpty)
            }
        }.orEmpty()

    private fun indexDefinition(
        connection: Connection,
        table: String,
        index: String,
    ): List<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, false, false).use { result ->
                buildList {
                    while (result.next()) {
                        if (result.getString("INDEX_NAME").equals(index, ignoreCase = true)) {
                            val column = result.getString("COLUMN_NAME") ?: continue
                            val direction = result.getString("ASC_OR_DESC")
                                ?.uppercase()
                                ?.takeIf { it == "A" || it == "D" }
                                ?: "A"
                            add(
                                Triple(
                                    result.getInt("ORDINAL_POSITION"),
                                    column.lowercase(),
                                    direction,
                                ),
                            )
                        }
                    }
                }.takeIf(List<Triple<Int, String, String>>::isNotEmpty)
            }
        }
            ?.sortedBy { it.first }
            ?.map { (_, column, direction) -> "$column:$direction" }
            .orEmpty()

    private fun importedKeys(
        connection: Connection,
        table: String,
    ): Set<String> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getImportedKeys(null, null, candidate).use { result ->
                buildSet {
                    while (result.next()) {
                        add(
                            result.getString("FKCOLUMN_NAME").lowercase() +
                                "->" +
                                result.getString("PKTABLE_NAME").lowercase(),
                        )
                    }
                }.takeIf(Set<String>::isNotEmpty)
            }
        }.orEmpty()

    private fun uniqueIndexDefinitions(
        connection: Connection,
        table: String,
    ): Set<List<String>> =
        metadataTableCandidates(table).firstNotNullOfOrNull { candidate ->
            connection.metaData.getIndexInfo(null, null, candidate, true, false).use { result ->
                buildMap<String, MutableList<Pair<Int, String>>> {
                    while (result.next()) {
                        val indexName = result.getString("INDEX_NAME") ?: continue
                        val columnName = result.getString("COLUMN_NAME") ?: continue
                        getOrPut(indexName) { mutableListOf() }
                            .add(result.getInt("ORDINAL_POSITION") to columnName.lowercase())
                    }
                }
                    .values
                    .mapTo(mutableSetOf()) { columns ->
                        columns.sortedBy(Pair<Int, String>::first).map(Pair<Int, String>::second)
                    }
                    .takeIf(Set<List<String>>::isNotEmpty)
            }
        }.orEmpty()

    private fun metadataTableCandidates(table: String): List<String> =
        listOf(table, table.uppercase())

    private data class ColumnMetadata(
        val nullable: Boolean,
    )

    private val EXPECTED_TABLES = setOf(
        "scheduling_appointment_commitments",
        "scheduling_appointment_proposals",
        "scheduling_consent_decisions",
        "scheduling_appointment_plan_revisions",
        "scheduling_plan_revision_treatments",
        "scheduling_plan_revision_dependencies",
        "scheduling_plan_revision_grouping_constraints",
        "scheduling_appointment_items",
        "scheduling_treatment_spaces",
        "scheduling_resource_capacity_buckets",
        "scheduling_resource_allocations",
        "scheduling_appointment_operational_exceptions",
        "scheduling_appointment_command_idempotencies",
        "scheduling_appointment_audit_events",
    )

    private val EXPECTED_FOREIGN_KEYS = setOf(
        "scheduling_appointment_commitments.appointment_id->scheduling_appointments",
        "scheduling_appointment_proposals.commitment_id->scheduling_appointment_commitments",
        "scheduling_consent_decisions.commitment_id->scheduling_appointment_commitments",
        "scheduling_appointment_plan_revisions.plan_id->scheduling_appointment_plans",
        "scheduling_appointment_items.plan_revision_id->scheduling_appointment_plan_revisions",
        "scheduling_plan_revision_grouping_constraints.plan_revision_id->scheduling_appointment_plan_revisions",
        "scheduling_resource_allocations.proposal_id->scheduling_appointment_proposals",
        "scheduling_treatment_spaces.tenant_group_id->scheduling_tenant_groups",
        "scheduling_appointment_audit_events.clinic_id->scheduling_clinics",
    )

    private val EXPECTED_UNIQUE_IDENTITIES = mapOf(
        "scheduling_appointment_commitments" to listOf("appointment_id"),
        "scheduling_appointment_proposals" to listOf("commitment_id", "revision"),
        "scheduling_consent_decisions" to listOf("evidence_authority", "evidence_id"),
        "scheduling_appointment_plan_revisions" to listOf("plan_id", "revision"),
        "scheduling_plan_revision_grouping_constraints" to
            listOf("plan_revision_id", "first_treatment_key", "second_treatment_key"),
        "scheduling_appointment_command_idempotencies" to
            listOf("tenant_group_id", "clinic_id", "actor_scope_hash", "idempotency_key"),
        "scheduling_treatment_spaces" to listOf("tenant_group_id", "clinic_id", "space_code"),
        "scheduling_resource_capacity_buckets" to
            listOf("tenant_group_id", "clinic_id", "resource_type", "resource_id", "bucket_start_at"),
    )

    private val EXPOSED_VISIT_MIGRATION_TABLES: Array<Table> = arrayOf(
        Appointments,
        AppointmentPlanRevisions,
        PlanRevisionTreatments,
        PlanRevisionDependencies,
        PlanRevisionGroupingConstraints,
        AppointmentCommitments,
        AppointmentProposals,
        ConsentDecisions,
        AppointmentItems,
        TreatmentSpaces,
        ResourceCapacityBuckets,
        ResourceAllocations,
        AppointmentOperationalExceptions,
        AppointmentCommandIdempotencies,
        AppointmentAuditEvents,
        SchedulingQuarantineEvents,
    )
}
