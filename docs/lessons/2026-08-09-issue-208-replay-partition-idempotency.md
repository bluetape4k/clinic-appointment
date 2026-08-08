# Issue #208 replay partition 멱등성 lesson

## 맥락

현재 구현 보강 과정에서 replay request의 runbook 계약과 `AppointmentReplayService`의
idempotency hash 입력이 서로 다르다는 점을 확인했다. 요청은 선택적인 단일 Kafka
partition을 지원하고 request id를 scope·range·partition에 묶어야 하지만, hash가
partition을 제외하고 있었다.

## 근본 원인

`AppointmentReplayService.requestHash()`가 consumer·stream identity, tenant·clinic,
승인자, offset 범위만 연결하고 `AppointmentReplayRequest.partition`을 연결하지 않았다.
따라서 같은 `requestId`로 partition 1의 replay를 완료한 뒤 partition 2를 지정해도 기존
audit hash와 같다고 판단해 새 범위를 거부하지 않았다. 이 동작은 runbook의
"partition-bound hash" 계약과 달랐고, 승인된 replay의 audit/idempotency 경계를 약화시켰다.

## 결정

- request hash 입력에 `partition`을 포함한다. `null`도 문자열 직렬화에 포함해 전체
  partition 요청과 특정 partition 요청을 서로 다른 scope로 취급한다.
- audit에 `hash_version`과 nullable `partition_number`를 추가해 현재 hash 계약과
  검증된 partition을 함께 보존한다. V25 migration은 기존 row를 `hash_version=1`,
  `partition_number=NULL`로 보존하며, partition 범위를 복원할 수 없는 legacy row는
  새 request id를 요구하는 fail-closed 정책으로 처리한다.
- physical inbox dedup key에는 partition을 추가하지 않는다. replay request의 범위
  binding만 강화하며, event side-effect dedup은 기존 logical identity와 event id 계약을
  유지한다.
- 동일 request id와 다른 partition을 사용하는 회귀 테스트를 production code보다 먼저
  RED로 실행한 뒤 최소 수정으로 GREEN을 확인한다.

## 검증

- 수정 전 회귀 테스트: `Expected java.lang.IllegalArgumentException to be thrown, but
  nothing was thrown`으로 실패했다.
- 수정 후 partition/legacy 회귀를 포함한 `AppointmentReplayServiceTest` 9개가 통과했다.
- `null -> 1` partition 변경과 V1 legacy audit row의 새 request id 요구를 각각 회귀
  테스트로 고정했다.
- H2/PostgreSQL/MySQL V25 migration이 새 metadata, `hash_version=1` default,
  nullable `partition_number` 계약을 각각 검증한다.
- `:appointment-messaging:test` 112개와 readiness 9개, H2 migration 5개,
  PostgreSQL/MySQL V25 migration 각 1개가 통과했다.
- `:appointment-messaging:build`와 `validate-appointment-messaging-ops.py`가 성공했다.

## 미래 guard

1. runbook에 범위 필드가 추가되면 request hash test fixture에 해당 필드를 반드시 포함한다.
2. 선택 필드는 `null`과 구체적인 값을 각각 사용한 same-request-id rejection test로
   고정한다.
3. audit/idempotency 계약을 수정할 때는 문서의 hash 입력 목록과 구현·회귀 테스트를
   같은 변경 단위에서 교차 검토한다.

## 추가 보강

- additive migration은 column/default metadata만 확인하면 기존 audit 값의 보존을
  증명하지 못한다. V25 전 schema에 legacy row를 넣고 migration 후 기존 필드와
  `hash_version=1`/`partition_number=NULL`을 모두 읽어 검증한다.
- 새 hash 계약은 구버전 writer와 mixed-version으로 실행하지 않는다. replay 기능을
  먼저 hold/disable하고 migration·readiness를 확인한 뒤 old node를 drain하고 새
  code를 활성화하는 rollout 순서를 runbook에 고정한다.
