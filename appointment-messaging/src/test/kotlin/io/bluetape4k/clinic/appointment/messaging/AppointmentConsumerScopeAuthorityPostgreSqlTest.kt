package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock

/** PostgreSQL의 실제 tenant-clinic ownership query가 live authority 계약을 지키는지 검증합니다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(
    value = "appointment-messaging-postgresql-scope-authority",
    mode = ResourceAccessMode.READ_WRITE,
)
class AppointmentConsumerScopeAuthorityPostgreSqlTest {
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgreSQL() {
        val postgres = PostgreSQLServer.Launcher.postgres
        database = Database.connect(
            postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username ?: PostgreSQLServer.USERNAME,
            password = postgres.password ?: PostgreSQLServer.PASSWORD,
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
        }
    }

    @BeforeEach
    fun resetScope() {
        transaction(database) {
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            TenantGroups.insert {
                it[id] = EntityID(7L, TenantGroups)
                it[tenantCode] = "tenant-scope-authority"
                it[displayName] = "Scope Authority Tenant"
                it[active] = true
            }
            Clinics.insert {
                it[id] = EntityID(31L, Clinics)
                it[tenantGroupId] = 7L
                it[name] = "Scope Authority Clinic"
            }
        }
    }

    @Test
    fun `database authority accepts only the current tenant clinic ownership`() {
        val authority = DatabaseAppointmentConsumerScopeAuthority(database)

        authority.isAuthorized(tenantGroupId = 7L, clinicId = 31L).shouldBeTrue()
        authority.isAuthorized(tenantGroupId = 8L, clinicId = 31L).shouldBeFalse()
        authority.isAuthorized(tenantGroupId = 7L, clinicId = 999L).shouldBeFalse()
    }
}
