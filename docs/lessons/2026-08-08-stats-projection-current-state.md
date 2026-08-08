# 통계 이벤트 projection과 현재 예약 집계의 경계

## 문제

`AppointmentStatsProjectionConsumer`가 이벤트의 `occurredAt` 날짜와 상태를
tenant/clinic/date/status bucket에 누적하면, 같은 예약 aggregate의 상태 변경이
이전 bucket을 그대로 남긴다. 더구나 현재 envelope에는 권위 있는
`appointmentDate`가 없으므로 이 projection row를 `Appointments`의 현재 상태 집계와
동일한 의미로 취급할 수 없다.

## 수정 규칙

- aggregate별 최신 event만 projection bucket에 반영한다.
- 최신 event가 날짜 또는 상태 bucket을 바꾸면 이전 bucket을 1건 감소시키고 새
  bucket을 1건 증가시킨다.
- 대시보드의 current-state 집계는 `Appointments.appointmentDate`와
  `Appointments.status`를 읽는 기존 repository를 권위 저장소로 유지한다.
- event projection에 행이 있다는 사실만으로 대시보드 집계를 대체하지 않는다.
  `appointmentDate`와 completeness를 증명하는 별도 read model이 준비된 뒤에만
  대체 경로를 재검토한다.

## 재발 방지 검증

- 동일 aggregate의 상태·이벤트 날짜 변경 테스트는 이전 bucket이 비워지고 최신
  bucket만 남는지 확인한다.
- projection row가 의도적으로 현재 DB 집계와 다를 때도 대시보드가 현재 예약 row를
  반환하는 격리 테스트를 유지한다.

## 범위 경계

이 규칙은 Issue #17 P1-01 통계 projection 의미 오류에 대한 수정이다. replay/inbox
격리, partition fencing, 실제 broker·registry 운영 검증은 별도 작업으로 남긴다.
