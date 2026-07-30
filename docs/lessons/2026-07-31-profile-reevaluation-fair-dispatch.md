# 프로필 재평가 작업의 공정한 분배와 실패 예산 관리

## 배경

프로필 변경 예약 재평가는 한 환자의 활성 예약만 다루지만, 대형 병원에서는 같은
시각에 수천 건이 쌓일 수 있다. 초기 구현은 clinic별 처리 상한을 두었어도 전역 후보를
먼저 잘라낸 뒤 분배했기 때문에 앞쪽 clinic이 계속 선택될 수 있었다. 또한 운영 모드나
처리 목표 때문에 잠시 미룬 작업을 실패 재시도와 같은 경로로 돌리면 실제 장애가
없어도 재시도 예산이 소진됐다.

## 1. 운영상 지연과 실패 재시도는 다른 상태 전이다

worker가 작업을 claim하면 `attemptCount`가 증가한다. 이 상태에서 dry-run,
`PROPOSED_ONLY`, clinic별 동시성 제한, tick 예산 같은 운영 조건 때문에 작업을
처리하지 못한 경우는 실행 실패가 아니다.

- 기술 실패는 `retry()`로 보내고 backoff와 실패 예산을 적용한다.
- 운영상 지연은 `defer()`로 보내 claim에서 증가한 `attemptCount`를 되돌린다.
- 두 경로 모두 lease를 해제하지만, 실패 원인과 운영 제어를 같은 지표로 합치지 않는다.

반복 defer 테스트는 작업이 여러 번 미뤄져도 최종 실패 상태로 전이하지 않는지 직접
검증해야 한다. 단순히 다음 실행 시각만 확인하면 실패 예산 소진을 놓칠 수 있다.

## 2. clinic별 상한만으로는 전역 공정성이 보장되지 않는다

`LIMIT globalConcurrency`로 전역 후보를 먼저 읽고 clinic별 개수를 제한하면, 정렬
앞쪽에 있는 clinic 수가 전역 제한보다 많을 때 뒤쪽 clinic은 영구적으로 보이지 않는다.
이번 구현은 dispatcher가 마지막으로 처리한 `(tenantGroupId, clinicId)`를 커서로
보존하고 다음 tick에서 그 뒤 clinic부터 찾는다.

1. clinic key를 keyset 조건과 `LIMIT 1`로 찾는다.
2. clinic별로 `PENDING`, `RETRY_WAIT`, lease가 만료된 `RUNNING` 후보를 제한된 수만
   조회한다.
3. 전역 상한에 도달하거나 더 이상 clinic이 없으면 종료한다.
4. 끝에 도달하면 이전 커서 앞쪽으로 한 번만 순환한다.

커서는 처리 건수나 환자 수가 아니라 clinic 순서를 전진시킨다. 따라서 한 병원의
backlog가 커져도 다른 병원이 다음 tick에서 선택될 기회를 잃지 않는다.

## 3. 공정성 쿼리는 실제 SQL과 실행계획으로 검증한다

전체 clinic을 `GROUP BY`와 `MIN`으로 집계하면 결과 행 수는 작아도 매 tick마다 큰
backlog를 읽을 수 있다. Exposed DSL 테스트와 H2 실행만으로는 PostgreSQL·MySQL
optimizer가 운영 경로에서 어떤 index를 고르는지 증명할 수 없다.

이번 구현은 다음 두 복합 index를 Flyway와 Exposed table에 같은 이름과 열 순서로
정의했다.

- `idx_profile_reevaluation_clinic_ready`
- `idx_profile_reevaluation_clinic_lease`

PostgreSQL과 MySQL의 `EXPLAIN` 테스트는 다음 실제 쿼리 형태를 각각 실행한다.

- 다음 clinic keyset 조회
- `PENDING` 후보 조회
- `RETRY_WAIT` 후보 조회
- lease가 만료된 `RUNNING` 후보 조회

검증 기준은 index 이름 문자열의 존재가 아니라 전용 index 사용과 full scan 부재다.
fixture도 한 clinic에만 몰지 않고 여러 clinic과 세 상태에 분산해야 공정성 경로를
실제로 통과한다.

## 4. 메모리상 커서는 처리량 최적화이며 안전성 권위가 아니다

dispatcher 커서는 프로세스 재시작 시 초기화될 수 있다. 그래도 안전성은 DB의 상태,
lease owner, revision CAS가 보장하므로 중복 mutation이나 오래된 결과 덮어쓰기로
이어지지 않는다. 재시작 뒤 순환 순서가 처음부터 다시 시작되는 것은 허용 가능한
처리량 저하다.

커서 갱신과 claim 호출은 같은 `Mutex` 구간에서 직렬화해 한 인스턴스의 중첩 tick이
같은 커서를 읽지 않도록 한다. 여러 인스턴스 사이의 중복 claim 방지는 메모리 커서가
아니라 DB lease가 담당한다.

## 재사용 지침

- 운영 모드, rate limit, tick 예산으로 미룬 작업은 실패 재시도 예산과 분리한다.
- 공정성은 “그룹별 최대 N건”뿐 아니라 그룹 선택 순서가 tick 사이에 전진하는지
  검증한다.
- 대규모 backlog 경로에서 전체 `GROUP BY`, 전체 정렬, 전체 materialization을 피하고
  keyset과 제한된 후보 조회를 결합한다.
- 지원 다이얼렉트마다 실제 운영 SQL의 `EXPLAIN`을 실행하고 full scan 부재를 확인한다.
- dispatcher의 메모리 상태는 처리량 보조 수단으로만 사용하고, 정확성과 중복 방지는
  DB의 lease·CAS 계약에 둔다.
