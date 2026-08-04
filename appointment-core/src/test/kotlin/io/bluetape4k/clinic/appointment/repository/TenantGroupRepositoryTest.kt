package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class TenantGroupRepositoryTest : AbstractExposedTest() {

    private val repository = TenantGroupRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `활성 tenant code만 조회한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups) {
            TenantGroups.insert {
                it[id] = EntityID(2L, TenantGroups)
                it[tenantCode] = "tenant-inactive"
                it[displayName] = "Inactive Tenant"
                it[active] = false
            }

            repository.findActiveByCode(TenantGroups.DEFAULT_TENANT_CODE)
                .shouldNotBeNull()
                .tenantCode shouldBeEqualTo TenantGroups.DEFAULT_TENANT_CODE
            repository.findActiveByCode("tenant-inactive").shouldBeNull()
        }
    }

    @Test
    fun `tenant code는 중복될 수 없다`() {
        withTables(TestDB.H2, TenantGroups) {
            assertFailsWith<ExposedSQLException> {
                TenantGroups.insert {
                    it[id] = EntityID(2L, TenantGroups)
                    it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
                    it[displayName] = "Duplicate Tenant"
                    it[active] = true
                }
            }
        }
    }
}
