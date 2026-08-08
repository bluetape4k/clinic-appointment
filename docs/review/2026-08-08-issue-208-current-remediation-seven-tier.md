# Issue #208 현재 remediation 6-R 및 seven-tier 검토

검토일: 2026-08-08
검토자: `main-session` 현재 remediation 재검토
기준 저장소: `bluetape4k/clinic-appointment` / `develop`
구현 exact head: PR #232 `936e62d7af98a82f4db147813fcd1c41e44498fe`
구현 merge: `addff53107a5fc3d9e30e160bb66e253f809f5b7`
문서 exact head: PR #233 `2ba8b854fa8fbf911276fd684bd41f4162c1cd64`
문서 merge: `8807bc08cdf84300e12180b326a1f0a6c1b64040`

## 범위와 역사적 한계

이번 기록은 PR #232의 reminder recovery JDBC IO-boundary remediation과 PR #233의
현재 증거 문서를 `develop` 기준으로 다시 대조한 현재 검토다. merge 전에 수행된
여섯 독립 관점과 main-session 통합 receipt가 없으므로 PR #200, #202, #205, #207의
historical Type A gate를 소급해 `PASS`로 바꾸지 않는다. historical 상태는 계속
`NOT PROVEN`이다.

## 현재 remediation seven-tier

| tier | 관점 | 현재 판단 | P0/P1/P2/P3 | 근거 |
|---|---|---|---:|---|
| 1 | 성능 | blocking JDBC materializer를 주입된 `ioDispatcher`로 격리하고 bounded recovery 경계를 유지 | 0/0/0/0 | `JdbcAppointmentReminderRecoveryStore`의 `enqueue`, `suppressMissed`, `scheduleFuture`와 PR #232 focused test |
| 2 | 안정성 | 실제 `Statement.execute*` 호출 thread 관찰과 retry/idempotency 경계를 확인 | 0/0/0/0 | `JdbcAppointmentReminderRecoveryStoreTest`, 동일 event/materializer 경로 재사용 |
| 3 | 보안·개인정보 | 이번 remediation은 저장 payload·member/contact 경계를 변경하지 않음 | 0/0/0/0 | PR #232 changed-file scope, 기존 redaction/contract test, PR #232 CI |
| 4 | 운영 | checkpoint lock·leader/recovery 흐름은 유지하고 dispatcher 경계만 보완 | 0/0/0/0 | 현재 source 대조, recovery lesson, PR #232 CI |
| 5 | 개발자/API | public materializer 계약과 Kotlin pattern compliance를 유지 | 0/0/0/0 | `KotlinProductionPatternComplianceTest`, PR #232 exact-head CI |
| 6 | 사용자·호출자 | `ENQUEUED`·`SUPPRESSED`·`ALREADY_EXISTS` 결과와 due/future/missed 의미를 유지 | 0/0/0/0 | 기존 materializer test와 PR #232 변경 범위 대조 |
| 7 | main-session 통합 | historical finding → PR #232 remediation → PR #233 evidence chain을 exact head와 merge로 분리 연결 | 0/0/0/0 | PR #232/#233 live metadata, current status, lesson/index |

## 검증 근거

- PR #232 exact head `936e62d…`: CI run `31174405823` 성공. backend 변경 범위에
  따라 Angular frontend job은 `SKIPPED`이며 이번 작업의 N/A다.
- PR #233 exact head `2ba8b85…`: CI run `31174415419` 및 visual companion run
  `31174415423` 성공. 문서 변경 범위에 따라 backend/frontend 테스트는 N/A다.
- 현재 코드의 focused regression은 실제 JDBC `Statement.execute*` 호출을 주입된
  IO dispatcher thread에서 관찰하며, source occurrence 개수만으로 경계를 증명하지
  않는다.
- 이번 검토는 production-shaped staging, provider 처리량, 24시간 canary를 실행하지
  않는다. 이는 Issue #204의 외부 운영 조건이며 Issue #208의 현재 문서 정합화 범위가
  아니다.

## 판정

**현재 remediation: PASS — P0=0, P1=0, P2=0, P3=0.**
**historical Type A independent gate: NOT PROVEN.** 이 artifact는 현재 remediation의
정합성과 증거 chain을 고정하지만, 누락된 merge 전 historical receipt를 복원하지
않는다. 따라서 Issue #208은 historical 상태를 명시적으로 검토하거나 별도 증거를
추가하기 전까지 완료/종료로 처리하지 않는다.
