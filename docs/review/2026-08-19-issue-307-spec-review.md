# Issue #307 설계 리뷰

## 리뷰 범위

- 대상: `docs/superpowers/specs/2026-08-19-issue-307-ddd-event-transaction-boundary-design.md`
- 브랜치: `feat/issue-307-ddd-event-transaction-boundary`
- workflow run: `20260819T063400Z-d38bd54a`
- 판정 기준: Issue #307의 bounded non-suspend `@Transactional` pilot, Exposed Spring transaction 경계, publisher listener 수명, H2 검증 계약

## 관점별 판정

| 관점 | 근거 | 판정 |
|---|---|---|
| 성능 | 실제 서비스 hot path와 저장 스키마를 바꾸지 않으며, 테스트에서 transaction 수·세 저장 행의 원자성과 connection identity만 확인하도록 제한했다. 새 benchmark는 범위 밖이다. | P0/P1 없음. 성능 결론은 N/A이며 구현 테스트로 불필요한 round trip만 확인한다. |
| 안정성 | synchronous listener와 `AFTER_COMMIT` listener를 분리하고, commit·rollback·listener 실패마다 Spring/Exposed/DataSource/publisher 정리를 확인하도록 명세를 보강했다. 초기 P1은 최신 명세에서 해소되었다. | P0/P1 없음. 안정성 재검토 lane은 timeout으로 main fallback이 회수했으며 동일 근거를 fresh receipt에 기록했다. |
| 보안 | opaque ID만 signal에 싣고 예약·환자 payload를 전달하지 않는다. pilot은 public 권한·tenant·clinic 경계를 새로 만들지 않으며, 동기 listener의 외부 I/O를 금지한다. | P0/P1 없음. 보안 재검토는 P0/P1=0을 확인했다. |
| 운영·복구 | publisher auto-configuration은 H2 pilot context에서만 명시적으로 켜고, 기존 post-transaction signal/polling 경로는 proof 전까지 유지한다. 실패 시 부분 커밋이면 승격하지 않는다. | P0/P1 없음. 의존성 버전과 context 기동 실패를 구현 검증에서 확인한다. |
| 개발자·API | Spring proxy를 통과하는 open non-suspend bean, Exposed `transaction {}` 규칙, BOM 관리 의존성, 동일 물리 connection assertion을 사용한다. self-invocation과 검증되지 않은 suspend annotation은 지원하지 않는다. | P0/P1 없음. public API 변경 없이 fixture로 검증한다. |
| 사용자·호출자 | outbox가 durable authority이고 Spring signal은 보조 신호라는 계약을 명시했다. listener는 durable outbox를 재조회하며 외부 bean 호출만 지원한다. | P0/P1 없음. 고정된 테스트 식별자만 사용하며 호출자 권한 모델은 변경하지 않는다. |

초기 안정성·보안 review에서 제기된 P1은 명세 보강으로 해소했다. review-only lane 중 성능과 안정성 재검토는 응답 제한을 넘어 main session fallback으로 전환했으며, fresh run의 일곱 terminal lane과 통합 check로 동일 판정을 재기록했다. 따라서 취소된 이전 run의 상태가 현재 판정을 덮지 않는다.

## 후속 P2와 구현 반영

다음 항목은 현재 설계를 막지 않지만 구현 계획에서 테스트 또는 문서로 닫는다.

1. 기존 `runCatching` legacy signal의 관찰성은 현재 서비스 동작을 보존하는 범위에서 유지하고, pilot listener에는 구조화된 opaque ID 관찰만 추가한다.
2. rollback event buffer는 실패 command의 일회성 재시도에서만 재사용할 수 있음을 테스트로 고정하고, 임의 aggregate 재사용은 금지한다.
3. publisher auto-configuration은 단일 transaction manager 선택, 다중 manager 상황의 fail-closed, 기본 경로 비활성 상태를 context test로 확인한다.

## Writer gate

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 구조·목적 | PASS | 목적, 현재 근거, 범위와 제외 범위를 분리했다. |
| SPW-02 용어·계약 | PASS | `@Transactional`, Exposed, `AFTER_COMMIT`, outbox authority와 signal 의미를 일관되게 사용했다. |
| SPW-03 실행 가능성 | PASS | H2 context, 외부 Spring bean 호출, connection identity, commit/rollback assertion을 명시했다. |
| SPW-04 안전성·회귀 | PASS | 실제 서비스 wiring과 기존 signal을 proof 전까지 보존하고 fault injection 승격 조건을 제한했다. |
| SPW-05 한국어 자연스러움 | PASS | `audit-korean-terms.mjs` 결과 `findings=0`이다. |

## 최종 판정

- P0: 0
- P1: 0
- 설계 상태: **승인된 bounded `@Transactional` pilot 구현 진행**
- 구현 경계: 먼저 `appointment-api` 테스트 의존성과 fixture를 추가하고, 증명 결과가 통과할 때만 adapter 승격 여부를 별도 결정한다.
- 현재 서비스의 전체 annotation 전환, outbox schema/relay/retry/lease, PostgreSQL 설정, 권한 모델 변경은 이 이슈에 포함하지 않는다.
