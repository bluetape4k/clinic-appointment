package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationMethod
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.ProviderType
import io.bluetape4k.clinic.appointment.model.tables.TreatmentCategory
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class SlotCalculationServiceTest : AbstractExposedTest() {

    companion object : KLogging() {
        private val service = SlotCalculationService()

        private val MONDAY = LocalDate.of(2026, 3, 23)

        private val allTables = arrayOf(
            Holidays,
            Clinics,
            ClinicDefaultBreakTimes,
            OperatingHoursTable,
            BreakTimes,
            ClinicClosures,
            Doctors,
            DoctorSchedules,
            DoctorAbsences,
            Equipments,
            TreatmentTypes,
            TreatmentEquipments,
            ConsultationTopics,
            Appointments,
            AppointmentNotes,
            EquipmentUnavailabilities,
            EquipmentUnavailabilityExceptions,
        )
    }

    private fun JdbcTransaction.insertBaseData(
        clinicOpen: LocalTime = LocalTime.of(9, 0),
        clinicClose: LocalTime = LocalTime.of(18, 0),
        slotDurationMinutes: Int = 30,
        maxConcurrentPatients: Int = 1,
        openOnHolidays: Boolean = false,
        doctorStart: LocalTime = LocalTime.of(9, 0),
        doctorEnd: LocalTime = LocalTime.of(18, 0),
        treatmentDurationMinutes: Int = 30,
        requiresEquipment: Boolean = false,
        doctorMaxConcurrent: Int? = null,
        treatmentMaxConcurrent: Int? = null,
        providerType: String = ProviderType.DOCTOR,
        treatmentCategory: String = TreatmentCategory.TREATMENT,
        requiredProviderType: String = ProviderType.DOCTOR,
        consultationMethod: String? = null,
    ): Triple<Long, Long, Long> {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Test Clinic"
            it[Clinics.slotDurationMinutes] = slotDurationMinutes
            it[Clinics.maxConcurrentPatients] = maxConcurrentPatients
            it[Clinics.openOnHolidays] = openOnHolidays
        }.value

        OperatingHoursTable.insert {
            it[OperatingHoursTable.clinicId] = clinicId
            it[dayOfWeek] = DayOfWeek.MONDAY
            it[openTime] = clinicOpen
            it[closeTime] = clinicClose
            it[isActive] = true
        }

        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Dr. Kim"
            it[Doctors.providerType] = providerType
            it[Doctors.maxConcurrentPatients] = doctorMaxConcurrent
        }.value

        DoctorSchedules.insert {
            it[DoctorSchedules.doctorId] = doctorId
            it[dayOfWeek] = DayOfWeek.MONDAY
            it[startTime] = doctorStart
            it[endTime] = doctorEnd
        }

        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "General Checkup"
            it[category] = treatmentCategory
            it[defaultDurationMinutes] = treatmentDurationMinutes
            it[TreatmentTypes.requiredProviderType] = requiredProviderType
            it[TreatmentTypes.consultationMethod] = consultationMethod
            it[TreatmentTypes.requiresEquipment] = requiresEquipment
            it[TreatmentTypes.maxConcurrentPatients] = treatmentMaxConcurrent
        }.value

        return Triple(clinicId, doctorId, treatmentTypeId)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `1 - 기본 슬롯 생성 - 09_00-18_00, 30분 슬롯, 제외 없음 - 18개`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 18
            slots.first().startTime shouldBeEqualTo LocalTime.of(9, 0)
            slots.last().startTime shouldBeEqualTo LocalTime.of(17, 30)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `2 - 점심시간 제외 12_00-13_00 - 16개`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                BreakTimes.insert {
                    it[BreakTimes.clinicId] = clinicId
                    it[dayOfWeek] = DayOfWeek.MONDAY
                    it[startTime] = LocalTime.of(12, 0)
                    it[endTime] = LocalTime.of(13, 0)
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 16
            slots.none { it.startTime == LocalTime.of(12, 0) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(12, 30) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `3 - 종일 휴진 - 빈 리스트`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                ClinicClosures.insert {
                    it[ClinicClosures.clinicId] = clinicId
                    it[closureDate] = MONDAY
                    it[reason] = "Holiday"
                    it[isFullDay] = true
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `4 - 부분 휴진 13_00-15_00 - 감소된 슬롯`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                ClinicClosures.insert {
                    it[ClinicClosures.clinicId] = clinicId
                    it[closureDate] = MONDAY
                    it[reason] = "Partial closure"
                    it[isFullDay] = false
                    it[startTime] = LocalTime.of(13, 0)
                    it[endTime] = LocalTime.of(15, 0)
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 14
            slots.none {
                it.startTime >= LocalTime.of(13, 0) && it.startTime < LocalTime.of(15, 0)
            }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `5 - 의사 스케줄이 운영시간보다 짧음 10_00-16_00 - 교차 결과`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(
                doctorStart = LocalTime.of(10, 0),
                doctorEnd = LocalTime.of(16, 0)
            )

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 12
            slots.first().startTime shouldBeEqualTo LocalTime.of(10, 0)
            slots.last().startTime shouldBeEqualTo LocalTime.of(15, 30)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `6 - 의사 종일 부재 - 빈 리스트`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                DoctorAbsences.insert {
                    it[DoctorAbsences.doctorId] = doctorId
                    it[absenceDate] = MONDAY
                    it[startTime] = null
                    it[endTime] = null
                    it[reason] = "Sick leave"
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `7 - 의사 부분 부재 14_00-16_00 - 감소된 슬롯`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                DoctorAbsences.insert {
                    it[DoctorAbsences.doctorId] = doctorId
                    it[absenceDate] = MONDAY
                    it[startTime] = LocalTime.of(14, 0)
                    it[endTime] = LocalTime.of(16, 0)
                    it[reason] = "Meeting"
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 14
            slots.none {
                it.startTime >= LocalTime.of(14, 0) && it.startTime < LocalTime.of(16, 0)
            }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `8 - 동시 수용 3, 기존 예약 2명 - remainingCapacity 1`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(maxConcurrentPatients = 3)

            transaction {
                repeat(2) { i ->
                    Appointments.insert {
                        it[Appointments.clinicId] = clinicId
                        it[Appointments.doctorId] = doctorId
                        it[Appointments.treatmentTypeId] = treatmentTypeId
                        it[patientName] = "Patient $i"
                        it[appointmentDate] = MONDAY
                        it[startTime] = LocalTime.of(9, 0)
                        it[endTime] = LocalTime.of(9, 30)
                        it[status] = AppointmentState.CONFIRMED
                    }
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            val slot0900 = slots.first { it.startTime == LocalTime.of(9, 0) }
            slot0900.remainingCapacity shouldBeEqualTo 1

            val slot0930 = slots.first { it.startTime == LocalTime.of(9, 30) }
            slot0930.remainingCapacity shouldBeEqualTo 3
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `9 - 동시 수용 가득 - 해당 슬롯 제외`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(maxConcurrentPatients = 1)

            transaction {
                Appointments.insert {
                    it[Appointments.clinicId] = clinicId
                    it[Appointments.doctorId] = doctorId
                    it[Appointments.treatmentTypeId] = treatmentTypeId
                    it[patientName] = "Patient Full"
                    it[appointmentDate] = MONDAY
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(9, 30)
                    it[status] = AppointmentState.CONFIRMED
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 17
            slots.none { it.startTime == LocalTime.of(9, 0) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `10 - 장비 필요하고 전부 사용 중 - 해당 슬롯 제외`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(requiresEquipment = true)

            val equipmentId = transaction {
                val eqId = Equipments.insertAndGetId {
                    it[Equipments.clinicId] = clinicId
                    it[name] = "X-Ray Machine"
                    it[usageDurationMinutes] = 30
                    it[quantity] = 1
                }.value

                TreatmentEquipments.insert {
                    it[TreatmentEquipments.treatmentTypeId] = treatmentTypeId
                    it[TreatmentEquipments.equipmentId] = eqId
                }

                eqId
            }

            transaction {
                Appointments.insert {
                    it[Appointments.clinicId] = clinicId
                    it[Appointments.doctorId] = doctorId
                    it[Appointments.treatmentTypeId] = treatmentTypeId
                    it[Appointments.equipmentId] = equipmentId
                    it[patientName] = "Patient Equip"
                    it[appointmentDate] = MONDAY
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(9, 30)
                    it[status] = AppointmentState.CONFIRMED
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots.none { it.startTime == LocalTime.of(9, 0) }.shouldBeTrue()
            slots shouldHaveSize 17
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `11 - 장비 수량 2, 1개 사용 중 - 슬롯 가용`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(
                maxConcurrentPatients = 2,
                requiresEquipment = true
            )

            val equipmentId = transaction {
                val eqId = Equipments.insertAndGetId {
                    it[Equipments.clinicId] = clinicId
                    it[name] = "X-Ray Machine"
                    it[usageDurationMinutes] = 30
                    it[quantity] = 2
                }.value

                TreatmentEquipments.insert {
                    it[TreatmentEquipments.treatmentTypeId] = treatmentTypeId
                    it[TreatmentEquipments.equipmentId] = eqId
                }

                eqId
            }

            transaction {
                Appointments.insert {
                    it[Appointments.clinicId] = clinicId
                    it[Appointments.doctorId] = doctorId
                    it[Appointments.treatmentTypeId] = treatmentTypeId
                    it[Appointments.equipmentId] = equipmentId
                    it[patientName] = "Patient Equip"
                    it[appointmentDate] = MONDAY
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(9, 30)
                    it[status] = AppointmentState.CONFIRMED
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            val slot0900 = slots.firstOrNull { it.startTime == LocalTime.of(9, 0) }
            slot0900.shouldNotBeNull()
            slot0900.equipmentIds shouldContain equipmentId
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `12 - 진료 시간 60분이 슬롯 단위 30분보다 큼 - 후보 감소`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(treatmentDurationMinutes = 60)

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 17
            slots.first().startTime shouldBeEqualTo LocalTime.of(9, 0)
            slots.last().startTime shouldBeEqualTo LocalTime.of(17, 0)
            slots.forEach { slot ->
                slot.endTime shouldBeEqualTo slot.startTime.plusMinutes(60)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `13 - 의사가 상담 진료 유형에 배정되면 빈 슬롯 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(
                providerType = ProviderType.DOCTOR,
                treatmentCategory = TreatmentCategory.CONSULTATION,
                requiredProviderType = ProviderType.CONSULTANT,
                consultationMethod = ConsultationMethod.IN_PERSON
            )

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots.isEmpty().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `14 - 전문상담사가 상담 진료 유형에 배정되면 슬롯 정상 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, consultantId, treatmentTypeId) = insertBaseData(
                providerType = ProviderType.CONSULTANT,
                treatmentCategory = TreatmentCategory.CONSULTATION,
                requiredProviderType = ProviderType.CONSULTANT,
                consultationMethod = ConsultationMethod.IN_PERSON
            )

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, consultantId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 18
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `15 - 전문상담사가 진료 유형에 배정되면 빈 슬롯 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, consultantId, treatmentTypeId) = insertBaseData(
                providerType = ProviderType.CONSULTANT,
                treatmentCategory = TreatmentCategory.TREATMENT,
                requiredProviderType = ProviderType.DOCTOR
            )

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, consultantId, treatmentTypeId, MONDAY)
            )

            slots.isEmpty().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `16 - 전화 상담은 장비 불필요, 영상통화는 장비 필요`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId1, consultantId1, phoneConsultationId) = insertBaseData(
                providerType = ProviderType.CONSULTANT,
                treatmentCategory = TreatmentCategory.CONSULTATION,
                requiredProviderType = ProviderType.CONSULTANT,
                consultationMethod = ConsultationMethod.PHONE,
                requiresEquipment = false
            )

            val phoneSlots = service.findAvailableSlots(
                SlotQuery(clinicId1, consultantId1, phoneConsultationId, MONDAY)
            )

            phoneSlots shouldHaveSize 18
            phoneSlots.forEach { slot ->
                slot.equipmentIds.isEmpty().shouldBeTrue()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `17 - 공휴일에는 기본적으로 빈 슬롯 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                Holidays.insert {
                    it[holidayDate] = MONDAY
                    it[name] = "테스트 공휴일"
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `18 - openOnHolidays 설정된 병원은 공휴일에도 슬롯 제공`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(openOnHolidays = true)

            transaction {
                Holidays.insert {
                    it[holidayDate] = MONDAY
                    it[name] = "테스트 공휴일"
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 18
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `19 - 병원 기본 휴식시간이 모든 영업일에 적용된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                ClinicDefaultBreakTimes.insert {
                    it[ClinicDefaultBreakTimes.clinicId] = clinicId
                    it[name] = "점심시간"
                    it[startTime] = LocalTime.of(12, 0)
                    it[endTime] = LocalTime.of(13, 0)
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 16
            slots.none { it.startTime == LocalTime.of(12, 0) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(12, 30) }.shouldBeTrue()
            slots.any { it.startTime == LocalTime.of(11, 30) }.shouldBeTrue()
            slots.any { it.startTime == LocalTime.of(13, 0) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `20 - 기본 휴식시간과 요일별 휴식시간이 동시에 적용된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                ClinicDefaultBreakTimes.insert {
                    it[ClinicDefaultBreakTimes.clinicId] = clinicId
                    it[name] = "점심시간"
                    it[startTime] = LocalTime.of(12, 0)
                    it[endTime] = LocalTime.of(13, 0)
                }
            }

            transaction {
                BreakTimes.insert {
                    it[BreakTimes.clinicId] = clinicId
                    it[dayOfWeek] = DayOfWeek.MONDAY
                    it[startTime] = LocalTime.of(15, 0)
                    it[endTime] = LocalTime.of(15, 30)
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 15
            slots.none { it.startTime == LocalTime.of(12, 0) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(15, 0) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `21 - 하루에 여러 기본 휴식시간이 적용된다`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData()

            transaction {
                ClinicDefaultBreakTimes.insert {
                    it[ClinicDefaultBreakTimes.clinicId] = clinicId
                    it[name] = "점심시간"
                    it[startTime] = LocalTime.of(12, 0)
                    it[endTime] = LocalTime.of(13, 0)
                }
                ClinicDefaultBreakTimes.insert {
                    it[ClinicDefaultBreakTimes.clinicId] = clinicId
                    it[name] = "오후 티타임"
                    it[startTime] = LocalTime.of(15, 0)
                    it[endTime] = LocalTime.of(15, 30)
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 15
            slots.none { it.startTime == LocalTime.of(12, 0) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(12, 30) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(15, 0) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `22 - 장비 사용불가 시간과 겹치는 슬롯 제외`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(requiresEquipment = true)

            val equipmentId = transaction {
                val eqId = Equipments.insertAndGetId {
                    it[Equipments.clinicId] = clinicId
                    it[name] = "MRI Machine"
                    it[usageDurationMinutes] = 30
                    it[quantity] = 1
                }.value

                TreatmentEquipments.insert {
                    it[TreatmentEquipments.treatmentTypeId] = treatmentTypeId
                    it[TreatmentEquipments.equipmentId] = eqId
                }

                eqId
            }

            transaction {
                EquipmentUnavailabilities.insert {
                    it[EquipmentUnavailabilities.equipmentId] = equipmentId
                    it[EquipmentUnavailabilities.clinicId] = clinicId
                    it[unavailableDate] = MONDAY
                    it[isRecurring] = false
                    it[recurringDayOfWeek] = null
                    it[effectiveFrom] = MONDAY
                    it[effectiveUntil] = MONDAY
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(10, 0)
                    it[reason] = "점검"
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 16
            slots.none { it.startTime == LocalTime.of(9, 0) }.shouldBeTrue()
            slots.none { it.startTime == LocalTime.of(9, 30) }.shouldBeTrue()
            slots.any { it.startTime == LocalTime.of(10, 0) }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `23 - 장비 사용불가 없으면 모든 슬롯 정상 반환`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val (clinicId, doctorId, treatmentTypeId) = insertBaseData(requiresEquipment = true)

            transaction {
                val eqId = Equipments.insertAndGetId {
                    it[Equipments.clinicId] = clinicId
                    it[name] = "Ultrasound Machine"
                    it[usageDurationMinutes] = 30
                    it[quantity] = 1
                }.value

                TreatmentEquipments.insert {
                    it[TreatmentEquipments.treatmentTypeId] = treatmentTypeId
                    it[TreatmentEquipments.equipmentId] = eqId
                }
            }

            val slots = service.findAvailableSlots(
                SlotQuery(clinicId, doctorId, treatmentTypeId, MONDAY)
            )

            slots shouldHaveSize 18
        }
    }
}
