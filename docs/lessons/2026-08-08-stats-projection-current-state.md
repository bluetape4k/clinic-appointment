# 통계 이벤트 projection과 현재 예약 집계의 경계

## 문제

`AppointmentStatsProjectionConsumer`가 이벤트의 `occurredAt` 날짜와 상태를
tenant/clinic/date/status bucket에 누적하면, 같은 예약 aggregate의 상태 변경이
이전 bucket을 그대로 남긴다. 더구나 현재 envelope에는 권위 있는
`appointmentDate`가 없으므로 이 projection row를 `Appointments`의 현재 상태 집계와
동일한 의미로 취급할 수 없다.

## 수정 규칙

- aggregate별 최신 event만 projection bucket에 반영한다.
- V24 aggregate lock row를 먼저 `FOR UPDATE`해 동일 aggregate의 최초 event와
  후속 event도 DB transaction 안에서 직렬화한다.
- 최신 event가 날짜 또는 상태 bucket을 바꾸면 이전 bucket을 1건 감소시키고 새
  bucket을 1건 증가시킨다.
- 이전 bucket이 사라진 불변식 위반은 조용히 성공시키지 않고 transaction 실패로
  표면화해 retry/repair 신호로 남긴다.
- 대시보드의 current-state 집계는 `Appointments.appointmentDate`와
  `Appointments.status`를 읽는 기존 repository를 권위 저장소로 유지한다.
- event projection에 행이 있다는 사실만으로 대시보드 집계를 대체하지 않는다.
  `appointmentDate`와 completeness를 증명하는 별도 read model이 준비된 뒤에만
  대체 경로를 재검토한다.
- consumer가 활성화된 환경의 readiness validator는 projection bucket, event ledger,
  V24 aggregate lock table까지 모두 확인한다. Flyway를 우회하는 로컬 schema-init은
  messaging consumer 운영 계약을 대신하지 않으며, 필요한 migration이 없으면
  startup을 fail-closed한다.

## 재발 방지 검증

- 동일 aggregate의 상태·이벤트 날짜 변경 테스트는 이전 bucket이 비워지고 최신
  bucket만 남는지 확인한다.
- projection row가 의도적으로 현재 DB 집계와 다를 때도 대시보드가 현재 예약 row를
  반환하는 격리 테스트를 유지한다.
- 동시 event pair가 같은 aggregate를 처리해도 최신 bucket 하나만 남는지
  `MultithreadingTester` 회귀 테스트로 확인한다.

## 범위 경계

이 규칙은 Issue #17 P1-01 통계 projection 의미 오류와 aggregate 경합 보강에 대한
수정이다. replay/inbox 격리, partition fencing, 실제 broker·registry 운영 검증은
별도 작업으로 남긴다. V24 lock table의 production 적용과 backfill은 운영 변경 창에서
별도 확인한다.
