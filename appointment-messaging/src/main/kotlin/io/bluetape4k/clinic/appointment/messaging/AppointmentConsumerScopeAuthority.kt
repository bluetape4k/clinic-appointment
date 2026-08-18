package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * live consumer가 envelope의 tenant/clinic 범위를 현재 권한 데이터와 대조하는 경계입니다.
 *
 * `false`는 record를 [AppointmentConsumerFailureCode.SCOPE_MISMATCH]로 quarantine해야
 * 한다는 뜻입니다. 구현체가 데이터베이스 장애를 만난 경우에는 예외를 호출자에게
 * 전파하여 broker redelivery가 일어나도록 해야 합니다.
 */
fun interface AppointmentConsumerScopeAuthority {
    fun isAuthorized(tenantGroupId: Long, clinicId: Long): Boolean
}

/** Exposed clinic ownership query를 이용하는 기본 live consumer authority입니다. */
class DatabaseAppointmentConsumerScopeAuthority(
    private val database: Database,
    private val clinicRepository: ClinicRepository = ClinicRepository(),
) : AppointmentConsumerScopeAuthority {
    override fun isAuthorized(tenantGroupId: Long, clinicId: Long): Boolean =
        transaction(database) {
            clinicRepository.findByIdAndTenant(clinicId = clinicId, tenantGroupId = tenantGroupId) != null
        }
}
