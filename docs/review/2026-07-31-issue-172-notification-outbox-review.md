# Issue #172 알림 아웃박스 최종 코드 검토

## 판정

알림 아웃박스 구현은 코드 병합 기준 `P0=0`, `P1=0`이다. 예약과 알림 의도의
원자성, 개인정보 최소화, lease fencing, 제한된 재시도, 다중 DB 실행 계획,
운영 관측과 병원별 rollout 경계를 구현과 자동화 검증으로 확인했다.

운영 `ACTIVE` 전환은 병합과 별도 gate이다. 기본 `SHADOW`를 유지하고, 후속 이슈
#204에서 병원 1곳의 24시간·1,000건 CANARY, critical alert 0건, 중복·unknown 0건을
확인하기 전에는 전체 병원으로 확대하지 않는다.

## 검토 범위

- `appointment-core`: 예약의 `MemberId` 해석과 영속 경계
- `appointment-event`: 개인정보 없는 outbox 계약, caller transaction repository,
  lease·attempt·종료 비식별화
- `appointment-notification`: 공정 dispatcher, 회원 조회, template 렌더링,
  provider timeout·회로 차단, metric·health·retention·rollout
- `appointment-api`: 예약 command 연결, 운영 API·객체 권한, OpenAPI와 세 dialect V14
- README 한·영, 요구사항, 운영 runbook, 한·영 light/dark 다이어그램

## 7-Tier 결과

| Tier | 검토 내용 | 결과 |
|---|---|---|
| 1. 업무 계약 | 예약 변경과 알림 의도의 원자 기록, 현재 회원 profile·동의 반영, legacy 억제 | 통과 |
| 2. 아키텍처·경계 | 예약 서비스는 `memberId`와 닫힌 parameter만 소유하고 회원·알림 서비스가 수신자 정보와 본문을 보강 | 통과 |
| 3. 데이터·트랜잭션 | caller-owned Exposed transaction, HMAC 멱등성, DB 시각 lease, fencing, 종료 상태와 비식별화의 단일 update | 통과 |
| 4. API·보안 | clinic scope, 운영 capability, 이중 승인 `re-notify`, 개인정보 없는 health·metric·오류 계약 | 통과 |
| 5. 테스트·호환성 | H2/PostgreSQL/MySQL migration·claim·retention, 소비 모듈 ABI, OpenAPI 게시 결과 | 통과 |
| 6. 성능·운영 | 관측 poll 분리·10,001건 상한, bounded executor, 채널별 timeout, 20,000건 backlog와 starvation 0 | 병합 통과, 운영 `ACTIVE` 보류 |
| 7. 사용자·문서 | 신규 예약 `memberId` 필수, legacy 전환 계약, 한·영 README와 light/dark 시각 자료 | 통과 |

## 독립 검토 수렴

| 관점 | 최초 finding | 최종 판정 | 주요 반영 |
|---|---|---|---|
| 성능 | P0=0, P1=2, P2=2, P3=0 | P0=0, P1=0, P2=0, P3=0 | 공정 claim index, 관측 split·상한, 실행 가능한 부하 예산 |
| 안정성 | P0=0, P1=2, P2=3, P3=1 | P0=0, P1=0, P2=0, P3=0 | schema readiness, crash 복구, direct executor 포화 격리, 채널별 timeout wiring 테스트 |
| 보안·개인정보 | P0=0, P1=2, P2=3, P3=2 | P0=0, P1=0 | 연락처·본문 미영속, crypto fail-closed, 운영 API 객체 권한, source scan |
| 운영 | P0=0, P1=2, P2=4, P3=2 | P0=0, P1=0, P2=0, P3=0 | 실제 `backlogCapped` health detail, retention·key revoke·rollback 절차 |
| 개발자·API | P0=0, P1=1, P2=2, P3=0 | P0=0, P1=0 | `MemberId` 소유권, 안정적인 503, OpenAPI 항목·문자열 경계 |
| 사용자·caller | P0=0, P1=2, P2=2, P3=1 | P0=0, P1=0 | 신규·legacy 입력 경계, 예약 thread provider I/O 차단, 오류 matrix |

## 검토에서 발견해 수정한 항목

### 관측 조회가 worker poll을 잠식하는 문제

발송 worker의 1초 poll에서 대규모 backlog를 매번 객체화하던 경로를 분리했다.
관측은 기본 10초 주기와 10,001건 상한을 사용하고, 상한 도달 여부를
`backlogCapped`와 `degraded`로 함께 노출한다. clinic·member 식별자는 metric tag와
health detail에 포함하지 않는다.

### 전환기 executor 포화가 예약 thread로 전파되는 문제

`CallerRunsPolicy`를 제거하고 bounded queue의 `AbortPolicy`를 사용한다. 포화된 direct
작업은 호출자 thread에서 provider I/O를 수행하지 않고, 이미 커밋된 outbox 행을
pending으로 남겨 worker가 복구한다. queue 포화 회귀 테스트가 예약 event listener의
비차단 계약을 고정한다.

### 채널별 timeout이 실제 bean에 전달되지 않는 문제

`channels.<채널 유형 소문자>.provider-timeout`을 실제 `NotificationChannel.channelType`으로
해석하고, 값이 없을 때만 전역 `worker.provider-timeout`을 사용한다. Spring
auto-configuration 테스트는 전역 2초, SMS 25ms를 설정하고 실제 decorated provider
호출이 500ms 안에 typed timeout으로 끝나는지 확인한다.

### OpenAPI annotation과 게시 schema의 차이

요청 DTO annotation만 검사하지 않고 실제 `/v3/api-docs`를 검증한다. `appointmentIds`는
1~100개, 중복 금지, 각 ID 양수이며, generation과 승인 참조 문자열의 길이·형식도 게시
schema와 controller 경계에서 함께 고정했다.

## 검증 증거

- `:appointment-core:build :appointment-event:build :appointment-notification:build
  :appointment-api:build`: 총 1,347개 테스트, 실패·오류 0건, 2개 보류
- 채널별 timeout auto-configuration: 9개 테스트 통과
- Actuator health 어댑터: readiness DOWN과 privacy-safe `backlogCapped` detail 테스트 통과
- H2·PostgreSQL·MySQL V14 lifecycle과 대표 claim 실행 계획: 통과
- 100개 병원·20,000건 backlog 합성 부하 3회: 모두 20,000건 처리,
  starvation 0, poll 최대 100행, working set 최대 200, p95 14ms, p99 23ms
- Gatling SMOKE: 4/4 성공, 실패 0건, p95 52ms
- README companion, endpoint, connector, geometry, sequence 다이어그램 감사와
  `git diff --check`, gitleaks: 통과

상세 설계는
`docs/superpowers/specs/2026-07-31-issue-172-notification-outbox-design.md`, 실행·검증
추적은 `docs/superpowers/plans/2026-07-31-issue-172-notification-outbox-plan.md`, 운영
전환 절차는 `docs/runbooks/notification-outbox-operations.md`를 기준 문서로 삼는다.

## 잔여 운영 gate

- staging V14 DDL의 실제 행 수, lock wait, rollback 판단 기록
- 병원 1곳에서 24시간·1,000건 CANARY와 critical alert 0건 확인
- `backlogCapped=true` 강제 fixture의 실제 Actuator 응답 확인
- 실제 provider 처리량·native connect/read/request timeout 확인
- CANARY exit criteria 충족 뒤 `ACTIVE` 전환과 direct listener 제거

위 항목은 후속 이슈 #204에서 추적한다. 코드 병합은 차단하지 않지만 운영
`ACTIVE` 활성화는 차단한다.
