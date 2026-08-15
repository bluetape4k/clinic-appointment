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

코드·계약 검토 상태는 `CLEAR`다. 후속 실행에서 실제 변경 전 baseline과
candidate를 서로 다른 source ref로 고정하고, PostgreSQL 취소 6회와 notification
codec mixed-schema 12회, 총 18회의 fixed-window artifact를 확보했다. 두 comparator와
실측 chart/PNG는 모두 `PASS`다. 보호된 backend E2E와 production rollout 증거는
별도 운영 게이트로 남아 있으므로 Issue #34의 전체 DoD와 PR merge 상태는 여전히
`PENDING`이다.

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
| 5. 이벤트·알림 | v1/v2 dual-read, cancellation template v1/v2, row/envelope/template identity, default-off producer readiness와 legacy recovery 경계를 확인했다. | PASS; mixed-schema fixed-window benchmark PASS |
| 6. 포털·접근성 | 취소 confirmation, 412 single-flight, 세션 generation, stale 응답 차단, busy/stale mutation 결과, 새로고침 복구 계약을 확인했다. | PASS; protected-backend E2E와 AT matrix는 PENDING |
| 7. 테스트·운영·성능 | 고정 환경·monotonic 측정 구간·lock sampler·환경 fingerprint·구조적 multi-run artifact 계약과 fail-closed comparator/chart를 확인했다. | 코드·18회 local 실측 PASS; 운영 증거 PENDING |

## 독립 검토에서 닫힌 항목

| 이전 지적 | 현재 구현 |
|---|---|
| warm-up 표본이 측정 결과에 섞일 수 있음 | 측정 phase 진입 시 관측치를 초기화하고, 측정 구간만 latency/error/lock-wait에 포함한다. |
| 측정 시간이 wall clock과 traffic 시작 지연에 의존 | phase barrier와 `System.nanoTime()` span을 사용하고 최소 측정 시간을 comparator/chart가 강제한다. |
| sampler 종료 이후 trailing query 가능 | sampling을 중지한 뒤 bounded `Future.get`으로 종료를 확인하고 종료 시각을 기록한다. JDBC query timeout과 nested cleanup도 적용한다. |
| smoke 환경도 baseline/candidate가 같으면 통과 | dataset 100, warm-up 30초, 측정 300초, concurrency 10/20, pause 1000ms 등 절대 환경 계약을 comparator와 chart가 함께 강제한다. |
| run별 source/environment provenance가 불완전 | source commit과 전체 canonical environment를 run마다 확인하고 SHA-256 fingerprint를 독립 재계산한다. |
| 정규식과 마지막 `]` 위치로 run JSON을 삽입 | Jackson `ObjectNode`/`ArrayNode`로 report와 run을 파싱·검증하고 배열에 구조적으로 추가한다. |

## 최신 검증 증거

| 검증 | 결과 |
|---|---|
| `node --test tests/benchmarks/appointment-messaging-benchmark-scripts.test.mjs tests/benchmarks/issue34-benchmark-chart.test.mjs` | 29/29 PASS |
| `./gradlew :appointment-api:compileGatlingKotlin --no-daemon` | BUILD SUCCESSFUL |
| PostgreSQL cancel Gatling 단일 smoke | KO 0, lock-wait sampling failure 0, fingerprint 독립 재계산 일치 |
| PostgreSQL cancel Gatling run 1·2 append smoke | 두 run 구조적 누적, KO 0, source/environment/fingerprint 일치 |
| `./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --no-daemon` | `c31818d`에서 BUILD SUCCESSFUL |
| `git diff --check` | PASS |
| PostgreSQL cancel fixed-window | baseline/candidate 각 3회, warm-up 30초·측정 300초, `postgres:18-alpine`, JDK 25, lock-wait sampling failure 0 | PASS; `docs/benchmarks/issue-34-fixed-window/2026-08-15/comparator/cancel-normalized.log` |
| notification codec fixed-window | `legacy-heavy`·`current-heavy` 각 baseline/candidate 3회, H2 10,000 rows, warm-up 30초·측정 300초 | PASS; `docs/benchmarks/issue-34-fixed-window/2026-08-15/comparator/codec-normalized.log` |
| 18회 artifact replay | 취소 6회 + codec 12회, source ref·environment·run 번호·metric schema 검증 | PASS; `provenance.json`, `charts/issue-34-benchmark-summary.json` |
| 차트·PNG visual QA | SVG 4개 → CairoSVG PNG 4개, semantic/text/geometry/endpoint/mixed-corner/visual audit 및 full-size inspection | PASS; `charts/*.svg`, `charts/*.png` |

위 표의 기존 PostgreSQL 실행 1건과 append 실행은 wiring·artifact smoke로 남긴다.
아래에 추가한 fixed-window 행은 별도 순차 실행으로 30초 warm-up과 5분 측정을
완료한 정식 local benchmark evidence다. 어느 결과도 deployment SLO 증거를 뜻하지
않는다.

## 미검증·차단 항목

1. 보호된 backend E2E로 ETag/412, tenant·patient 권한, outbox, request count,
   trace/screenshot을 보존해야 한다.
2. production에서 ACL·backup·provider log, schema backlog 0, canary/SLO/rollback을
   확인해야 한다.

## 상태

- P0: 0
- P1: 0
- P2: 0
- P3: 0 (구조적 JSON append 보강 후)
- 코드 아키텍처: `CLEAR`
- 코드 검토: `CLEAR`
- Issue #34 local benchmark DoD: `PASS`
- Issue #34 전체 DoD: `PENDING` (protected backend E2E·production rollout 미완료)
- PR/merge: 정식 성능·보호 backend·운영 gate가 충족될 때까지 `PENDING`

실측 산출물은 [`docs/benchmarks/issue-34-fixed-window/2026-08-15`](../benchmarks/issue-34-fixed-window/2026-08-15)에 보존했다.
임시 실행 JSON에서 발견한 `sourceCommit` 오기만 `provenance.json`에 명시하고,
metric·timing·sampling 값은 그대로 유지한 normalized replay report로 comparator와
차트를 재실행했다. JDK 21 baseline과 첫 codec candidate calibration 세트는 환경
fingerprint가 달라 최종 18회 집합에서 제외했으며, comparator threshold를 바꾸지 않았다.
