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

## Independent review receipt

- `architecture` follow-up lane: `WATCH`, P0=0/P1=0/P2=5/P3=1. historical evidence와 current assessment를 분리했지만, six-lens identity/count와 live metadata 정합화가 남아 있다.
- `code-quality-security` follow-up lane: `REQUEST CHANGES`, P0=0/P1=2/P2=1/P3=0. `JdbcAppointmentReminderRecoveryStore.kt:141-186`의 세 materializer 경로가 IO dispatcher 밖에서 blocking Exposed transaction을 실행하며, compliance test가 이를 보장하지 못한다.
- follow-up synthesis: `REQUEST CHANGES`, P0=0/P1=2/P2=6/P3=1. P1이 남아 있으므로 current remediation과 Issue #208 closure를 merge-ready로 판정하지 않는다.
- main validation: live PR heads, PR #215 CI `15 SUCCESS + 1 SKIPPED`, targeted appointment-api 170 tests, Markdown link resolver, exact-head scan, `git diff --check`를 통과했다.

## Receipt authority

현재 follow-up의 권위 있는 workflow receipt는 `.bluetape` run `20260807T091252Z-206acf29`이다. worktree에 남아 있는 이전 `.omx/issue-208-*` 파일은 단일 main lane과 111-test checkpoint를 담은 stale 보조 산출물이므로, 현재 lane topology·170-test 검증·최종 상태의 근거로 사용하지 않는다.

## 해제 조건

1. Issue #208의 historical gate를 `reviewed N/A` 또는 `NOT PROVEN`으로 명시하고, 현재 remediation 검토와 분리한다.
2. 여섯 독립 관점별 reviewer identity, exact head, P0/P1/P2/P3와 main-session integration을 새 current review artifact에 남긴다.
3. Issue/PR metadata와 local index의 #202 및 PR #215 SHA 매핑을 정합화하고, remediation artifact는 PR head `a1fcb1c...`와 merge `9899dac...`를 분리 기록한다.
4. `enqueue`, `suppressMissed`, `scheduleFuture`의 IO dispatcher 경계와 동작 검증을 보완한 뒤 current P1을 재검토한다.
5. 위 조건을 충족하는 follow-up review/PR 없이 Issue #208을 완료로 닫지 않는다.
