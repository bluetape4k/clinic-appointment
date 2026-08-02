package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.VacancyDescriptor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 빈자리 descriptor의 서버 소유 canonical key를 생성합니다.
 *
 * member profile, 이름, 연락처 같은 고객 원문 값은 descriptor에 포함하지 않습니다. 같은
 * tenant/clinic/resource/time/capacity/treatment/doctor 조합은 재시도해도 같은 key를
 * 만들고, 하나라도 달라지면 다른 key가 됩니다.
 */
object WaitlistVacancyKeyHasher {

    fun hash(vacancy: VacancyDescriptor): String =
        MessageDigest.getInstance("SHA-256")
            .apply { updateVacancy(vacancy) }
            .digest()
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun MessageDigest.updateVacancy(vacancy: VacancyDescriptor) {
        updateField("tenantGroupId", vacancy.tenantGroupId)
        updateField("clinicId", vacancy.clinicId)
        updateField("resourceType", vacancy.resourceType)
        updateField("resourceId", vacancy.resourceId)
        updateField("capacityUnits", vacancy.capacityUnits)
        updateField("maximumCapacity", vacancy.maximumCapacity)
        updateField("startsAt", vacancy.startsAt)
        updateField("endsAt", vacancy.endsAt)
        updateField("treatmentTypeId", vacancy.treatmentTypeId)
        updateField("doctorId", vacancy.doctorId)
    }

    private fun MessageDigest.updateField(name: String, value: Any?) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(0)
        if (value == null) {
            update(-1)
        } else {
            val valueBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            update(valueBytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            update(0)
            update(valueBytes)
        }
        update(0)
    }
}
