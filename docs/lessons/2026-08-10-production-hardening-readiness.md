# 운영 캐시 보안·배포 증거 하드닝 lesson

## 범위

Issue #253 의존성 1.4.0 전환 이후 남은 production-readiness 경계를 다룬다. API
Redis near-cache에 TLS/ACL URL 검증과 등록 강제 Fory serializer를 적용하고, 새 payload를
v3 namespace로 격리했다. 알림 리더 계측(#254), 멱등성(#255), replay race(#256), 보안
응답(#257), notification outbox canary(#204), frontend는 변경하지 않았다.

## 구현 결과

| 영역 | 결과 |
| --- | --- |
| Redis URL | `require-tls=false` local fallback 유지, true에서는 `rediss`, 비 loopback host, ACL username/password 요구 |
| Fory | `requireClassRegistration=true`, unknown class 거부, max depth 32, max graph memory 8 MiB, DTO registration id 1001/1002/1003 |
| Cache wire | `clinic-doctors-v3`, `clinic-equipments-v3`, `clinic-treatment-types-v3`; 논리 Spring cache 이름은 유지 |
| Rollback | v2 namespace 보존, drain/restart/warm-up/TTL 정리 순서를 runbook에 기록 |
| Evidence | local/live JSON 필드와 `--require-live`, 선택적 PostgreSQL/broker threshold validator 추가 |

## 검증 증거

- `CacheConfigSecurityTest`: 4건 통과. local URI 허용, 인증된 비 loopback `rediss` 허용,
  plain/local/missing-auth/malformed 거부, CacheConfig wiring fail-closed를 확인했다.
- secure Fory 경계 테스트: 등록되지 않은 타입이 `BinarySerializationException`으로
  거부되고 `requireClassRegistration`, `deserializeUnknownClass`, max depth, max graph
  memory 설정값이 고정된 것을 확인했다.
- `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests
  "io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest"`: 세 DTO
  독립 Redis client round-trip, v3 raw key 생성, v2/v1 key 부재와 serializer boundary를
  포함해 4건 통과했다.
- Colima 기본 실행은 Ryuk socket mount에서
  `Status 500 ... '/Users/debop/.colima/default/docker.sock' ... operation not supported`
  로 실패했다. Ryuk 비활성화는 테스트 실행 환경 workaround이며 production 설정으로
  전파하지 않는다.
- `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:build --no-build-cache`의
  결과 XML은 707 tests, failures 0, errors 0, skipped 3이었다. 이후 cached
  `:appointment-api:build`도 `BUILD SUCCESSFUL`로 확인했다.
- 정적 검사에서 기존 `appointment-api` 테스트의 Mockito baseline 7건이 확인됐다. 이번
  diff의 추가 라인에는 Mockito가 없고, `CacheConfig` 새 writer에는 v2 namespace가 없도록
  diff 추가 라인 기준으로 검사한다. wire test가 rollback v2 파생 key를 확인하는 것은
  의도된 호환성 경계다.
- `scripts/verify-cache-rollout-evidence.sh`: `bash -n`, local positive, live 조건 부족
  negative, production+threshold positive, threshold 초과 negative를 통과했다.

## 운영 경계

아래 값은 이 worktree에서 실행하지 않았고, 코드 테스트 통과로 대체할 수 없다.

- 인증된 production Redis의 TLS/ACL 연결과 실제 v3 canary
- production PostgreSQL lock-wait와 broker lag SLO
- production cache hit/miss delta와 decode-error 관찰
- traffic drain/restart/warm-up을 포함한 실제 v2 rollback 결과
- GitHub CI, push, PR, merge

따라서 구현/로컬 검증 상태는 green이지만 production DoD는 `PENDING`이다. live report가
생기면 반드시 `--require-live`와 승인된 threshold로 재검증하고, 이 lesson의 PENDING 항목을
실제 timestamp/evidence reference로 교체한다.

## 후속 조치

1. production hardening readiness 전용 follow-up issue를 #254~#257과 중복되지 않게 등록한다.
2. canary 담당자가 Redis/PostgreSQL/broker evidence JSON과 rollback report를 보존한다.
3. live evidence가 없는 동안 v2 namespace를 삭제하지 않는다.
