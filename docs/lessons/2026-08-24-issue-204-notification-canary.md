# Issue #204 production-like canary 검증 lesson

## 맥락

기존 readiness 검증은 route·schema·timeout·health 계약과 migration inventory를
확인했지만, fixed-window workload에서 rollback 시 queue가 보존되는지와 claim/retry/
fencing 결과를 한 번에 재현하지 못했습니다.

## 결정

production source를 건드리지 않고 `appointment-api` 통합 테스트에 bounded 1,000건
simulation을 추가했습니다. PostgreSQL·Redis·Kafka는 repository singleton launcher를
재사용하고, provider는 deterministic stub으로 대체했습니다. report는
`productionSloEvidence=false`, `productionClaim=false`를 필수 필드로 두고 원문
payload·destination·credential를 저장하지 않도록 validator가 fail closed하게 했습니다.

## 결과와 검증

고정 sequence `SHADOW → ACTIVE_SIMULATED → PAUSED → SHADOW`에서 pause 중 provider
호출 0과 non-terminal queue 보존을 확인했습니다. 1,000건이 `SENT`로 종결됐고 retry
1건, lease fencing 1건, duplicate accepted result 0건, Kafka lag 0건, Redis leaked key
0건을 기록했습니다. 최종 `SHADOW` route assertion을 포함해 validator 2건과
container-backed simulation 1건, 총 `3 passing`이며 report assertions는 `18/18`입니다.

## 놓치기 쉬운 점

`production-like`라는 이름만으로 실제 SLO나 rollout 승인을 의미하지 않습니다. 실제
provider 처리량, staging DDL lock/query plan, 24시간 cohort, owner 승인과 stabilization
window는 여전히 별도 증거입니다. 또한 retry를 테스트할 때는 다음 dispatch가 즉시
재현되도록 retry wait를 명시적으로 backdate해야 하며, 그렇지 않으면 bounded drain이
대기 상태에서 끝난 것으로 오해할 수 있습니다.

## 다음 작업자가 지킬 점

1. test source의 fixed seed와 `MAX_DRAIN_ROUNDS`를 임의로 늘려 운영 완료를 가장하지
   말고, workload·threshold 변경은 Issue #204의 승인 범위로 다시 분류합니다.
2. report에 raw destination, member ID, rendered body, credential, lease token을 추가하지
   말고 validator의 forbidden-field 계약을 유지합니다.
3. 실제 rollout을 진행할 때는 이 simulation report를 staging/production evidence로
   승격하지 말고, 외부 운영 증거와 owner approval을 별도로 등록합니다.
