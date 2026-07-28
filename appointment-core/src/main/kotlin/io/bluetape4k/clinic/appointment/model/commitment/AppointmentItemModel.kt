package io.bluetape4k.clinic.appointment.model.commitment

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * 한 방문에서 수행을 시도할 세부 진료 항목 초안입니다.
 *
 * 한 예약은 대표 진료명을 가지면서 여러 항목을 포함할 수 있습니다. 당일 일부 항목을
 * 완료하지 못하면 미완료 항목은 provenance를 유지한 새 방문 item으로 분리되며, 이미
 * 완료된 항목을 되돌리지 않습니다.
 *
 * @property planRevisionId 이 항목을 만든 동일 구매 Plan의 양수 불변 revision 식별자입니다.
 * @property treatmentKey 실행 BOM과 Plan revision 안에서 안정적인 진료 의무 키입니다.
 * @property representativeTreatmentName 방문과 고객 화면에 표시할 대표 진료명입니다.
 * 식별이나 임상 완료 판정에 사용하지 않습니다.
 * @property detailedTreatmentCodes 이 방문에서 수행을 시도할 순서 있는 세부 진료 코드입니다.
 * @property preparationMinutes 항목별 준비 구간입니다. 0 이상이며 패키지 합계 시간으로
 * 덮어쓰지 않습니다.
 * @property treatmentMinutes 실제 진료에 예약한 양수 분 단위 구간입니다.
 * @property recoveryMinutes 항목별 회복 구간입니다. 0 이상이며 자원 점유 계산에서
 * 명시적으로 포함 또는 제외할 근거가 됩니다.
 * @property attemptNumber 같은 계획 의무를 여러 방문에서 시도할 때 1부터 증가하는 번호입니다.
 */
data class AppointmentItemDraft(
    val planRevisionId: Long,
    val treatmentKey: String,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val preparationMinutes: Int,
    val treatmentMinutes: Int,
    val recoveryMinutes: Int,
    val attemptNumber: Int = 1,
) : Serializable {

    init {
        planRevisionId.requirePositiveNumber("planRevisionId")
        treatmentKey.requireNotBlank("treatmentKey")
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")
        preparationMinutes.requireNonNegative("preparationMinutes")
        treatmentMinutes.requirePositiveNumber("treatmentMinutes")
        recoveryMinutes.requireNonNegative("recoveryMinutes")
        attemptNumber.requirePositiveNumber("attemptNumber")
    }

    /** 준비·진료·회복을 모두 포함한 이 항목의 전체 방문 소요시간입니다. */
    val totalDurationMinutes: Int
        get() = preparationMinutes + treatmentMinutes + recoveryMinutes

    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun Int.requireNonNegative(name: String) {
    require(this >= 0) { "$name must be greater than or equal to zero" }
}
