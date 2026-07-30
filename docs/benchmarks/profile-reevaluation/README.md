# 프로필 재평가 규모·공정성 기준

## 결론

고정 seed의 논리적 queue 모델에서 다음 세 profile이 모두 안전성 위반 없이
통과했다.

- smoke: 10개 병원, 예약 1,000건
- multi-clinic-target: 100개 병원, 예약 10,000건, 최대 병원에 40% 편중
- single-clinic-target: 한 병원에 예약 10,000건 집중

두 목표 profile의 `HELD` 논리 처리 지연 p95는 약 167초로 5분 목표 안에
들어왔고, `PROPOSED` p95는 약 1,506초로 30분 목표 안에 들어왔다.
`CONFIRMED` 변경, 중복 allocation, 교차 병원 변경, stale revision 반영,
개인정보 금지 필드 보존과 병원 starvation은 모두 0건이었다.

이 결과는 운영 DB와 CRM network를 포함한 종단 성능 수치가 아니다. 고정된
dispatcher queue 모델의 규모·공정성 회귀 기준이며, PostgreSQL·MySQL의 lock,
rollback과 query plan은 별도 통합 테스트에서 검증한다.

## 기준 소스와 실행 환경

| 항목 | 값 |
|---|---|
| benchmark commit | `8542453` |
| production baseline | `2ee920f` |
| 실행 시각 | 2026-07-30, Asia/Seoul |
| OS | macOS Darwin 25.5.0, arm64 |
| CPU | Apple M5, 10 physical / 10 logical cores |
| memory | 32 GiB |
| JVM | Oracle GraalVM 21.0.12, Java 21 LTS |
| Gatling | 3.15.1 |
| Docker | 28.4.0 |
| fixture seed | `20260730` |
| worker | 16 |
| page size | 50 |
| per-clinic claim limit | 1 |
| 측정 방식 | profile별 warm-up 1회, 측정 3회 |

규모 fixture 자체는 DB를 사용하지 않는다. 같은 코드 기준의 dialect 통합
검증에는 `postgres:18-alpine`과 `mysql:8.4` singleton launcher를 사용했다.
따라서 아래 wall-clock 값은 DB 처리량으로 해석하면 안 된다.

## 데이터셋

상태 비율은 `PROPOSED` 70%, `HELD` 25%, `CONFIRMED` 5%다.
여러 병원 profile은 첫 병원에 전체 예약의 40%를 배치하고 나머지를 균등
분산한다. 모든 profile은 다음 입력을 같은 seed로 포함한다.

- latest revision과 함께 섞인 역순 stale event
- 같은 revision의 중복 event
- 고정된 CRM 지연
- 1%의 재시도 가능한 CRM 실패
- 약 0.1%의 lease 만료

dispatcher는 `HELD` 우선순위 클래스 전체를 병원별 round-robin으로 처리한 뒤
`PROPOSED`로 넘어간다. 이 순서를 병원 내부에서만 적용하면 작은 병원의
`PROPOSED`가 큰 병원의 `HELD`보다 먼저 처리되어 p95 목표를 위반한다.

## 안전성과 실패 조건

probe는 다음 조건 중 하나라도 위반하면 HTTP 500을 반환하고 Gatling 실행을
실패시킨다.

| 조건 | 상한 |
|---|---:|
| `CONFIRMED` mutation | 0 |
| active allocation 중복 | 0 |
| cross-tenant/clinic mutation | 0 |
| stale revision mutation | 0 |
| 개인정보 persistence | 0 |
| starvation clinic | 0 |
| `HELD` 논리 지연 p95 | 300,000 ms |
| `PROPOSED` 논리 지연 p95 | 1,800,000 ms |
| 최대 queue | 최초 active queue 이하 |
| 추정 worker working set | 64 MiB 이하 |
| lease expiry rate | 2% 이하 |

개인정보 검증은 `name`, `birthDate`, `diagnosis`, `feature`, `score`,
`explanation`, `correction`, `rawProfile`을 영속 허용 key에서 금지한다.
원본 환자 식별자는 데이터셋에 만들지 않고 scope 제한 SHA-256 지문만 사용한다.

## 측정 결과

아래 논리 지연은 event 발생 시점부터 고정 worker queue에서 처리가 끝날
때까지의 모델 시간이다. wall-clock은 fixture와 assertion 자체의 실행 시간이다.

| profile | 예약 / 병원 | wall-clock median | HELD p50 / p95 / p99 | PROPOSED p50 / p95 / p99 | retry | lease expiry | 위반 |
|---|---:|---:|---:|---:|---:|---:|---:|
| smoke | 1,000 / 10 | 21 ms | 9,124 / 17,187 / 17,949 ms | 89,005 / 151,751 / 157,510 ms | 10 | 1 | 0 |
| multi-clinic-target | 10,000 / 100 | 120 ms | 88,237 / 167,345 / 174,383 ms | 874,760 / 1,505,584 / 1,561,186 ms | 100 | 10 | 0 |
| single-clinic-target | 10,000 / 1 | 108 ms | 88,328 / 167,355 / 174,385 ms | 876,365 / 1,505,559 / 1,561,147 ms | 100 | 10 | 0 |

세 측정은 동일 seed에서 같은 논리 결과를 냈다. `worst-success`는 논리
p95가 가장 큰 성공 측정이며, 세 profile 모두 median과 같은 판정을 받았다.
추정 최대 working set은 모두 409,600 bytes였다.

## 실패 이력

첫 multi-clinic-target 실행은 `HELD` p95 1,006,291 ms로 실패했다.
병원별 queue 안에서는 `HELD`가 먼저였지만, 작은 병원의 `PROPOSED`가 큰
병원의 남은 `HELD`와 같은 순환 단계에 섞인 것이 원인이었다. 우선순위
클래스 전체에서 `HELD`를 먼저 순환하도록 고친 뒤 p95가 167,345 ms로
낮아졌다.

이 실패 결과도 삭제하지 않고 원시 Gatling report 위치를 아래에 남긴다.

## 실행 명령

Gatling Gradle plugin의 공식 non-interactive 선택 옵션은 `--simulation`이다.

```bash
./gradlew :appointment-api:gatlingRun \
  --simulation io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=smoke

./gradlew :appointment-api:gatlingRun \
  --simulation io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=multi-clinic-target

./gradlew :appointment-api:gatlingRun \
  --simulation io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=single-clinic-target
```

## 보고서 위치

요약 JSON:

- `appointment-api/build/reports/performance/profile-reevaluation/smoke.json`
- `appointment-api/build/reports/performance/profile-reevaluation/multi-clinic-target.json`
- `appointment-api/build/reports/performance/profile-reevaluation/single-clinic-target.json`

이번 측정의 원시 Gatling HTML·`simulation.log`:

- smoke 성공:
  `appointment-api/build/reports/gatling/profilereevaluationscalesimulation-20260730151806476/`
- multi-clinic-target 최초 실패:
  `appointment-api/build/reports/gatling/profilereevaluationscalesimulation-20260730151651416/`
- multi-clinic-target 성공:
  `appointment-api/build/reports/gatling/profilereevaluationscalesimulation-20260730151747538/`
- single-clinic-target 성공:
  `appointment-api/build/reports/gatling/profilereevaluationscalesimulation-20260730151757542/`

`build/` 산출물은 Git에 보존하지 않는다. CI나 다른 장비에서 재실행하면
timestamp 디렉터리가 새로 생기므로, 판정할 때는 실행 로그가 가리키는 해당
디렉터리와 요약 JSON을 함께 보관한다.

## 기준선과 재실행 조건

- 안전성 위반 허용치는 항상 0이며 오차를 적용하지 않는다.
- 논리 p95는 `HELD` 5분, `PROPOSED` 30분을 넘으면 실패다.
- wall-clock은 장비 차이를 고려해 같은 장비의 baseline median 대비 +20%까지
  경고, +35%를 실패로 본다.
- GC, 백그라운드 부하 또는 thermal throttling이 확인된 실행만 폐기할 수 있다.
  결과가 느리다는 이유만으로 재실행해 더 좋은 값만 선택하지 않는다.
- seed, 상태 비율, 병원 편중, worker와 page 설정을 바꾸면 기존 baseline과
  직접 비교하지 않고 새 기준선을 만든다.
- 운영 DB image, CRM latency 분포 또는 worker 배포 구성이 바뀌면 이 fixture와
  별도로 종단 부하 시험을 수행한다.
