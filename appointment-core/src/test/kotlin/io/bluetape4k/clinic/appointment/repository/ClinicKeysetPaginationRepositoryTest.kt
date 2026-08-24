package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetCursor
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ProviderType
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentCategory
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.assertions.shouldHaveSize
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ClinicKeysetPaginationRepositoryTest : AbstractExposedTest() {

    companion object {
        private const val TENANT_A = TenantGroups.DEFAULT_TENANT_GROUP_ID
        private const val TENANT_B = 2L
        private const val CLINIC_A = 10L
        private const val CLINIC_B = 20L

        private const val DOCTOR_FIRST = 101L
        private const val DOCTOR_SECOND = 105L
        private const val DOCTOR_LAST = 120L

        private const val EQUIPMENT_FIRST = 401L
        private const val EQUIPMENT_SECOND = 405L
        private const val EQUIPMENT_LAST = 420L

        private const val TREATMENT_FIRST = 601L
        private const val TREATMENT_SECOND = 605L
        private const val TREATMENT_LAST = 620L
    }

    private val doctorRepository = DoctorRepository()
    private val equipmentRepository = EquipmentRepository()
    private val treatmentTypeRepository = TreatmentTypeRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `세 repository는 clinic 범위와 exclusive cursor 순서를 함께 보장한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
            insertFixture()
            val scope = TenantClinicScope(TENANT_A, CLINIC_A)

            val firstDoctors = doctorRepository.findKeysetPage(scope, cursor = null, limit = 2)
            firstDoctors.content.map { it.id } shouldBeEqualTo listOf(DOCTOR_FIRST, DOCTOR_SECOND)
            firstDoctors.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, DOCTOR_SECOND)

            val lastDoctors = doctorRepository.findKeysetPage(scope, firstDoctors.nextCursor, limit = 2)
            lastDoctors.content.map { it.id } shouldBeEqualTo listOf(DOCTOR_LAST)
            lastDoctors.nextCursor.shouldBeNull()

            val firstEquipments = equipmentRepository.findKeysetPage(scope, cursor = null, limit = 2)
            firstEquipments.content.map { it.id } shouldBeEqualTo listOf(EQUIPMENT_FIRST, EQUIPMENT_SECOND)
            firstEquipments.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, EQUIPMENT_SECOND)

            val firstTreatmentTypes = treatmentTypeRepository.findKeysetPage(scope, cursor = null, limit = 2)
            firstTreatmentTypes.content.map { it.id } shouldBeEqualTo listOf(TREATMENT_FIRST, TREATMENT_SECOND)
            firstTreatmentTypes.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, TREATMENT_SECOND)

            val tenantBPage = doctorRepository.findKeysetPage(TenantClinicScope(TENANT_B, CLINIC_B), null, 10)
            tenantBPage.content.map { it.id } shouldBeEqualTo listOf(201L)
            tenantBPage.nextCursor.shouldBeNull()

            val emptyPage = doctorRepository.findKeysetPage(
                scope,
                ClinicKeysetCursor(CLINIC_A, 999L),
                limit = 2,
            )
            emptyPage.content shouldHaveSize 0
            emptyPage.nextCursor.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `repository는 잘못된 clinic cursor와 범위를 벗어난 limit을 거부한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Clinics, Doctors) {
            insertFixture(includeEquipments = false, includeTreatmentTypes = false)
            val scope = TenantClinicScope(TENANT_A, CLINIC_A)

            assertFailsWith<IllegalArgumentException> {
                doctorRepository.findKeysetPage(scope, ClinicKeysetCursor(CLINIC_B, 201L), limit = 2)
            }
            assertFailsWith<IllegalArgumentException> {
                doctorRepository.findKeysetPage(scope, cursor = null, limit = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                doctorRepository.findKeysetPage(scope, cursor = null, limit = 101)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `anchor 삭제와 sparse id 삽입 뒤에도 다음 페이지는 중복 없이 진행한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Clinics, Doctors) {
            insertFixture(includeEquipments = false, includeTreatmentTypes = false)
            val scope = TenantClinicScope(TENANT_A, CLINIC_A)

            val firstPage = doctorRepository.findKeysetPage(scope, cursor = null, limit = 1)
            firstPage.content.map { it.id } shouldBeEqualTo listOf(DOCTOR_FIRST)
            val anchor = firstPage.nextCursor

            Doctors.deleteWhere { Doctors.id eq DOCTOR_FIRST }
            insertDoctor(110L, CLINIC_A, "Doctor Inserted After Anchor")

            val secondPage = doctorRepository.findKeysetPage(scope, anchor, limit = 2)
            secondPage.content.map { it.id } shouldBeEqualTo listOf(DOCTOR_SECOND, 110L)
            secondPage.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, 110L)

            Doctors.deleteWhere { Doctors.id eq DOCTOR_SECOND }
            val finalPage = doctorRepository.findKeysetPage(scope, secondPage.nextCursor, limit = 2)
            finalPage.content.map { it.id } shouldBeEqualTo listOf(DOCTOR_LAST)
            finalPage.nextCursor.shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `keyset SQL은 count와 offset 없이 limit plus one과 정렬을 사용한다`(testDB: TestDB) {
        withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
            insertFixture()
            val capture = SqlStatementCapture()
            registerInterceptor(capture)

            val scope = TenantClinicScope(TENANT_A, CLINIC_A)
            doctorRepository.findKeysetPage(scope, ClinicKeysetCursor(CLINIC_A, DOCTOR_FIRST), limit = 2)
            equipmentRepository.findKeysetPage(scope, ClinicKeysetCursor(CLINIC_A, EQUIPMENT_FIRST), limit = 2)
            treatmentTypeRepository.findKeysetPage(scope, ClinicKeysetCursor(CLINIC_A, TREATMENT_FIRST), limit = 2)

            val keysetQueries = capture.statements.filter { statement ->
                statement.contains("scheduling_doctors") ||
                    statement.contains("scheduling_equipments") ||
                    statement.contains("scheduling_treatment_types")
            }
            keysetQueries shouldHaveSize 3
            keysetQueries.forEach { statement ->
                statement.contains("offset").shouldBeFalse()
                statement.contains("count(").shouldBeFalse()
                statement.contains("limit").shouldBeTrue()
                statement.contains("order by").shouldBeTrue()
                statement.contains("clinic_id").shouldBeTrue()
            }
        }
    }

    private fun JdbcTransaction.insertFixture(
        includeEquipments: Boolean = true,
        includeTreatmentTypes: Boolean = true,
    ) {
        TenantGroups.insert {
            it[id] = EntityID(TENANT_B, TenantGroups)
            it[tenantCode] = "tenant-b"
            it[displayName] = "Tenant B"
            it[active] = true
        }
        insertClinic(CLINIC_A, TENANT_A, "Clinic A")
        insertClinic(CLINIC_B, TENANT_B, "Clinic B")
        insertDoctor(DOCTOR_FIRST, CLINIC_A, "Doctor First")
        insertDoctor(DOCTOR_SECOND, CLINIC_A, "Doctor Second")
        insertDoctor(DOCTOR_LAST, CLINIC_A, "Doctor Last")
        insertDoctor(201L, CLINIC_B, "Tenant B Doctor")

        if (includeEquipments) {
            insertEquipment(EQUIPMENT_FIRST, CLINIC_A, "Equipment First")
            insertEquipment(EQUIPMENT_SECOND, CLINIC_A, "Equipment Second")
            insertEquipment(EQUIPMENT_LAST, CLINIC_A, "Equipment Last")
        }
        if (includeTreatmentTypes) {
            insertTreatmentType(TREATMENT_FIRST, CLINIC_A, "Treatment First")
            insertTreatmentType(TREATMENT_SECOND, CLINIC_A, "Treatment Second")
            insertTreatmentType(TREATMENT_LAST, CLINIC_A, "Treatment Last")
        }
    }

    private fun JdbcTransaction.insertClinic(clinicId: Long, tenantGroupId: Long, name: String) {
        Clinics.insert {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[Clinics.name] = name
        }
    }

    private fun JdbcTransaction.insertDoctor(doctorId: Long, clinicId: Long, name: String) {
        Doctors.insert {
            it[id] = EntityID(doctorId, Doctors)
            it[Doctors.clinicId] = EntityID(clinicId, Clinics)
            it[Doctors.name] = name
            it[Doctors.providerType] = ProviderType.DOCTOR
        }
    }

    private fun JdbcTransaction.insertEquipment(equipmentId: Long, clinicId: Long, name: String) {
        Equipments.insert {
            it[id] = EntityID(equipmentId, Equipments)
            it[Equipments.clinicId] = EntityID(clinicId, Clinics)
            it[Equipments.name] = name
            it[Equipments.usageDurationMinutes] = 30
            it[Equipments.quantity] = 1
        }
    }

    private fun JdbcTransaction.insertTreatmentType(treatmentTypeId: Long, clinicId: Long, name: String) {
        TreatmentTypes.insert {
            it[id] = EntityID(treatmentTypeId, TreatmentTypes)
            it[TreatmentTypes.clinicId] = EntityID(clinicId, Clinics)
            it[TreatmentTypes.name] = name
            it[TreatmentTypes.category] = TreatmentCategory.TREATMENT
            it[TreatmentTypes.defaultDurationMinutes] = 30
            it[TreatmentTypes.requiredProviderType] = ProviderType.DOCTOR
        }
    }

    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }
}
