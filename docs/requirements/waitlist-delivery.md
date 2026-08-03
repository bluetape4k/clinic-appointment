# Waitlist 전달 요구사항

## 목적

당일 확정 예약이 `CANCELLED` 또는 `NO_SHOW`가 되면 병원 범위 안의 호환 대기 후보를
결정적으로 선택해 제한 시간 offer를 만든다. 하나의 offer는 최대 하나의 replacement
appointment로 확정되어야 하며, 반복 요청·worker 재시작·알림 장애가 상태 일관성을 깨뜨리면
안 된다.

## 권위와 경계

- tenant, clinic, treatment, doctor, 시간창 hard eligibility는 점수 계산보다 먼저 검사한다.
- urgency, disruption recovery credit, 승인된 benefit grant, attendance tier, waiting age,
  slot fit, 명시적 override 순서와 policy version/digest를 offer decision에 저장한다.
- 이름·연락처·clinical note는 waitlist/event/outbox에 복제하지 않고 opaque reference만 쓴다.
- Redis leader는 scheduler 중복을 줄일 뿐이다. offer/hold/appointment terminal write는 DB
  fence와 CAS가 권위다.
- Redis leader를 잃거나 delivery를 rollback해도 expiry, suppression, stuck-hold recovery는
  계속 실행한다.

## 상태와 원자성

`WAITING -> OFFERED -> ACCEPTED | DECLINED | EXPIRED | WITHDRAWN` 상태를 사용한다.
vacancy generation, offer, capacity hold, decision audit, notification draft, command result는
정해진 lock 순서와 Exposed `transaction {}` 경계 안에서 기록한다. `PROCESSING` command가
appointment 생성 직후 남으면 다음 요청이 appointment reference를 재조회해 `SUCCEEDED`로
닫고, 생성되지 않았으면 안정된 `FAILED` replay로 닫는다.

## API와 rollout

직원 API는 `/api/{tenantCode}/clinics/{clinicId}/waitlist` 아래에 있고 모든 mutation은
16~128자 ASCII `Idempotency-Key`와 필요한 `expectedVersion`을 요구한다. public reference는
versioned opaque string이며 교차 scope reference는 `404`로 숨긴다. 기본
`appointment.waitlist.delivery.enabled=false`, `clinic-allowlist`는 점진 활성화 목록이다.

정량 기준은 clinic당 active entry 10,000, pending vacancy 1,000, notification backlog 5,000
환경에서 vacancy 300건/분 이상, 최초 offer p95 2초 이하, DB lock wait p99 500ms 이하,
재시작 backlog catch-up 10분 이하이다. 실 DB 성능 측정은 staging fixture에서 별도로 실행한다.
