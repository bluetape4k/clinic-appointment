package io.bluetape4k.clinic.appointment.solver.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * nullable planning 값이 모두 할당된 경우에만 [block]을 실행합니다.
 *
 * Timefold의 부분 초기화 entity는 유효한 solver 입력이지만, 완전한 예약 결과나
 * 제약식 predicate로 사용할 수는 없습니다. 값이 하나라도 빠진 상태에서는 기본값을
 * 만들지 않고 `null`을 반환합니다.
 */
internal inline fun <T> AppointmentPlanning.withAssigned(
    block: (doctorId: Long, appointmentDate: LocalDate, startTime: LocalTime, endTime: LocalTime) -> T,
): T? {
    val doctorId = this.doctorId ?: return null
    val appointmentDate = this.appointmentDate ?: return null
    val startTime = this.startTime ?: return null
    val endTime = this.endTime ?: return null
    return block(doctorId, appointmentDate, startTime, endTime)
}
