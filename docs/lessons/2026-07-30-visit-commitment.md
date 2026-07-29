# 방문 예약 확정 lifecycle 구현 교훈

## 배경

Issue #184는 반복 시술과 복합 패키지의 실행 BOM을 여러 방문으로 전개하고,
고객 가예약·병원 승인·고객 동의·자원 점유를 하나의 commitment lifecycle로
관리했다. 단순한 CRUD가 아니라 정책 snapshot, immutable proposal revision,
의료진·장비·공간의 동시 점유, 외부 fact 수렴, 부분 이행 이후 future-only 변경을
같이 보존해야 했다.

## 1. MySQL 잠금 조회는 snapshot read와 구분한다

MySQL의 기본 `REPEATABLE READ` transaction에서 먼저 일반 조회를 수행한 뒤 mutex
row를 만들고 다시 일반 조회하면, transaction snapshot 때문에 방금 생성된 row를
보지 못할 수 있다. 자원 선점처럼 현재 상태가 필요한 경로는 mutex row 생성 이후
locking current read로 다시 읽어야 한다.

- resource key를 정렬해 canonical order로 잠근다.
- 최초 사용 자원도 mutex row를 생성한 뒤 같은 transaction에서 locking read한다.
- PostgreSQL의 `NOWAIT`와 MySQL의 current read 차이를 repository 경계에서 흡수하고,
  application에는 같은 `RESOURCE_CONFLICT` 계약을 제공한다.

## 2. 잠금 완료 상태는 boolean이 아니라 opaque token으로 전달한다

`alreadyLocked=true` 같은 boolean은 호출자가 실제 잠금을 획득하지 않고도 검증을
우회할 수 있다. 이번 구현에서는 repository만 만들 수 있는
`LockedResourceAvailability`를 사용해 잠금 획득과 후속 allocation 교체를 결합했다.

- 일반 호출은 항상 resource availability를 직접 검증한다.
- 잠금 경로만 opaque token을 전달해 중복 잠금을 피한다.
- 관리자 승인과 direct confirmation도 같은 점유 검증을 거친다.

이 패턴은 자원 잠금뿐 아니라 권한 검증, optimistic version 확인처럼
“앞 단계가 끝났음”을 전달하는 내부 API에도 적용할 수 있다.

## 3. production wiring은 interface와 단위 테스트만으로 증명되지 않는다

controller test가 mock service로 통과해도 실제 Spring configuration에 application
service bean이 없으면 feature flag를 켠 순간 시작 또는 요청 처리가 실패한다.
운영 metric도 facade와 단위 테스트만 있으면 관측 가능한 것이 아니다.

- controller에서 시작해 실제 bean graph를 구성하는 wiring test를 둔다.
- request DTO에서 만들 수 없는 고객 identity와 실제 inventory는 명시적인
  server-side resolver 계약으로 분리하고, adapter가 없으면 값을 합성하지 않고
  fail-closed한다.
- 정책은 `EffectiveSchedulingPolicyService`와 영속 snapshot row를 결합해 command
  FK에 사용한다.
- metric은 실제 command, retention, event 처리 경로에서 기록되는지 검증한다.

## 4. redrive 성공은 handler 성공 뒤에만 기록한다

격리 row를 먼저 `RELEASED`로 바꾼 뒤 handler를 호출하면 handler 실패 시 복구할
근거가 사라진다. redrive는 attempt와 최종 결과를 분리해야 한다.

1. 원본 `eventId`와 envelope hash가 격리 row와 정확히 일치하는지 확인한다.
2. append-only audit에 redrive attempt를 기록한다.
3. handler가 성공한 뒤에만 quarantine을 `RELEASED`로 전이하고 success를 기록한다.
4. 실패하면 quarantine 상태를 유지하고 failure audit을 남긴다.

큰 payload는 암호화 전에 상한을 검사한다. 상한을 넘은 원문은 저장하지 않고
streaming SHA-256 증거만 보존해 메모리 증폭과 민감정보 잔류를 막는다.

## 5. retention 기준 시각은 상태와 별도 열로 보존한다

`RELEASED` 상태만 보고 `detected_at`으로 90일을 계산하면 오래 대기하다 방금
해결된 quarantine이 즉시 삭제될 수 있다. V11은 nullable `resolved_at`을 추가하고
해결 전이의 CAS와 같은 transaction에서 기록한다.

- 기존 row의 해결 시각은 추정해 backfill하지 않는다.
- `resolved_at IS NULL`인 legacy row는 자동 삭제하지 않는다.
- `legal_hold=true`, 미해결 quarantine, 미전달 outbox는 retention 대상에서 제외한다.
- scheduler는 단일 owner로 실행하고 tenant별 batch 상한을 적용한다.

## 재사용 지침

- DB concurrency 기능은 H2 GREEN으로 끝내지 말고 PostgreSQL과 MySQL의 실제 격리
  수준 및 locking read를 각각 검증한다.
- package 최대 크기 검증은 mapping 뒤가 아니라 canonical hash와 중첩 collection
  순회 전에 수행한다.
- 운영 문서의 flag 이름과 rollback 순서는 실제 configuration property를 그대로
  사용한다.
- 최종 review에서는 코드 존재 여부뿐 아니라 production caller가 실제 bean graph와
  adapter를 통해 기능을 사용할 수 있는지 확인한다.
