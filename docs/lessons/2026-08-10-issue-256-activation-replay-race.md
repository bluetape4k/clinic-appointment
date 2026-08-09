# Issue #256 scheduling policy activation durable replay race 교훈

## 문제와 원인

PostgreSQL `READ COMMITTED`에서 동일 idempotency key로 두 activation이 동시에
시작되면, 승자 transaction이 durable command와 `ACTIVE` lifecycle을 commit한 직후에도
패자의 첫 command 조회가 아직 이전 snapshot을 볼 수 있다. 기존 코드는 command가 없다고
판단한 뒤 `ACTIVE` definition을 읽고 `POLICY_DRAFT_STALE`을 반환했다. 이미 같은 의도로
완료된 durable command가 존재하므로, 이는 stale 입력이 아니라 replay로 수렴해야 하는
경합 창이었다.

## 결정

`resolveActivationCommand`가 `POLICY_DRAFT_STALE`을 관찰하면 immediate activation의
동일 scope/key를 한 번 더 조회한다. durable row가 발견된 경우에는 기존과 동일하게
fingerprint와 preview evidence를 exact-match하고, 검증을 통과한 row를 반환한다.
row가 없거나 의도가 다르면 원래 stale/idempotency 오류를 그대로 보존한다. 따라서
진짜 revision 충돌을 무시하지 않으면서 이미 commit된 command만 idempotent replay로
복구한다.

## 검증

- 수정 전 deterministic visibility-gap 회귀는 `POLICY_DRAFT_STALE`로 실패했다.
- 수정 후 같은 회귀는 기존 command ID의 `idempotentReplay=true`로 통과했다.
- PostgreSQL `SchedulingPolicyDialectIntegrationTest` 5개와 병렬 activation repeated 3회가
  통과했다.
- H2 `SchedulingPolicyCommandServiceTest` 14개가 통과했다.

## 향후 guard

1. durable idempotency lookup과 lifecycle/CAS 결과를 함께 사용하는 경로는 commit 이후
   재조회가 필요한 visibility gap을 deterministic test double로 고정한다.
2. replay fallback은 반드시 intent fingerprint와 preview evidence를 다시 검증하고,
   같은 key의 다른 payload를 성공으로 바꾸지 않는다.
3. Testcontainers 기반 aggregate 실행에서 재현되는 단발성 stale 오류는 retry pass만으로
   닫지 말고 transaction visibility와 durable row 상태를 함께 확인한다.
