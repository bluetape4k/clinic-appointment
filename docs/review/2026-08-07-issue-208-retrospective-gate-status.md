# Issue #208 historical Type A review gate status

검토일: 2026-08-07
대상: PR #200, #202, #205, #207
최종 상태: **BLOCKED — historical independent gate 미증명**

## 결론

기존 PR과 merge 이후에 작성된 문서는 exact head의 명세·계획·구현을 재평가하는 유용한 retrospective assessment다. 그러나 merge 전에 수행된 여섯 독립 관점과 main-session integration의 증거가 아니므로, historical 2-R/3-R/6-R gate를 `PASS`로 소급하지 않는다.

| PR | live exact head | retrospective content | historical gate | current implementation/remediation |
|---|---|---|---|---|
| #200 | `4f7b41a498dd1c0b4dc9fea41ed1721fe9e8d53f` | 2-R/3-R PASS, P0=0/P1=0 | NOT PROVEN | PR #215 head `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035` current verification BLOCKED by P1; merge `9899dac...` |
| #202 | `1baad5cfeb092792c7ae92eac79d51f465972fad` | 2-R/3-R PASS, P0=0/P1=0 | NOT PROVEN | current implementation assessment PASS; issue comment의 `f10e2...`는 merge SHA |
| #205 | `cb8c093ff77289242093b4e1c832e95e73b46870` | 2-R/3-R PASS, P0=0/P1=0 | NOT PROVEN | PR #215 head `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035` current verification BLOCKED by P1; merge `9899dac...` |
| #207 | `18f3007e2c3c82f072c9934f27041f0846ffa285` | 2-R/3-R PASS, P0=0/P1=0 | NOT PROVEN | PR #215 head `a1fcb1c128c7ee6e2e324989fd4119e9ba6c5035` current verification BLOCKED by P1; merge `9899dac...` |

## Current narrow remediation (local only)

Issue #208의 구현 결함 하나는 별도 Type C bugfix로 수정했지만, 이 변경은 과거 Type A gate를 소급해 통과시키거나 Issue #208을 닫는 근거가 아니다.

- production fix commit: `569e5bc28863d94d3a7fe6bb9028d5443fc98489`
- exact current implementation/evidence head: `936e62d7af98a82f4db147813fcd1c41e44498fe`
- evidence commits after the production fix: `e666bfc` (JDBC statement execution observation), `936e62d` (test scope and static-guard cleanup)
- branch/worktree: `fix/issue-208-reminder-io-boundary` / `.worktrees/issue-208-reminder-io-boundary`
- changed production path: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/JdbcAppointmentReminderRecoveryStore.kt`
- boundary: `enqueue`, `suppressMissed`, `scheduleFuture`가 주입된 `ioDispatcher` 안의 blocking Exposed `transaction(database)`으로 수렴한다.
- Type C implementation receipt: `20260807T095731Z-df47e975` (completed, sequence 14)
- Type C JDBC-observation receipt: `20260807T104518Z-e20d367f` (completed, sequence 14)
- Type C scope-cleanup receipt: `20260807T110236Z-066e6995` (completed, sequence 13)
- Type D prior receipt: `20260807T101233Z-627082d3` (completed, sequence 16)
- Type D final receipt: `20260807T110653Z-59c55dc7` (completed, sequence 16)
- final code-quality review: P0=0/P1=0/P2=0/P3=0; recommendation `COMMENT` only because `lsp_diagnostics` is unavailable
- final architecture review: implementation `CLEAR`; the pre-refresh `WATCH` P2=1 was metadata-only and is resolved by this exact-head documentation refresh

The local follow-up now observes actual `Statement.execute*` calls on the injected IO thread for the three materializer paths, and the compliance test keeps only a lightweight `withContext(ioDispatcher)` guard rather than a brittle source-occurrence count. The review artifact is now anchored to `936e62d`; historical Type-A evidence remains `NOT PROVEN`. This local follow-up includes no PR/push/merge, so external delivery and Issue closure remain PENDING.

## Independent review receipt

### Prior backfill review

- `architecture` follow-up lane: `WATCH`, P0=0/P1=0/P2=5/P3=1. historical evidence와 current assessment를 분리했지만, six-lens identity/count와 live metadata 정합화가 남아 있다.
- `code-quality-security` follow-up lane: `REQUEST CHANGES`, P0=0/P1=2/P2=1/P3=0. 이전 exact head에서 `JdbcAppointmentReminderRecoveryStore.kt:141-186`의 세 materializer 경로가 IO dispatcher 밖에서 blocking Exposed transaction을 실행했다.
- prior synthesis: `REQUEST CHANGES`, P0=0/P1=2/P2=6/P3=1. 이 결과는 현재 local fix 이전의 historical/current assessment다.

### Current local follow-up

- code-quality lane: exact head `936e62d`, P0=0/P1=0/P2=0/P3=0. JDBC `Statement.execute*` 관찰, targeted tests, compile, `git diff --check`, lightweight static scans를 확인했다.
- architecture lane: implementation `CLEAR`; pre-refresh `WATCH` P0=0/P1=0/P2=1/P3=0은 stale exact-head metadata 하나였고 Type E 문서 갱신으로 정합화했다.
- main-session synthesis: current remediation P0/P1/P2/P3는 0/0/0/0으로 수렴했으나, Issue acceptance가 요구하는 과거 six-lens identity/count와 historical gate를 이 두 lane이 대체하지 않는다.

## Receipt authority

- prior backfill review receipt: `.bluetape` run `20260807T091252Z-206acf29` (historical P1로 BLOCKED)
- current Type C implementation receipt: `.bluetape` run `20260807T095731Z-df47e975` (completed)
- current Type C JDBC-observation receipt: `.bluetape` run `20260807T104518Z-e20d367f` (completed)
- current Type C scope-cleanup receipt: `.bluetape` run `20260807T110236Z-066e6995` (completed)
- current Type D review receipt: `.bluetape` run `20260807T101233Z-627082d3` (completed)
- final Type D review receipt: `.bluetape` run `20260807T110653Z-59c55dc7` (completed)
- final Type E documentation receipt: `.bluetape` run `20260807T111534Z-be30bc6b` (completed after this document commit)
- worktree에 남아 있는 이전 `.omx/issue-208-*` 파일은 stale 보조 산출물이므로 현재 exact-head와 lane topology의 근거로 사용하지 않는다.
- receipt는 local workflow evidence이며 GitHub PR head/CI/merge authority를 대체하지 않는다.

## 해제 조건

1. Issue #208의 historical gate를 `reviewed N/A` 또는 `NOT PROVEN`으로 명시하고, 현재 remediation 검토와 분리한다.
2. 여섯 독립 관점별 reviewer identity, exact head, P0/P1/P2/P3와 main-session integration을 새 current review artifact에 남긴다.
3. Issue/PR metadata와 local index의 #202 및 PR #215 SHA 매핑을 정합화하고, remediation artifact는 PR head `a1fcb1c...`와 merge `9899dac...`를 분리 기록한다.
4. `enqueue`, `suppressMissed`, `scheduleFuture`의 실제 JDBC statement 실행 thread 관찰 검증과 exact-head current review artifact를 보완한다. (현재 local remediation에서 충족)
5. six independent perspectives, main-session integration, exact head, 일관된 P0/P1/P2/P3를 포함한 current seven-tier artifact를 별도로 기록한다.
6. 위 조건을 충족하는 follow-up review/PR 없이 Issue #208을 완료로 닫지 않는다.
