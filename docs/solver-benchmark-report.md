# Solver Benchmark Report

> 실행일: 2026-04-19 | 환경: Apple M4 Pro, JDK 25, Timefold Solver

## 요약

| 시나리오 | 의사 | 예약 | 일수 | Time Limit | 실행 시간 | Best Score | Feasible | Move Speed |
|----------|------|------|------|------------|-----------|------------|----------|------------|
| 소규모 | 2 | 10 | 5 | 10s | **5,030ms** | `0hard/0soft` | ✅ | 133,758/sec |
| 중규모 | 5 | 30 | 5 | 15s | **8,270ms** | `0hard/-500soft` | ✅ | 100,613/sec |
| 대규모 | 10 | 100 | 10 | 30s | **16,163ms** | `0hard/-2000soft` | ✅ | 116,135/sec |

## Baseline 기준선

회귀 감지용 assertion 기준값:

| 시나리오 | 최대 허용 시간 | 최소 Soft Score |
|----------|---------------|----------------|
| 소규모 | 15,000ms | -100 |
| 중규모 | 20,000ms | -500 |
| 대규모 | 40,000ms | -2,000 |

- **Hard Score ≥ 0**: 모든 시나리오에서 하드 제약 위반 없음 (필수)
- **Feasible = true**: 소/중규모 필수, 대규모는 score만 검증

## 문제 규모 (Problem Scale)

| 시나리오 | Entity | Variable | Approx Value | Problem Scale |
|----------|--------|----------|--------------|---------------|
| 소규모 | 10 | 30 | 25 | 3.57 × 10²² |
| 중규모 | 30 | 90 | 28 | 3.95 × 10⁷⁹ |
| 대규모 | 100 | 300 | 38 | 3.37 × 10³²⁵ |

## 해석

### 소규모 (의사 2명, 예약 10건)
- **완벽한 해** 달성 (`0hard/0soft`) — 모든 제약 만족
- 5초 내 최적해 도달, 여유 시간 50% 미사용
- Search space가 작아 Local Search로 충분

### 중규모 (의사 5명, 예약 30건)
- Hard 제약 전부 해소, Soft 위반 -500 (선호도 미충족 일부 존재)
- Move evaluation 10만/sec — CPU 바운드 최적화 효과적
- Time limit 15초 중 8.2초 사용 (조기 수렴)

### 대규모 (의사 10명, 예약 100건)
- Hard 제약 전부 해소, Soft 위반 -2000 (합리적 범위)
- 116만 step 탐색, 16초 사용 (30초 제한의 54%)
- Problem scale 10³²⁵ 대비 feasible 해를 빠르게 달성

## Solver 설정

```kotlin
AppointmentSolverConfig.createFactory(timeLimit = Duration.ofSeconds(N))
```

- Algorithm: Construction Heuristic → Local Search (Late Acceptance)
- Environment: `PHASE_ASSERT`
- Move Thread: NONE (단일 스레드)
- Random Seed: 0 (재현 가능)

## 제약 조건 (Constraint Provider)

| 종류 | 제약 | Weight |
|------|------|--------|
| Hard | 의사 시간 겹침 금지 | -1 per conflict |
| Hard | 운영 시간 외 배정 금지 | -1 per violation |
| Hard | 의사 스케줄 외 배정 금지 | -1 per violation |
| Hard | 동일 환자 시간 겹침 금지 | -1 per conflict |
| Soft | 연속 슬롯 선호 | reward |
| Soft | 오전 시간대 선호 | reward |
| Soft | 균등 배분 선호 | penalty on imbalance |

## CI 통합 가이드

벤치마크 테스트는 `@Tag("benchmark")`로 분류되어 일반 CI에서 제외됩니다.

```bash
# 벤치마크만 실행
./gradlew :appointment-solver:test --tests "*.benchmark.*"

# 일반 테스트 (벤치마크 제외)
./gradlew :appointment-solver:test -Dexclude.tags=benchmark
```

## 이력

| 날짜 | 변경 | 소규모 | 중규모 | 대규모 |
|------|------|--------|--------|--------|
| 2026-04-19 | 초기 baseline 정의 | 5.0s / 0/0 | 8.3s / 0/-500 | 16.2s / 0/-2000 |
