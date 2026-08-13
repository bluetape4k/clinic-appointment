# Issue #34 환자 예약 약속 구현 7-tier code review

## 결론

코드 기준 exact head `bd07645f19d53008e1404a2cfd20cde17975e04c`는
이전 독립 검토에서 확인된 benchmark 증거 계약의 차단 항목을 모두 닫았다.
취소 부하의 warm-up과 측정 구간을 분리하고, monotonic clock으로 실제 측정 시간을
검증하며, sampler 종료를 기다린 뒤 artifact를 확정한다. 환경 fingerprint는
`pauseMillis`를 포함한 canonical JSON의 SHA-256으로 만들고 comparator와 chart
generator가 독립적으로 다시 계산한다. 여러 run의 누적은 Jackson tree로 JSON을
파싱해 mode, source commit, 환경, fingerprint, run 번호를 검증한 뒤 구조적으로
추가한다.

코드·계약 검토 상태는 `CLEAR`다. 다만 정식 baseline/candidate 각 3회 실행,
그 결과로 만든 실측 chart/PNG, 보호된 backend E2E, production rollout 증거는 아직
없다. 유효한 변경 전 baseline 없이 같은 코드를 baseline과 candidate로 실행하면
성능 회귀 증거가 아니므로 이를 생성하지 않았다. 따라서 Issue #34의 전체 DoD와
PR merge 상태는 `PENDING`이다.

- 검토 브랜치: `feat/issue-34-patient-commitment`
- 코드 검토 head: `bd07645f19d53008e1404a2cfd20cde17975e04c`
- 기준 브랜치: `develop`
- 범위: 환자 code-only 취소, ADMIN/STAFF 등록 상세, snapshot·알림 v1/v2,
  Angular portal 취소 흐름, migration·보안·운영·benchmark 증거 계약
- 제외: Issue #305 환자 취소 이력 조회·감사 UI

## 7-tier 판정

| Tier | 검토 내용 | 판정 |
|---|---|---|
| 1. 구조·의존성 | reason registry는 `appointment-core`에 있고 API/event/notification이 같은 계약을 사용한다. Gatling JSON 처리는 기존 version catalog의 Jackson 3을 명시적으로 사용한다. | PASS |
| 2. 보안·개인정보 | PATIENT detail 차단, ADMIN/STAFF 등록값 allow-list, tenant·clinic·patient ownership 재검증, durable payload redaction, 로그인 주체 전환 시 client state 폐기를 확인했다. | PASS; production ACL·backup·provider log는 PENDING |
| 3. API·도메인 | 폐쇄 reason code, ETag/idempotency, terminal transition, code-only 환자 확인 계약이 DTO·command·OpenAPI·frontend에 일치한다. | PASS |
| 4. 데이터·트랜잭션 | V27 세 dialect migration과 Flyway 비활성 schema가 cancellation snapshot을 포함하고, 상태 전환·audit·outbox가 같은 transaction 경계에 있다. | PASS |
| 5. 이벤트·알림 | v1/v2 dual-read, cancellation template v1/v2, row/envelope/template identity, default-off producer readiness와 legacy recovery 경계를 확인했다. | PASS; 정식 mixed-schema benchmark는 PENDING |
| 6. 포털·접근성 | 취소 confirmation, 412 single-flight, 세션 generation, stale 응답 차단, busy/stale mutation 결과, 새로고침 복구 계약을 확인했다. | PASS; protected-backend E2E와 AT matrix는 PENDING |
| 7. 테스트·운영·성능 | 고정 환경·monotonic 측정 구간·lock sampler·환경 fingerprint·구조적 multi-run artifact 계약과 fail-closed comparator/chart를 확인했다. | 코드 PASS; 정식 실측과 운영 증거는 PENDING |

## 독립 검토에서 닫힌 항목

| 이전 지적 | 현재 구현 |
|---|---|
| warm-up 표본이 측정 결과에 섞일 수 있음 | 측정 phase 진입 시 관측치를 초기화하고, 측정 구간만 latency/error/lock-wait에 포함한다. |
| 측정 시간이 wall clock과 traffic 시작 지연에 의존 | phase barrier와 `System.nanoTime()` span을 사용하고 최소 측정 시간을 comparator/chart가 강제한다. |
| sampler 종료 이후 trailing query 가능 | sampling을 중지한 뒤 bounded `Future.get`으로 종료를 확인하고 종료 시각을 기록한다. JDBC query timeout과 nested cleanup도 적용한다. |
| smoke 환경도 baseline/candidate가 같으면 통과 | dataset 100, warm-up 30초, 측정 300초, concurrency 10/20, pause 182ms 등 절대 환경 계약을 comparator와 chart가 함께 강제한다. |
| run별 source/environment provenance가 불완전 | source commit과 전체 canonical environment를 run마다 확인하고 SHA-256 fingerprint를 독립 재계산한다. |
| 정규식과 마지막 `]` 위치로 run JSON을 삽입 | Jackson `ObjectNode`/`ArrayNode`로 report와 run을 파싱·검증하고 배열에 구조적으로 추가한다. |

## 최신 검증 증거

| 검증 | 결과 |
|---|---|
| `node --test tests/benchmarks/appointment-messaging-benchmark-scripts.test.mjs tests/benchmarks/issue34-benchmark-chart.test.mjs` | 29/29 PASS |
| `./gradlew :appointment-api:compileGatlingKotlin --no-daemon` | BUILD SUCCESSFUL |
| PostgreSQL cancel Gatling 단일 smoke | KO 0, lock-wait sampling failure 0, fingerprint 독립 재계산 일치 |
| PostgreSQL cancel Gatling run 1·2 append smoke | 두 run 구조적 누적, KO 0, source/environment/fingerprint 일치 |
| `./gradlew build -x test -x :frontend:appointment-frontend:build --parallel` | 이전 benchmark repair head에서 BUILD SUCCESSFUL; 최종 exact-head 재검증 대상 |
| `git diff --check` | PASS |

위 PostgreSQL 실행은 짧은 wiring·artifact smoke다. 30초 warm-up과 5분 측정의
정식 성능 결과 또는 deployment SLO 증거로 사용하지 않는다.

## 미검증·차단 항목

1. 실제 변경 전 baseline과 현재 candidate를 동일 머신·JDK·PostgreSQL image·dataset·
   pause·concurrency에서 각각 3회 실행하고 comparator를 통과해야 한다.
2. 유효한 여섯 run artifact로 latency, error, lock-wait chart와 분석 문서의 실측
   표·PNG를 생성해야 한다.
3. notification mixed-schema backlog도 두 mix 각각 3회 실행해 decode failure 0과
   처리량·latency 회귀 한계를 검증해야 한다.
4. 보호된 backend E2E로 ETag/412, tenant·patient 권한, outbox, request count,
   trace/screenshot을 보존해야 한다.
5. production에서 ACL·backup·provider log, schema backlog 0, canary/SLO/rollback을
   확인해야 한다.

## 상태

- P0: 0
- P1: 0
- P2: 0
- P3: 0 (구조적 JSON append 보강 후)
- 코드 아키텍처: `CLEAR`
- 코드 검토: `CLEAR`
- Issue #34 전체 DoD: `PENDING`
- PR/merge: 정식 성능·보호 backend·운영 gate가 충족될 때까지 `PENDING`

실측 chart는 아직 추가하지 않았다. 현재 코드로 baseline과 candidate를 모두 만드는
방식은 변경 전후 비교를 위조하므로 허용하지 않는다.
