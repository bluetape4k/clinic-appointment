# PR #200 구현 6-R 및 seven-tier 수렴 검토

검토일: 2026-08-08 (현재 remediation 정합화 갱신)
역사적 exact head: `4f7b41a498dd1c0b4dc9fea41ed1721fe9e8d53f`
PR #215 remediation head: `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035`; merge commit: `9899dacbd62eaec02b9e2ee51a2162715fc9ef82`
현재 follow-up remediation head: PR #232 `936e62d7af98a82f4db147813fcd1c41e44498fe`; merge commit: `addff53107a5fc3d9e30e160bb66e253f809f5b7`
검토 단계: Type A Step 6-R + seven-tier

## 역사적 finding과 수정 경계

원래 exact head에는 다음 P1 구현 결함이 있었다.

- `ProfileReevaluationConfiguration.kt` scheduler의 `runBlocking`
- `ProfileReevaluationEndpoint.kt`와 `ProfileReevaluationHealthIndicator.kt`의 blocking coroutine adapter

이는 명세 결함이 아니며, 원래 head를 PASS로 소급하지 않는다. PR #215 head가 `mono {}` Reactor bridge, suspend scheduler, 명시적 nullable invariant를 도입했고, 후속 PR #232가 notification materializer의 JDBC IO 경계를 보완했다. 따라서 PR #215의 merge commit `9899dac...`와 PR #232의 현재 remediation head `936e62d...`를 별도 provenance로 기록한다.

## seven-tier 결과

| tier | 관점 | 최종 판단 | P0/P1 | 근거 |
|---|---|---|---:|---|
| 1 | 성능 | bounded page와 clinic/global permit 유지 | 0/0 | dispatcher contract 및 focused regression |
| 2 | 안정성 | cancellation/lease/retry와 non-blocking adapter 확인 | 0/0 | PR #215 focused API tests + PR #232 JDBC statement thread 관찰 |
| 3 | 보안·개인정보 | scope·fingerprint-only·CONFIRMED 보호 유지 | 0/0 | endpoint/privacy tests |
| 4 | 운영 | health/actuator가 Reactor bridge로 suspend source를 호출 | 0/0 | 운영 endpoint/health code scan |
| 5 | 개발자/API | `mono`와 `suspend poll`, materializer IO 경계가 명시됨 | 0/0 | PR #215 및 PR #232 exact remediation diff |
| 6 | 사용자·호출자 | 기존 상태·동의 경계와 redrive authorization 유지 | 0/0 | API/security focused tests |
| 7 | main-session 통합 | 명세·계획·remediation·현재 증거가 exact head와 merge로 연결 | 0/0 | #208 2-R → 3-R → PR #215 → PR #232/#233 기록 |

이전 PR #215 remediation에서 확인된 P1은 PR #232의 `enqueue`, `suppressMissed`, `scheduleFuture` IO-boundary 보완과 실제 `Statement.execute*` 관찰 테스트로 해소됐다. aggregate API 전체 테스트는 PR #200 당시의 운영 증거가 아니므로 PR #232 exact-head focused test와 CI run `31174405823`을 현재 검증 근거로 사용한다.

최종 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## 판정

**Current remediation verification: PASS — P0=0, P1=0, P2=0, P3=0.** Historical 6-R/seven-tier independent gate is **NOT PROVEN**; current verification is anchored to PR #232 head `936e62d...`, while merge `addff531...` is recorded separately.
