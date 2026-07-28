package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.TreatmentSpaceRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TreatmentSpaces
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * caller transaction 안에서 병원별 실제 진료 공간과 capability를 저장·조회합니다.
 */
class TreatmentSpaceRepository {

    /**
     * 공간의 tenant·clinic 소유권을 검증하고 저장합니다.
     */
    fun save(record: TreatmentSpaceRecord): TreatmentSpaceRecord {
        require(
            Clinics.selectAll().where {
                (Clinics.id eq record.clinicId) and
                    (Clinics.tenantGroupId eq record.tenantGroupId)
            }.count() == 1L,
        ) {
            "clinic must belong to tenantGroupId"
        }
        require(record.capabilities.all(String::isNotBlank)) { "capabilities must not contain blank values" }
        require(record.capabilities.size == record.capabilities.toSet().size) {
            "capabilities must be unique"
        }
        val id = TreatmentSpaces.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[clinicId] = record.clinicId
            it[spaceCode] = record.spaceCode
            it[displayName] = record.displayName
            it[capabilitiesPayload] = encodeCapabilities(record.capabilities)
            it[nominalCapacity] = record.nominalCapacity
            it[bucketMinutes] = record.bucketMinutes
            it[active] = record.active
        }.value
        return record.copy(id = id)
    }

    /** 정확한 tenant·clinic·code 범위의 공간만 반환합니다. */
    fun findByCode(
        tenantGroupId: Long,
        clinicId: Long,
        spaceCode: String,
    ): TreatmentSpaceRecord? {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        spaceCode.requireNotBlank("spaceCode")
        return TreatmentSpaces
            .selectAll()
            .where {
                (TreatmentSpaces.tenantGroupId eq tenantGroupId) and
                    (TreatmentSpaces.clinicId eq clinicId) and
                    (TreatmentSpaces.spaceCode eq spaceCode)
            }
            .singleOrNull()
            ?.let(::mapSpace)
    }

    /** 필요한 capability를 모두 제공하는 활성 실제 공간을 code 순으로 반환합니다. */
    fun findCompatible(
        tenantGroupId: Long,
        clinicId: Long,
        requiredCapabilities: Set<String>,
    ): List<TreatmentSpaceRecord> =
        TreatmentSpaces
            .selectAll()
            .where {
                (TreatmentSpaces.tenantGroupId eq tenantGroupId) and
                    (TreatmentSpaces.clinicId eq clinicId) and
                    (TreatmentSpaces.active eq true)
            }
            .map(::mapSpace)
            .filter { it.capabilities.containsAll(requiredCapabilities) }
            .sortedBy(TreatmentSpaceRecord::spaceCode)

    private fun mapSpace(row: org.jetbrains.exposed.v1.core.ResultRow) = TreatmentSpaceRecord(
        id = row[TreatmentSpaces.id].value,
        tenantGroupId = row[TreatmentSpaces.tenantGroupId].value,
        clinicId = row[TreatmentSpaces.clinicId].value,
        spaceCode = row[TreatmentSpaces.spaceCode],
        displayName = row[TreatmentSpaces.displayName],
        capabilities = decodeCapabilities(row[TreatmentSpaces.capabilitiesPayload]),
        nominalCapacity = row[TreatmentSpaces.nominalCapacity],
        bucketMinutes = row[TreatmentSpaces.bucketMinutes],
        active = row[TreatmentSpaces.active],
    )

    private fun encodeCapabilities(values: List<String>): String = values.joinToString("\n")

    private fun decodeCapabilities(payload: String): List<String> =
        payload.lineSequence().filter(String::isNotEmpty).toList()
}
