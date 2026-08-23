# Issue #311 waitlist scheduler fencing Type A 최종 검토

## 범위와 판정

이번 PR은 production runner/wiring이나 `LettuceFencedLock` 구현이 아니라,
기존 DB claim fence를 보존하고 fenced path 활성화 조건을 문서화하는 docs-only
hold다. 여섯 관점 모두 현재 범위에서 **P0=0, P1=0, PASS**로 판정했다.

Issue #311 자체는 production runner, typed fenced API, Redis token propagation,
strict-greater DB migration과 production-like 검증이 완료될 때까지 **PENDING**으로
유지한다.

## 여섯 관점 결과

| 관점 | 판정 | 확인한 핵심 근거 |
|---|---|---|
| Architecture | PASS | Redis는 advisory scheduler gate이고 DB `owner/version/leaseVersion/leaseExpiresAt` fence가 business authority다. 실제 runner/wiring 부재를 문서가 과장하지 않는다. |
| Security | PASS | actor는 `SYSTEM` 또는 full keyed `hmac:vN:<64 hex>`만 허용하고 suffix·truncated·비키드 hash를 거부한다. evidence correlation은 일반 HTTP trace와 분리한 서버 생성 random/keyed opaque 값만 사용한다. 현재 일반 audit 경계의 caller correlation과 `staff:<sha256...take(24)>` actor는 미충족 상태로 명시하고 fenced activation을 차단한다. |
| Performance | PASS | `jobLease >= worst-case tick + safety margin`, tick p95/p99, bounded backoff/jitter 또는 circuit breaker와 retry budget, 고정 cardinality metric을 readiness hold로 고정했다. schema/index/dependency 비용은 없다. |
| SRE/Operations | PASS | rollback은 dispatch를 멈추되 expiry/suppression/reconcile을 유지한다. `Ambiguous`/unknown은 `NOT_HELD` 확인 전 acquire·dispatch·requeue·business mutation을 quarantine한다. Redis production-like 증거 부재는 의도된 Not-tested다. |
| Library/API | PASS | 현재 포트는 Boolean acquire/release뿐이고 production Redis adapter wiring이 없다. typed result, fixed lease/watchdog, close/cancellation, reconcile, single release, token propagation과 전 terminal mutation strict-greater를 activation prerequisite로 남겼다. |
| Kotlin/Test | PASS | Exposed fresh transaction과 DB CAS fence 경계를 보존한다. API scheduler/recovery 5개와 core repository/PostgreSQL contention 15개가 모두 통과했고 skipped/failure/error는 0이다. recovery drill은 실제 Redis가 아닌 in-memory fixture로 명시했다. |

## 보정 이력

초기 보안 검토에서 운영 ticket/audit의 actor와 correlation이 opaque인지 불명확한
P1이 발견됐다. 문서 전체에 다음을 명시한 뒤 재검토에서 PASS로 전환했다.

- `staff:*`/`recovery:*` 및 임의 suffix, truncated·비키드 hash를 허용하지 않는다.
- 일반 HTTP trace `CorrelationId`와 fenced evidence correlation을 분리한다.
- 현재 일반 waitlist audit 경계의 caller correlation 보존과 비키드 actor는 이번
  docs-only 범위에서 조용히 수정하지 않고, 보정·회귀 검증 전 fenced path를
  활성화하지 않는다.

## 검증 증거

- `git diff --check`: PASS
- `./gradlew :appointment-api:test --tests '...WaitlistDeliverySchedulingTest' --tests '...WaitlistDeliveryRecoveryDrillTest' --rerun-tasks --no-daemon --console=plain`: 5개 통과
- `./gradlew :appointment-core:test --tests '...WaitlistDeliveryRepositoryTest' --tests '...WaitlistDeliveryPostgreSqlContentionTest' --rerun-tasks --no-daemon --console=plain`: 15개 통과
- `./gradlew :appointment-api:test --no-build-cache --no-daemon --console=plain`: 832개 통과, 3개 skipped. 첫 `--rerun-tasks` 실행의 test-result aggregation artifact 오류는 `cleanTest` 후 재실행으로 해소했으며 assertion 실패가 아니었다.
- 변경 범위: API/runbook/spec/plan/lesson/review 문서 6개; Kotlin/API/schema/migration/dependency 변경 없음

## 남은 hold

Redis fenced integration, production runner/wiring, token propagation, p95/p99 측정,
retry budget과 ownership-loss metric 구현은 아직 없다. 이 항목들은 Issue #311의
재개 acceptance이며, 현재 PR의 완료로 오인하지 않는다.
