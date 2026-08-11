package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import io.bluetape4k.clinic.appointment.model.tables.PatientAccounts
import io.bluetape4k.clinic.appointment.model.tables.PatientLoginIdentities
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.Test

class PatientAuthenticationRepositoryTest : AbstractExposedTest() {

    private val accountRepository = PatientAccountRepository()
    private val identityRepository = PatientLoginIdentityRepository()

    @Test
    fun `identifier lookup is tenant scoped and excludes inactive accounts`() {
        withTables(TestDB.H2, PatientAccounts, PatientLoginIdentities) {
            TenantGroups.insert {
                it[id] = 2L
                it[tenantCode] = "tenant-two"
                it[displayName] = "두 번째 Tenant"
            }
            val activeAccountId = PatientAccounts.insertAndGetId {
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[patientSubject] = "patient-subject-active"
                it[displayName] = "활성 환자"
                it[passwordHash] = "encoded-password"
                it[active] = true
            }
            val inactiveAccountId = PatientAccounts.insertAndGetId {
                it[tenantGroupId] = 2L
                it[patientSubject] = "patient-subject-inactive"
                it[displayName] = "비활성 환자"
                it[passwordHash] = "encoded-password"
                it[active] = false
            }
            PatientLoginIdentities.insert {
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[patientAccountId] = activeAccountId
                it[key] = PatientLoginIdentifierKey.EMAIL
                it[normalizedValue] = "active@example.com"
            }
            PatientLoginIdentities.insert {
                it[tenantGroupId] = 2L
                it[patientAccountId] = inactiveAccountId
                it[key] = PatientLoginIdentifierKey.EMAIL
                it[normalizedValue] = "inactive@example.com"
            }

            val identity = identityRepository.findActiveByIdentifier(
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                key = PatientLoginIdentifierKey.EMAIL,
                normalizedValue = "active@example.com",
            )
            identity.shouldNotBeNull().patientAccountId.shouldBeEqualTo(activeAccountId.value)
            accountRepository.findActiveById(TenantGroups.DEFAULT_TENANT_GROUP_ID, activeAccountId.value)
                .shouldNotBeNull()
                .patientSubject.shouldBeEqualTo("patient-subject-active")
            identityRepository.findActiveByIdentifier(
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                key = PatientLoginIdentifierKey.EMAIL,
                normalizedValue = "inactive@example.com",
            ).shouldBeNull()
            accountRepository.findActiveById(2L, inactiveAccountId.value).shouldBeNull()
        }
    }

    @Test
    fun `same identifier value is independent across tenants`() {
        withTables(TestDB.H2, PatientAccounts, PatientLoginIdentities) {
            TenantGroups.insert {
                it[id] = 2L
                it[tenantCode] = "tenant-two"
                it[displayName] = "두 번째 Tenant"
            }
            val first = PatientAccounts.insertAndGetId {
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[patientSubject] = "patient-subject-one"
                it[displayName] = "첫 환자"
                it[passwordHash] = "encoded-password"
            }
            val second = PatientAccounts.insertAndGetId {
                it[tenantGroupId] = 2L
                it[patientSubject] = "patient-subject-two"
                it[displayName] = "둘 환자"
                it[passwordHash] = "encoded-password"
            }
            listOf(
                TenantGroups.DEFAULT_TENANT_GROUP_ID to first,
                2L to second,
            ).forEach { (tenantId, accountId) ->
                PatientLoginIdentities.insert {
                    it[tenantGroupId] = tenantId
                    it[patientAccountId] = accountId
                    it[key] = PatientLoginIdentifierKey.PHONE
                    it[normalizedValue] = "+821012345678"
                }
            }

            identityRepository.findActiveByIdentifier(
                TenantGroups.DEFAULT_TENANT_GROUP_ID,
                PatientLoginIdentifierKey.PHONE,
                "+821012345678",
            )?.patientAccountId.shouldBeEqualTo(first.value)
            identityRepository.findActiveByIdentifier(
                2L,
                PatientLoginIdentifierKey.PHONE,
                "+821012345678",
            )?.patientAccountId.shouldBeEqualTo(second.value)
        }
    }
}
