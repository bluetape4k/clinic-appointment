# PR #205 reminder 복구 구현 6-R 및 seven-tier 수렴 검토

검토일: 2026-08-08 (현재 remediation 정합화 갱신)
historical exact head: `cb8c093ff77289242093b4e1c832e95e73b46870`
PR #215 remediation head: `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035`; merge commit: `9899dacbd62eaec02b9e2ee51a2162715fc9ef82`
현재 follow-up remediation head: PR #232 `936e62d7af98a82f4db147813fcd1c41e44498fe`; merge commit: `addff53107a5fc3d9e30e160bb66e253f809f5b7`
검토 단계: Type A Step 6-R + seven-tier

## historical P1

PR #205 exact head는 `synchronized(cursorLock)`로 suspend `findCandidates`와 blocking Exposed transaction을 감싸고 있었다. 이는 cancellation·thread starvation 위험이 있는 P1이며 historical head에 대한 FAIL finding이다. PR #215 head가 cursor coordination을 `Mutex`로 바꾸고 `findCandidates`에 `withContext(ioDispatcher)` 경계를 추가했고, PR #232가 `enqueue`, `suppressMissed`, `scheduleFuture`의 남은 blocking transaction 경계를 보완했다.

## seven-tier 결과

| tier | 관점 | 최종 판단 | P0/P1 |
|---|---|---|---:|
| 1 | 성능 | keyset/page/date window와 pending queue가 bounded | 0/0 |
| 2 | 안정성 | checkpoint transaction, restart, idempotent result 수렴과 JDBC IO 격리 | 0/0 |
| 3 | 보안·개인정보 | redacted candidate `toString`, 최소 payload, 연락처 snapshot 없음 | 0/0 |
| 4 | 운영 | ready/hourly trigger, metric, failure log와 disabled gate | 0/0 |
| 5 | 개발자/API | `Mutex` + IO dispatcher + Exposed transaction의 명시적 경계와 materializer 위임 | 0/0 |
| 6 | 사용자·호출자 | due/future/missed semantics와 duplicate retry 안정성 | 0/0 |
| 7 | main-session 통합 | restored spec/plan → historical finding → #215/#232 remediation chain | 0/0 |

이전 current review에서 확인된 P1/P2는 PR #232의 세 materializer IO-boundary 보완과 실제 `Statement.execute*` thread 관찰로 해소됐다. 다중 인스턴스 leader semantics와 full aggregate API test는 별도 follow-up 범위다.

최종 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

PR #232 head `936e62d...` focused evidence와 merge `addff531...`, CI run `31174405823`을 현재 검증 근거로 사용한다. PR #215는 upstream Kotlin remediation provenance로만 남긴다.

**Current remediation verification: PASS — P0=0, P1=0, P2=0, P3=0.** Historical 6-R/seven-tier independent gate is **NOT PROVEN**; final review anchor is PR #232 head `936e62d...`, with merge `addff531...` recorded separately.
