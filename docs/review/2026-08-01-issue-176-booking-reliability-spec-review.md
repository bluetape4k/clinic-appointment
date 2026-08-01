# Issue #176 설계 명세 독립 검토

검토일: 2026-08-01
검토 대상: `docs/superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md`
검토 단계: Type A Step 2-R

## 검토 범위와 근거

명세와 현재 checkout의 다음 계약을 함께 읽었다.

- `CapacityAndReliabilityPolicies.kt`: `PRIORITY_AND_RELIABILITY` payload와 clinic override
- `SchedulingPolicyPayloadCodec.kt`, `SchedulingPolicyValidator.kt`, `SchedulingPolicyHasher.kt`: codec·범위 검증·snapshot hash
- `AppointmentCommitmentModel.kt`: `PROPOSED`/`HELD`/`CONFIRMED` 전이와 확정 보호
- `MemberId.kt`: opaque 회원 식별자 경계
- `EffectiveSchedulingPolicyService.kt`: tenant/clinic effective policy 조회
- `ActorContextResolver.kt`, `ProfileReevaluationEndpoint.kt`: clinic scope와 API 권한 관례
- 이슈 #176과 소비자 이슈 #170의 현재 GitHub 상태

성능·안정성·보안 lane은 native review 결과가 bounded 시간 안에 도착하지 않아 main-session fallback으로 수행했다. 이 timeout은 변경 승인을 대신하지 않으며, 해당 관점을 동일 명세와 위 근거로 재검토했다.

## 독립 관점 결과

| 우선순위 | 관점 | 근거와 판단 | 필요한 조치 | 상태 |
|---|---|---|---|---|
| P0 | 성능 | 동기 경로가 member 단위 bounded history와 keyset batch를 사용하도록 명시됨 | 없음 | 0 |
| P1 | 성능 | `triggeringAppointmentIds`와 원장 read 상한이 초안에서 모호하면 대형 clinic 응답·메모리 폭증 위험 | 최대 read 100건, 응답 ID 32건, 추가 cursor를 명세에 고정 | 수정 완료 |
| P0 | 안정성 | policy 경쟁, CAS, 중복·역순 사건, outage, retry가 별도 실패 경로로 정의됨 | 없음 | 0 |
| P1 | 안정성 | event attribution write와 decision snapshot 수렴이 구현에서 단일 transaction/idempotency 경계로 누락될 수 있음 | Exposed transaction 경계, 외부 호출 분리, unique digest와 source version 멱등화를 명세에 고정 | 수정 완료 |
| P0 | 보안·개인정보 | 이름·전화번호·자유 텍스트를 금지하고 principal 기반 clinic scope를 요구함 | 없음 | 0 |
| P1 | 보안·개인정보 | `MemberId`도 개인 식별자로 취급할 수 있어 retention·삭제가 명세에 없으면 장기 보존 위험 | 보존/삭제·pseudonymization·법적 retention class를 명세에 추가 | 수정 완료 |
| P0 | 운영 | `OFF/SHADOW/ENFORCE`, clinic allow-list, additive migration, pause·rollback 기준이 정의됨 | 없음 | 0 |
| P1 | 운영 | 실제 canary 수치·alert 임계값·runbook 경로는 구현 전 운영 계약으로 고정해야 함 | 24시간/1,000건 canary, 승격 기준, rollback과 `docs/runbooks/booking-reliability.md`를 명세에 고정 | 수정 완료 |
| P0 | 개발자/API | evaluator 결과와 `#170` 소비 계약, stale 재검증, caller-safe 결과 의미가 분리됨 | 없음 | 0 |
| P1 | 개발자/API | 정확한 Kotlin property·HTTP URL·error registry를 뒤로 미루면 구현자 간 계약이 갈릴 수 있음 | endpoint base path와 caller-safe error code를 명세에 고정하고 Kotlin property naming만 계획 단계에서 확정 | 수정 완료 |
| P0 | 사용자·호출자 | `ELIGIBLE/RESTRICTED/OVERRIDDEN/UNAVAILABLE`별 caller 동작과 `CONFIRMED` 보호가 명시됨 | 없음 | 0 |
| P1 | 사용자·호출자 | `RESTRICTED`를 고객에게 낙인으로 노출하지 않는 public error와 직원 상세 경계가 필요함 | `BOOKING_REVIEW_REQUIRED` 등 caller-safe code와 API 보안 테스트를 명세에 고정 | 수정 완료 |

## Main-session 통합

중복 findings를 합치면 설계 blocker는 없다.

- 모든 P0/P1은 0으로 수렴했다. 구현 계획에서는 명세에 고정한 계약을 실제 파일·검증 명령으로 연결한다.
- 기존 `CONFIRMED` 보호, opaque `MemberId`, 병원 책임 사건 제외, `#170` 단일 evaluator 계약 사이에 모순이 없다.
- `UNAVAILABLE`을 자동 제한으로 해석하지 않고 재시도·직원 검토로 보내므로 outage가 고객 차별로 전환되지 않는다.
- schema 이름·threshold property·HTTP URL은 현재 코드 패턴을 조사한 뒤 구현 계획에서 확정한다. 이는 설계 의미를 바꾸지 않는 구체화 작업이다.
- HTML+PNG 업무 흐름과 정적 ERD의 locale/theme 요구는 기준 Markdown과 분리된 reader-facing 산출물로 남긴다.

## 결론

`P0=0, P1=0`으로 Step 2-R을 통과한다. P2 수준의 구현 구체화와 운영 수치는 Step 3 plan에 배치한다. 다음 단계는 이 명세의 사용자 검토·승인 후 `writing-plans`를 로드해 실행 계획을 작성하는 것이다.
