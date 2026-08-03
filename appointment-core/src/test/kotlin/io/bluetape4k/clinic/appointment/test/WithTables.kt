package io.bluetape4k.clinic.appointment.test

import io.bluetape4k.clinic.appointment.model.tables.BookingBenefitGrants
import io.bluetape4k.clinic.appointment.model.tables.BookingRestrictions
import io.bluetape4k.clinic.appointment.model.tables.DisruptionRecoveryCredits
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCommandRecords
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOfferEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyEvents
import io.bluetape4k.clinic.appointment.model.tables.WaitlistPolicyVersions
import io.bluetape4k.clinic.appointment.model.tables.WaitlistVacancyJobs
import io.bluetape4k.logging.error
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager

/**
 * 지정한 [tables] 를 생성한 뒤 [statement] 를 실행하고, 종료 시 테이블을 정리합니다.
 *
 * 테스트마다 독립적인 스키마 상태를 보장해야 할 때 사용합니다.
 */
fun withTables(
    testDB: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: JdbcTransaction.(TestDB) -> Unit,
) {
    synchronized(withTablesSchemaLock) {
        val tablesToUse = withTenantGroups(tables)

        withDb(testDB, configure = configure) {
            runCatching {
                withReferentialIntegrityDisabled(testDB) {
                    dropTestTables(testDB, tablesToUse)
                }
            }

            SchemaUtils.create(*tablesToUse)
            seedDefaultTenantIfNeeded(tablesToUse)
            commit()

            try {
                statement(testDB)
                commit() // Need commit to persist data before drop tables
            } finally {
                if (dropTables) {
                    try {
                        withReferentialIntegrityDisabled(testDB) {
                            dropTestTables(testDB, tablesToUse)
                        }
                        commit()
                    } catch (ex: Exception) {
                        logger.error(ex) { "Drop Tables 에서 예외가 발생했습니다. 삭제할 테이블: ${tablesToUse.joinToString { it.tableName }}" }
                        val database = testDB.db ?: return@withDb
                        inTopLevelTransaction(
                            transactionIsolation = database.transactionManager.defaultIsolationLevel,
                            db = database
                        ) {
                            maxAttempts = 1
                            withReferentialIntegrityDisabled(testDB) {
                                dropTestTables(testDB, tablesToUse)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val withTablesSchemaLock = Any()

/**
 * Tables introduced by the waitlist delivery contract all reference [Clinics].
 * They may be left behind by schema-contract tests that intentionally keep their
 * tables between assertions, so a narrower fixture must remove these children
 * before dropping a shared clinic parent. The list is H2-test cleanup only; it
 * never changes production schema or application transactions.
 */
private val sharedClinicDependentWaitlistTables = arrayOf(
    WaitlistOfferEvents,
    WaitlistCapacityHolds,
    WaitlistOffers,
    WaitlistEntries,
    WaitlistPolicyEvents,
    WaitlistPolicyVersions,
    BookingRestrictions,
    DisruptionRecoveryCredits,
    BookingBenefitGrants,
    WaitlistVacancyJobs,
    WaitlistCommandRecords,
)

private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.dropTestTables(
    testDB: TestDB,
    tables: Array<out Table>,
) {
    val tablesToDrop = if (testDB.name.startsWith("H2")) {
        (sharedClinicDependentWaitlistTables.asList() + tables.asList())
            .distinctBy { it.tableName }
            .toTypedArray()
    } else {
        tables
    }
    SchemaUtils.drop(*tablesToDrop)
}

/**
 * Shared in-memory H2 databases may retain tables created by another test while a
 * narrower fixture is being torn down. H2 refuses to drop a parent table while
 * those foreign keys exist, even when the child table is outside the fixture.
 * Disable only H2's test-database referential checks for the bounded DDL cleanup;
 * production databases keep their normal constraint enforcement.
 */
private inline fun <T> org.jetbrains.exposed.v1.jdbc.JdbcTransaction.withReferentialIntegrityDisabled(
    testDB: TestDB,
    block: () -> T,
): T {
    if (!testDB.name.startsWith("H2")) {
        return block()
    }

    exec("SET REFERENTIAL_INTEGRITY FALSE")
    return try {
        block()
    } finally {
        exec("SET REFERENTIAL_INTEGRITY TRUE")
    }
}

private fun withTenantGroups(tables: Array<out Table>): Array<out Table> =
    if (tables.any { it === TenantGroups }) {
        tables
    } else {
        arrayOf(TenantGroups, *tables)
    }

private fun seedDefaultTenantIfNeeded(tables: Array<out Table>) {
    if (tables.none { it === TenantGroups }) {
        return
    }

    val defaultTenantExists = !TenantGroups
        .selectAll()
        .where { TenantGroups.id eq TenantGroups.DEFAULT_TENANT_GROUP_ID }
        .empty()
    if (defaultTenantExists) {
        return
    }

    TenantGroups.insert {
        it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
        it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
        it[displayName] = TenantGroups.DEFAULT_TENANT_NAME
        it[active] = true
    }
}
