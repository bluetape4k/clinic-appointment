# 운영 캐시 보안과 배포 증거 강화 설계

## 목적

Issue #253에서 의존성 그래프와 애플리케이션 회귀는 검증했지만, 실제 운영으로
진입하기 위한 Redis 보안 경계와 캐시 wire 계약, PostgreSQL/브로커/롤백 증거의
수집 형식은 아직 `PENDING`이다. 이 설계는 그 잔여 경계를 구현 가능한 계약으로
좁힌다. 대상은 `appointment-api`의 Redis near-cache와 운영 검증 도구이며, 프론트엔드,
알림 리더 계측(#254), 멱등성(#255), replay race(#256), 보안 응답(#257), notification
outbox canary(#204)는 포함하지 않는다.

이 문서는 승인된 Type-A delivery lane의 설계 기준이다. 로컬 테스트가 통과해도
인증된 production Redis, PostgreSQL, broker 및 실제 rollback 결과를 확인하기 전에는
운영 완료로 표시하지 않는다.

## 현재 기준선과 문제

- `CacheConfig.redisClient`는 기본값 `redis://localhost:6379`를 그대로 사용하고,
  `LettuceCaches.nearCache(redisClient) { ... }`의 기본 Fory/LZ4 codec을 사용한다.
- 원격 캐시 이름은 `clinic-doctors-v2`, `clinic-equipments-v2`,
  `clinic-treatment-types-v2`이다. `v2` payload와 새 serializer를 같은 namespace에
  섞으면 rolling deployment 중 구 binary가 새 payload를 해석할 수 있다는 보장이 없다.
- 현재 wire 테스트는 서로 다른 client의 round trip과 raw key를 확인하지만 codec의
  클래스 등록, 깊이, graph memory 상한과 운영 TLS/ACL 요구사항을 고정하지 않는다.
- 기존 benchmark 결과의 `deploymentSloEvidence=false`는 로컬 성능 기준일 뿐이며,
  production lock-wait, broker lag, cache hit/miss, rollback 결과를 대신하지 않는다.

## 설계 원칙과 범위

1. 기본 로컬/테스트 실행은 유지한다. 명시적 운영 보안 플래그가 켜진 경우에만
   fail-closed validation을 적용한다.
2. core 도메인과 messaging 의존성 경계를 건드리지 않는다. 변경은 API cache 설정,
   테스트, 운영 스크립트와 runbook에 한정한다.
3. 캐시 codec 변경은 역방향 decode 성공을 전제로 하지 않는다. 새 wire format은
   별도 namespace에서만 읽고 쓴다.
4. Fory 설정은 신뢰할 수 없는 원격 payload를 대상으로 한다. 클래스 등록을 요구하고,
   알 수 없는 클래스·과도한 depth·graph memory를 거부한다.
5. 운영 증거는 사람이 읽는 로그가 아니라 버전이 있는 JSON 계약으로 보존한다. 값이
   없으면 validator가 실패해야 하며, 임의의 `PASS` 문자열만으로 통과하지 않는다.
6. Kotlin 구현과 테스트는 `$bluetape-kotlin-patterns`를 따른다. 테스트 double이
   필요한 경우 MockK/Spring MockK만 사용하고 Mockito는 추가하지 않는다. Redis 통합은
   저장소의 singleton launcher를 사용하며 `@Testcontainers`는 사용하지 않는다.

## 대안 비교

### A. 클래스 등록 강제 + v3 namespace + 운영 증거 validator (채택)

`ForyBuilder.requireClassRegistration(true)`를 유지하고 명시적으로 허용한 세 DTO를
안정적인 registration id로 등록한다. `withDeserializeUnknownClass(false)`,
`withMaxDepth(32)`, `withMaxGraphMemoryBytes(8 MiB)`를 적용하고 codec을 새
`clinic-*-v3` 원격 namespace에 연결한다. 기존 `v2`는 rollback 전용으로 보존한다.

Redis URL은 `scheduling.cache.redis.require-tls=true`일 때 `rediss://`만 허용하고,
loopback이 아닌 host와 username/password가 있는 ACL URI를 요구한다. 운영 evidence
validator는 TLS/ACL, PostgreSQL lock-wait, broker lag, cache hit/miss, rollback 결과를
모두 요구한다.

장점은 serializer의 입력 경계와 rolling/rollback 경계가 서로 독립적으로 명확해지고,
운영 완료 조건을 자동 검증할 수 있다는 점이다. 단점은 v2→v3 warm-up과 namespace
정리 절차가 필요하다는 점이다.

### B. 클래스 등록을 끄고 AllowListChecker만 적용 (기각)

`requireClassRegistration(false)`는 새 타입을 빠르게 수용하지만, 등록 id의 안정성과
unknown-class rejection을 동시에 보장하기 어렵다. 현재 세 DTO만 필요한 캐시에는
허용 범위가 과도하고, 향후 실수로 타입을 넓힐 수 있으므로 채택하지 않는다.

### C. 문서와 수동 증거만 보강 (기각)

운영 절차를 문서화해도 현재 codec과 Redis URL 검증은 그대로 남는다. 테스트와 validator가
없는 상태는 이번 잔여 하드닝의 목적을 충족하지 못한다.

## 상세 설계

### 1. Redis URL 보안 정책

`RedisCacheSecurityPolicy`를 `appointment-api` 설정 패키지에 둔다. 정책은 순수하고
결정적인 `validate(url: String, requireTls: Boolean): URI` 계약을 제공한다.

`requireTls=false`이면 기존 local/test fallback을 허용한다. `true`이면 다음을 모두
검증하고 하나라도 어기면 `IllegalArgumentException`으로 시작을 중단한다.

- scheme은 정확히 `rediss`이다.
- host가 비어 있지 않고 `localhost`, `127.0.0.1`, `::1` 또는 loopback 주소가 아니다.
- user-info에 비어 있지 않은 ACL username과 password가 모두 있다.
- URI가 정상적으로 파싱되며 credential을 예외 메시지나 로그에 다시 출력하지 않는다.

`CacheConfig.redisClient`는 `spring.data.redis.url`과
`scheduling.cache.redis.require-tls`를 주입받고, Redis client를 만들기 전에 정책을
호출한다. 기본값은 `false`로 유지해 local/test가 갑자기 외부 TLS를 요구하지 않도록
한다. 운영 profile은 `true`를 명시하고 secret manager가 제공한 `rediss://username:...
@host:6380` URI를 사용한다.

정책 자체는 credential을 보관하거나 normalize한 문자열을 반환하지 않는다. Redis
client 생성에 필요한 URI만 내부적으로 사용하고, 로그에는 scheme/host 같은 비밀 없는
진단 필드만 허용한다.

### 2. Fory secure codec과 wire namespace

세 remote cache의 값 타입별 codec을 명시적으로 생성한다.

등록 대상과 고정 registration id는 다음과 같다.

| 값 타입 | registration id | 새 원격 namespace |
| --- | ---: | --- |
| `DoctorRecord` | 1001 | `clinic-doctors-v3` |
| `EquipmentRecord` | 1002 | `clinic-equipments-v3` |
| `TreatmentTypeRecord` | 1003 | `clinic-treatment-types-v3` |

`ThreadSafeFory` pool은 다음 경계를 사용한다.

- `requireClassRegistration(true)`
- `withDeserializeUnknownClass(false)`
- `withMaxDepth(32)`
- `withMaxGraphMemoryBytes(8L * 1024 * 1024)`
- compatible mode, reference tracking/copy, string compression 및 codegen은 현재
  `ForyBinarySerializer` 사용 목적과 동일한 동작을 유지한다.

등록 DTO는 `Serializable`인 현재 record만 허용한다. Java collection과 primitive/string
같은 Fory 기본 타입은 library 기본 처리를 사용하되, 새 DTO나 nested graph를 추가할
때는 등록 목록·id·wire fixture를 함께 갱신해야 한다. registration id는 자동 할당하지
않아 등록 순서 변경이 기존 v3 payload를 바꾸지 않도록 한다.

`LettuceBinaryCodecs.codec(serializer)`와 LZ4 compressor를 사용해 각 `LettuceNearCacheConfig`
에 codec을 명시한다. 기존 DSL의 기본 codec에 의존하지 않고 codec overload를 사용한다.
논리적 Spring cache name은 유지하며 remote name만 v3으로 바꾼다.

### 3. Rolling deployment와 rollback

배포 전에는 새 binary가 v3에만 쓰도록 하고 v2를 삭제하지 않는다.

1. 새 binary 한 pod를 canary로 시작한다.
2. v3 read/write round trip과 decode error counter를 확인한다.
3. cache hit/miss, v3 key count, Redis TLS/ACL 연결 결과를 evidence JSON에 기록한다.
4. 전체 배포 후 TTL(최대 1시간) 동안 v2를 보존한다.
5. 성공 확인과 rollback window 종료 후에만 runbook의 exact namespace clear로 v2를
   정리한다. `KEYS`, `FLUSHALL`, glob `DEL`은 금지하고 bounded `SCAN` + `UNLINK`만 사용한다.

Rollback은 traffic drain, 새 writer 중지, 구 binary 재기동으로 process-local L1을
   비운 뒤 v2를 사용한다. v3는 즉시 삭제하지 않고 조사 보존한다. rollback 결과와
   재기동/warm-up 완료 여부를 evidence에 기록한다.

### 4. 운영 증거 JSON과 validator

`scripts/verify-cache-rollout-evidence.sh`는 JSON 경로와 선택적 `--require-live`를
받는다. 스크립트는 JSON schema를 다음처럼 검증한다.

```json
{
  "schemaVersion": 1,
  "environment": "production",
  "capturedAt": "2026-08-10T00:00:00Z",
  "deploymentSloEvidence": true,
  "redis": { "tls": true, "acl": true, "namespace": "v3", "rollbackNamespace": "v2" },
  "postgres": { "lockWaitMs": 0 },
  "broker": { "lagSeconds": 0 },
  "cache": { "hits": 1, "misses": 0 },
  "rollback": { "result": "PASS" }
}
```

필수 필드는 누락·null·음수이면 실패한다. `--require-live`에서는
`deploymentSloEvidence=true`, `environment=production`, Redis TLS/ACL=true,
`rollback.result=PASS`를 추가로 요구한다. 로컬/CI 샘플은 `deploymentSloEvidence=false`로
검증할 수 있지만 production 완료로 사용할 수 없다. lock-wait와 lag의 허용 SLO 수치는
이 저장소가 임의로 결정하지 않으며, 배포 환경의 승인된 SLO 값을 evidence 생성기가
함께 기록해야 한다. validator는 값의 존재와 형식, 그리고 별도 승인된 threshold 파일이
주어진 경우 그 threshold 초과 여부만 판단한다.

### 5. 문서와 관측성

기존 `docs/runbooks/dependency-1.4.0-cache-migration.md`를 v2→v3 migration,
TLS/ACL 설정, canary, bounded clear와 rollback 순서에 맞게 갱신한다. 새 lesson에는
로컬 검증 결과와 production-only PENDING 항목을 분리해 기록한다. README에는 runbook과
evidence validator 진입점을 추가하되, benchmark 결과를 deployment SLO로 표현하지 않는다.

이번 범위에서 애플리케이션 metric 이름을 새로 정의하지는 않는다. 대신 기존 cache
hit/miss 및 Redis client/connection 로그를 evidence 수집기가 읽을 수 있는 필드로
매핑하고, 영향받은 metric이 없는 환경은 `PENDING`으로 남긴다. 세부 Micrometer/leader
계측은 #254에서 다룬다.

## 오류 처리와 복구

- TLS flag가 켜졌는데 local `redis://` fallback이 적용되면 client를 만들지 않고 즉시
  실패한다.
- credential이 없는 `rediss://` 또는 loopback host는 secret을 출력하지 않고 validation
  error를 반환한다.
- 등록되지 않은 Fory class, max depth/graph memory 초과, 손상된 v3 payload는 decode
  failure로 처리한다. cache adapter의 miss fallback은 유지하되, canary evidence에는
  decode error count를 별도 기록한다.
- v3 warm-up 중 오류가 나면 v2를 삭제하지 않고 canary를 drain한다. v2 기존 binary의
  rollback 절차를 수행한 뒤 원인을 수정한다.
- evidence JSON이 누락되거나 validator가 실패하면 구현/테스트가 통과해도 운영 DoD는
  `PENDING`이다.

## 검증 계획

1. `RedisCacheSecurityPolicy`의 local 허용, rediss 비-loopback ACL 허용, rediss local,
   인증 누락, 비정상 URI 거부를 단위 테스트로 고정한다.
2. `CacheConfigSecurityTest`에서 `require-tls` property를 켠 Spring context가 잘못된
   URI로 시작하지 않는지 확인한다. 테스트 double은 MockK/Spring MockK만 사용한다.
3. singleton Redis launcher를 사용하는 `NearCacheWireCompatibilityTest`에서 서로 다른
   client의 세 DTO round trip, raw v3 key 생성, v2/v1 key 부재를 확인한다. 등록되지 않은
   DTO와 과도한 graph를 serializer 단위 테스트로 거부한다.
4. evidence validator에 정상 local report, live report, 필드 누락, 음수 값, live 조건
   불충족 fixture를 주고 exit code를 확인한다.
5. `appointment-api` targeted test와 module build를 실행한 뒤, non-frontend aggregate
   build에서 frontend task가 선택되지 않는지 확인한다.
6. 실제 authenticated production Redis/PostgreSQL/broker 값과 rollback은 이 lane의
   로컬 검증 범위를 넘어선다. 해당 증거가 없으면 final DoD에 명시적으로 `PENDING`으로
   남긴다.

## 수용 기준과 DoD

- [ ] TLS/ACL 정책이 명시적 운영 flag에서 fail-closed로 동작하고 credential을 노출하지
  않는다.
- [ ] 세 cache가 등록 강제·unknown-class 거부·depth/graph memory bound를 가진 v3 codec을
  사용하며 registration id가 고정돼 있다.
- [ ] v2 rollback namespace가 보존되고 runbook에 canary, bounded clear, drain/restart,
  warm-up 순서가 있다.
- [ ] raw-key/wire compatibility, security negative path, validator negative path가
  MockK/Spring MockK와 singleton launcher 규칙에 맞는 자동 테스트로 통과한다.
- [ ] evidence validator가 local과 live를 구분하고 필요한 Redis/PostgreSQL/broker/cache/
  rollback 필드를 누락 없이 요구한다.
- [ ] 로컬 targeted test/build 및 정적 검사가 통과한다. production canary/SLO/rollback은
  승인된 live evidence가 생길 때까지 `PENDING`이다.
- [ ] 변경은 Issue #253 잔여 운영 하드닝에 한정되고 #254~#257, #204, frontend 동작을
  포함하지 않는다.

## 참고

- Apache Fory 타입 등록: <https://fory.apache.org/docs/guide/java/type_registration/>
- Apache Fory 구성: <https://fory.apache.org/docs/guide/java/configuration/>
- Issue #253 의존성 전환 설계와 기존 migration runbook은 historical baseline으로
  보존하며, 이 문서는 새 v3/운영 보안 계약의 현재 기준이다.
