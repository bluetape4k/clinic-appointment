# 실시간 consumer의 tenant/clinic scope 권한 검증

## 배경

Issue #331에서 live appointment consumer가 envelope의 `tenantGroupId`와
`clinicId`를 현재 기준 데이터와 대조하지 않는 문제를 수정했다. replay 경로는
호출자가 지정한 `expectedScope`를 이미 검사했지만, live 경로는 이 값을
`AppointmentConsumerProvenance`에 그대로 복사한 뒤 inbox와 handler를 실행했다.

## 원인과 결정

provenance는 record의 출처를 기록할 뿐 scope의 소유권을 증명하지 않는다. 따라서
존재하지 않는 clinic, 다른 tenant에 속한 clinic, 임의로 만든 tenant/clinic 조합이
정상 이벤트처럼 projection이나 외부 side effect까지 도달할 수 있었다.

`AppointmentConsumerScopeAuthority`를 runtime의 필수 의존성으로 두고, schema 검증과
inbox 획득 사이에서 live record만 현재 권한을 조회하도록 했다. 기본 구현은
`DatabaseAppointmentConsumerScopeAuthority`이며 `ClinicRepository.findByIdAndTenant`
를 Exposed `transaction(database)` 안에서 호출한다. 조회 결과가 `false`이면 기존
`SCOPE_MISMATCH` quarantine/ack/metric 경계를 재사용하고, 데이터베이스 예외는
quarantine하지 않고 broker redelivery 대상으로 전파한다. replay는 기존
`expectedScope` 검사를 계속 우선 적용한다.

## 검증

- unknown clinic, 다른 tenant의 clinic, forged tenant/clinic scope가 handler 전에
  `SCOPE_MISMATCH`로 quarantine되는 runtime 회귀 테스트를 추가했다.
- PostgreSQL singleton launcher로 실제 `scheduling_tenant_groups`와
  `scheduling_clinics` ownership query를 검증했다.
- Spring auto-configuration이 Database와 `consumer.enabled=true` 조건을 함께 만족할
  때만 독립 scope authority를 등록하는 wiring 테스트를 추가했다.

## 놓친 점

기존 테스트는 partition key와 replay scope만 검사했고, live provenance가 실제
tenant/clinic 소유권을 확인하는지 검증하지 않았다. provenance equality 검사가
있다는 사실을 독립 authority 검증으로 오해하면 같은 문제가 다시 생긴다.

## 다음 guard

새 live consumer runtime은 반드시 `AppointmentConsumerScopeAuthority`를 주입하고,
scope 검증을 inbox 획득과 handler 실행보다 앞에 둔다. provenance는 감사용 출처
정보로만 취급하며 권한 근거로 사용하지 않는다. scope authority 조회 장애는
quarantine이 아니라 재시도로 남겨 데이터베이스 일시 장애가 데이터 손실로 바뀌지
않게 한다.
