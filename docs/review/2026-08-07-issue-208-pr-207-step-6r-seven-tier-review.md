# PR #207 booking reliability 구현 6-R 및 seven-tier 수렴 검토

검토일: 2026-08-08 (현재 remediation 정합화 갱신)
historical exact head: `18f3007e2c3c82f072c9934f27041f0846ffa285`
PR #215 remediation head: `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035`; merge commit: `9899dacbd62eaec02b9e2ee51a2162715fc9ef82`
현재 follow-up remediation head: PR #232 `936e62d7af98a82f4db147813fcd1c41e44498fe`; merge commit: `addff53107a5fc3d9e30e160bb66e253f809f5b7`
검토 단계: Type A Step 6-R + seven-tier

## historical finding

PR #207 exact head의 touched tests에는 generic JUnit assertion block이 남아 있었다. 이는 production P0/P1은 아니지만 repository Kotlin pattern을 위반하는 P2 finding이다. PR #215 head가 정확히 28개 touched test file을 bluetape assertion과 explicit nullable check로 정리하고 compliance test를 추가했다. 후속 PR #232는 notification materializer의 blocking transaction IO 경계를 보완하고 실제 JDBC statement 실행 thread를 관찰하는 회귀 테스트를 추가했다.

## seven-tier 결과

| tier | 관점 | 최종 판단 | P0/P1 |
|---|---|---|---:|
| 1 | 성능 | evaluator input/lookback/trigger bound와 migration index contract 유지 | 0/0 |
| 2 | 안정성 | strict ingress, quarantine, dedupe, stale/override semantics와 JDBC IO 격리 유지 | 0/0 |
| 3 | 보안·개인정보 | raw payload strict decode, PII 비저장, retention class 경계 | 0/0 |
| 4 | 운영 | OFF/SHADOW/ENFORCE, canary, audit/retention 후속 기준 유지 | 0/0 |
| 5 | 개발자/API | evaluator와 caller-safe verdict, V17 dialect migration contract, Kotlin IO 경계 유지 | 0/0 |
| 6 | 사용자·호출자 | `RESTRICTED`/`UNAVAILABLE` semantics와 `CONFIRMED` 보호 유지 | 0/0 |
| 7 | main-session 통합 | existing 2-R/3-R + #215/#232 remediation + current exact-head record 연결 | 0/0 |

historical generic assertions와 notification materializer IO-boundary P1/P2는 remediation으로 해소됐다. #170 integration, clinic-wide backfill, retention executor는 linked follow-up 범위다.

최종 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

PR #232 head `936e62d...` focused evidence와 merge `addff531...`, CI `31174405823`를 확인했다. current source scan에서는 unsafe `runBlocking`/persisted-ID `!!`가 재발하지 않았고, materializer의 blocking transaction 경계도 주입된 IO dispatcher 안으로 수렴한다.

**Current remediation verification: PASS — P0=0, P1=0, P2=0, P3=0.** Historical 6-R/seven-tier independent gate is **NOT PROVEN**; final review anchor is PR #232 head `936e62d...`, with merge `addff531...` recorded separately.
