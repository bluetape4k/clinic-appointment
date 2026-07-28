package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.model.dto.TreatmentSpaceRecord
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.Test

class TreatmentSpaceRepositoryTest {

    private val repository = TreatmentSpaceRepository()

    @Test
    fun `실제 공간을 tenant clinic 범위와 capability로 조회한다`() {
        withCommitmentTables { seed ->
            val saved = repository.save(
                TreatmentSpaceRecord(
                    tenantGroupId = 1L,
                    clinicId = seed.clinicId,
                    spaceCode = "OR-1",
                    displayName = "수술실 1",
                    capabilities = listOf("SURGERY", "ANESTHESIA"),
                    nominalCapacity = 1,
                    bucketMinutes = 30,
                    active = true,
                ),
            )

            repository.findByCode(1L, seed.clinicId, "OR-1") shouldBeEqualTo saved
            repository.findByCode(2L, seed.clinicId, "OR-1").shouldBeNull()
            repository.findCompatible(1L, seed.clinicId, setOf("SURGERY")) shouldBeEqualTo listOf(saved)
        }
    }

    @Test
    fun `같은 병원 안의 안정적인 공간 code는 중복될 수 없다`() {
        withCommitmentTables { seed ->
            val record = TreatmentSpaceRecord(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                spaceCode = "ROOM-1",
                displayName = "진료실",
                capabilities = listOf("CARE"),
                nominalCapacity = 2,
                bucketMinutes = 15,
                active = true,
            )
            repository.save(record)
            assertFailsWith<ExposedSQLException> {
                repository.save(record)
            }
        }
    }
}
