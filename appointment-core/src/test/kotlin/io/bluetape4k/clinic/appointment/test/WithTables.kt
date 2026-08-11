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
import org.jetbrains.exposed.v1.jdbc.deleteAll
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

            SchemaUtils.createMissingTablesAndColumns(*tablesToUse)
            seedDefaultTenantIfNeeded(tablesToUse)
            commit()

            try {
                statement(testDB)
                commit() // Need commit to persist data before drop tables
            } finally {
                if (dropTables) {
                    try {
                        withReferentialIntegrityDisabled(testDB) {
                            clearTestRows(tablesToUse)
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
                                clearTestRows(tablesToUse)
                                dropTestTables(testDB, tablesToUse)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 일반 fixture가 만든 row를 FK 자식부터 정리합니다. schema-contract 테스트의 명시적
 * DDL 재생성은 이 helper 밖에서만 허용하고, 일반 테스트는 row 정리를 기본 경로로 둡니다.
 */
private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.clearTestRows(
    tables: Array<out Table>,
) {
    tables
        .toList()
        .asReversed()
        .distinctBy { it.tableName }
        .forEach { it.deleteAll() }
}

private val withTablesSchemaLock = Any()

/**
 * waitlist delivery 계약으로 추가된 테이블은 모두 [Clinics]를 참조한다.
 * schema-contract 테스트가 assertion 사이에 테이블을 의도적으로 유지하면 테이블이
 * 남을 수 있으므로, 더 좁은 fixture는 공유 clinic parent를 삭제하기 전에 이 자식들을
 * 제거해야 한다. 이 목록은 H2 테스트 정리 전용이며 운영 스키마나 애플리케이션 transaction을
 * 변경하지 않는다.
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
 * 공유 in-memory H2 데이터베이스는 더 좁은 fixture를 해제하는 동안 다른 테스트가 만든
 * 테이블을 유지할 수 있다. 자식 테이블이 fixture 밖에 있더라도 해당 foreign key가 존재하면
 * H2는 parent 테이블 삭제를 거부한다. 범위를 제한한 DDL 정리에서만 H2 테스트 데이터베이스의
 * 참조 무결성 검사를 비활성화하고, 운영 데이터베이스는 정상적인 제약 조건 적용을 유지한다.
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
