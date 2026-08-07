# PR #200 구현 6-R 및 seven-tier 수렴 검토

검토일: 2026-08-07
역사적 exact head: `4f7b41a498dd1c0b4dc9fea41ed1721fe9e8d53f`
PR #215 remediation head: `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035`; merge commit: `9899dacbd62eaec02b9e2ee51a2162715fc9ef82`
검토 단계: Type A Step 6-R + seven-tier

## 역사적 finding과 수정 경계

원래 exact head에는 다음 P1 구현 결함이 있었다.

- `ProfileReevaluationConfiguration.kt` scheduler의 `runBlocking`
- `ProfileReevaluationEndpoint.kt`와 `ProfileReevaluationHealthIndicator.kt`의 blocking coroutine adapter

이는 명세 결함이 아니며, 원래 head를 PASS로 소급하지 않는다. PR #215 head가 `mono {}` Reactor bridge, suspend scheduler, 명시적 nullable invariant를 도입했지만, 현재 follow-up은 notification materializer의 IO 경계 누락을 P1로 확인했다. 따라서 merge commit `9899dac...`는 결과이며 final review anchor는 PR head `a1fcb1c...`로 고정한다.

## seven-tier 결과

| tier | 관점 | 최종 판단 | P0/P1 | 근거 |
|---|---|---|---:|---|
| 1 | 성능 | bounded page와 clinic/global permit 유지 | 0/0 | dispatcher contract 및 focused regression |
| 2 | 안정성 | cancellation/lease/retry와 non-blocking adapter 확인 | 0/0 | PR #215 focused API tests |
| 3 | 보안·개인정보 | scope·fingerprint-only·CONFIRMED 보호 유지 | 0/0 | endpoint/privacy tests |
| 4 | 운영 | health/actuator가 Reactor bridge로 suspend source를 호출 | 0/0 | 운영 endpoint/health code scan |
| 5 | 개발자/API | `mono`와 `suspend poll`로 coroutine 경계가 명시됨 | 0/0 | exact remediation diff |
| 6 | 사용자·호출자 | 기존 상태·동의 경계와 redrive authorization 유지 | 0/0 | API/security focused tests |
| 7 | main-session 통합 | 명세·계획·remediation·검증 artifact가 동일 chain으로 연결 | 0/0 | #208 2-R → 3-R → 6-R 기록 |

P1: `JdbcAppointmentReminderRecoveryStore.kt:141-186`의 `enqueue`, `suppressMissed`, `scheduleFuture`가 blocking Exposed transaction을 IO dispatcher 밖에서 실행한다. compliance test는 `withContext(ioDispatcher)` 존재만 확인하므로 P2 검증 gap도 남는다. aggregate API 전체 테스트는 PR #200 당시의 운영 증거가 아니므로 PR #215의 focused 155 API tests와 CI run `30763178105`(15 successful checks + 1 skipped)를 사용했다.

최종 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## 판정

**Current remediation verification: BLOCKED — P0=0, P1=2, P2=1, P3=0.** Historical 6-R/seven-tier independent gate is **NOT PROVEN**; current verification is anchored to PR head `a1fcb1c...`, while merge `9899dac...` is recorded separately.
