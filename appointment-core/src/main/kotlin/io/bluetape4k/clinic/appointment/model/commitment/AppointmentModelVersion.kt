package io.bluetape4k.clinic.appointment.model.commitment

/**
 * 하나의 `scheduling_appointments` 테이블에서 구 예약과 commitment 기반 예약을 구분하는 모델 버전입니다.
 *
 * [LEGACY] row는 기존 API가 요구하는 의사·진료 유형·일자·시작/종료 시각을 항상 가진다.
 * [COMMITMENT_V2] row는 고객 요청 직후 아직 확정 proposal이 없을 수 있으므로 해당 projection이
 * 비어 있을 수 있다. 확정 transaction이 선택된 proposal을 projection에 반영한 뒤에만 기존 예약
 * 조회 경로에 노출된다.
 */
enum class AppointmentModelVersion {
    /** 기존 단일 예약 생성·조회 계약을 따르는 row입니다. */
    LEGACY,

    /** proposal과 고객 동의를 거쳐 확정되는 방문 commitment row입니다. */
    COMMITMENT_V2,
}
