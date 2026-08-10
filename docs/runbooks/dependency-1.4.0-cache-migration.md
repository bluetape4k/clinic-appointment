# bluetape4k-dependencies 1.4.0 캐시 전환 runbook

## 목적

`bluetape4k-dependencies` 1.4.0 전환으로 기본 LZ4+Fory codec 버전이 바뀌어도 구 binary와 새
binary가 서로의 Redis payload를 읽지 않도록 remote namespace를 분리한다. Spring Cache의 논리
이름은 그대로 유지하고 Redis key prefix만 다음과 같이 변경한다.

| 논리 cache 이름 | 구 remote prefix | 새 remote prefix |
| --- | --- | --- |
| `clinic-doctors` | `clinic-doctors:*` | `clinic-doctors-v2:*` |
| `clinic-equipments` | `clinic-equipments:*` | `clinic-equipments-v2:*` |
| `clinic-treatment-types` | `clinic-treatment-types:*` | `clinic-treatment-types-v2:*` |

Redis 작업은 cluster의 각 primary shard에서 실행한다. replica에는 직접 쓰지 않는다. 모든 삭제는
cursor 기반 `SCAN`이 반환한 exact key 목록을 제한된 batch로 나누어 `UNLINK`한다. `KEYS`,
`FLUSHALL`, glob 문자열을 직접 넘긴 `DEL` 또는 `UNLINK`는 사용하지 않는다.

## 배포 전 준비

1. 배포 전 Redis `INFO stats`의 `keyspace_hits`, `keyspace_misses`를 저장한다.
2. 각 primary shard에서 아래 pattern을 각각 `SCAN ... COUNT 500`으로 순회한다.
   - `clinic-doctors-v2:*`
   - `clinic-equipments-v2:*`
   - `clinic-treatment-types-v2:*`
3. 각 cursor 응답의 exact key만 운영 환경의 허용 batch 크기로 나누어 `UNLINK key1 key2 ...`로
   삭제한다. cursor가 `0`으로 돌아올 때까지 반복한다.
4. 세 v2 prefix의 `SCAN` count가 0인지 확인한다.

## Canary와 점진 배포

1. 새 binary 한 개를 canary로 배포한다.
2. application log에서 serialization/decode 오류를 확인한다.
3. 세 v2 prefix의 `SCAN` count가 실제 cache 사용과 함께 증가하는지 확인한다.
4. Redis `INFO stats`의 hit/miss delta를 배포 전 snapshot과 비교한다.
5. 오류율과 hit/miss가 허용 범위이면 새 binary를 점진 배포한다. 구 binary는 v1만, 새 binary는
   v2만 읽고 쓰므로 rolling deployment 중 payload가 교차하지 않는다.

## Rollback

1. 먼저 application traffic을 중단하고 readiness가 0이 될 때까지 drain한다. 구 binary의
   writer가 더 이상 실행 중이 아닌지 deployment 상태와 로그로 확인한다.
2. 구 binary pod를 모두 종료하거나 재기동해 process-local L1(Caffeine) 캐시를 비운다. Redis
   v1 삭제만으로는 이미 메모리에 남은 10분 L1 payload를 무효화할 수 없다.
3. 각 primary shard에서 다음 v1 pattern을 각각 `SCAN ... COUNT 500`으로 순회한다.
   - `clinic-doctors:*`
   - `clinic-equipments:*`
   - `clinic-treatment-types:*`
4. 각 cursor 응답의 exact key만 batch `UNLINK`하고, cursor가 `0`이 될 때까지 반복한다.
5. 세 v1 prefix의 `SCAN` count가 0인지 확인한 뒤 새로 시작한 구 binary를 배포한다. 배포된
   모든 pod가 새 process-local L1로 시작했는지 readiness와 rollout 상태를 확인한다.
6. 짧은 warm-up 동안 v1 prefix가 재생성되지 않는지 최종 `SCAN`으로 확인하고 traffic을
   재개한다. serialization/decode 오류와 Redis hit/miss delta도 함께 확인한다.

구 binary 배포 전에 v1을 비우는 이유는 upgrade 이전에 남은 stale payload가 rollback 후 다시
노출되는 것을 막기 위해서다. v2 namespace만 삭제하고 rollback하면 이 위험이 제거되지 않는다.

## 배포 완료 후 정리

1. 전체 rollout 성공 후 cache TTL 1시간과 별도의 관찰 window가 모두 지날 때까지 기다린다.
2. 각 primary shard에서 v1 세 prefix를 cursor 기반 `SCAN ... COUNT 500`으로 순회한다.
3. 반환된 exact key만 batch `UNLINK`한다.
4. v1 count가 0이고 v2 hit/miss와 application 오류율이 정상인지 확인해 전환을 종료한다.

## 금지 사항

- `FLUSHALL`, `FLUSHDB`, `KEYS`를 사용하지 않는다.
- glob pattern을 `DEL` 또는 `UNLINK` 인자로 직접 전달하지 않는다.
- cache name에 tenant별 동적 suffix나 `:`를 추가하지 않는다.
- rollback 과정에서 database schema-down을 수행하지 않는다.
