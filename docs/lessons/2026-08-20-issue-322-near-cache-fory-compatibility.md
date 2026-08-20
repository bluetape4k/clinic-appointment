# Issue #322 NearCache Fory 호환성 검증 lesson

## 배경

`appointment-api`의 Redis v3 NearCache는 등록을 강제한 `ThreadSafeFory` pool과
LZ4 압축 serializer를 사용한다. 기존 `issue-253/1.3.1` fixture는 과거
payload의 회귀 기준으로 보존해야 하지만, 현재 실행 graph는
`bluetape4k-dependencies=1.4.0`, `fory-core=1.5.0`, `fory-kotlin=1.5.0`이다.
Issue #322는 이 두 버전 좌표를 섞지 않고 legacy read, 현재 write, codegen 결과,
압축 경로와 rollback namespace를 식별 가능한 증거로 고정하는 작업이다.

## 결정

- 기존 `appointment-api/src/test/resources/cache/issue-253/` fixture와
  provenance는 변경하지 않는다.
- `cache/issue-322/fixture-provenance.properties`에 source fixture commit/hash와
  현재 resolved dependency 좌표를 함께 기록한다.
- `NearCacheForyCompatibilityTest`는 현재 Fory가 legacy DTO payload를 복원하는지,
  등록된 DTO가 codegen serializer 또는 `ObjectSerializer` interpreter fallback 중
  어느 경로를 택했는지 로그와 assertion으로 남긴다. codegen 설정이 켜져 있다는
  사실만으로 generated serializer를 가정하지 않는다.
- `NearCacheWireCompatibilityTest`는 registration id, secure type rejection,
  depth/graph bound, `CompressableBinarySerializer`의 LZ4 compressor와
  `clinic-*-v3` namespace를 함께 고정한다.
- rollback은 기존 runbook의 namespace 격리를 따른다. 새 writer는 v3만 사용하고,
  v2 payload를 새 codec이 역방향으로 읽는다는 가정을 두지 않는다.

## 구현 결과

- 1.3.1 의사·장비·진료 유형 fixture 3종을 현재 1.4.0 runtime에서 DTO로 복원했다.
- 현재 Fory 1.5.0 실행에서는 codegen 설정과 DTO 지원 조건이 모두 활성화되어도
  `ObjectSerializer` interpreter fallback이 관찰됐다. 테스트는 이 결과를
  `codegen=interpreter-fallback`으로 출력하며, generated `ForyRefCodec_*` 결과도
  허용된 명시적 상태로 분류한다.
- 4 worker × 2 round의 `ThreadSafeFory` pool serializer round-trip을 통과시켰다.
- 등록 id `1001`/`1002`/`1003`, secure unknown-class rejection, 최대 depth `32`,
  graph memory `8 MiB`, LZ4 compressor와 v3 remote prefix를 회귀 검증했다.
- 압축 경로의 최소 반복 근거는 raw `2621` bytes 대비 compressed `657` bytes,
  64회 평균 `657` bytes, 최근 전체 모듈 실행에서 측정된 시간 `2,544,750 ns`였다.
  이 수치는 실행 환경에
  종속된 방향성 근거이며 production latency나 GC allocation SLO가 아니다.

## 검증

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.config.NearCacheForyCompatibilityTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest' \
  --no-build-cache --console=plain
```

결과:

```text
SUCCESS: Executed 9 tests in 4.1s
BUILD SUCCESSFUL
```

모듈 전체 회귀도 다음 명령으로 `829 tests`, `3 skipped`, 실패·오류 `0`을 확인했다.

```bash
./gradlew :appointment-api:test --no-build-cache --console=plain
→ SUCCESS: Executed 829 tests in 2m 35s (3 skipped)
```

실행 XML의 `system-out`에는 다음 구조화된 근거가 남는다.

```text
Issue #322 codegen=interpreter-fallback expected=generated-or-interpreter-fallback
Issue #322 compression-evidence={"iterations":64,"rawBytes":2621,"compressedBytes":657,"averageCompressedBytes":657,"elapsedNanos":2544750,"allocationPath":"byte-array-compatibility"}
```

## TDD와 발견 사항

첫 RED 실행에서는 issue-322 provenance resource가 없어 provenance 검증이 실패했고,
생성 전 serializer 기대값과 실제 `ObjectSerializer` fallback이 달라 codegen 결과
불일치도 드러났다. resource를 추가하고 generated/fallback을 구분하는 assertion으로
계약을 좁힌 뒤 GREEN 9건을 확인했다. 따라서 fallback은 조용히 무시되지 않고
테스트 출력·XML evidence에서 확인할 수 있다.

Fory JIT가 테스트 JVM 종료 시점에 작업 executor 종료 경고를 출력할 수 있지만,
해당 실행의 test task는 성공했다. 이 경고를 production 장애나 성능 수치로 해석하지
않는다.

## 다음 guard와 제외 범위

- Fory 또는 bluetape4k BOM을 갱신할 때 issue-322 provenance의 resolved 좌표와
  `codegen.expected`를 함께 갱신하고 targeted test를 다시 실행한다.
- fixture hash와 source commit을 바꾸려면 새 compatibility fixture와 별도 migration
  판단을 기록한다.
- generated serializer 강제, 무검증 serializer 교체, trusted external writer를
  전제로 한 object graph 확대는 이 작업에서 수행하지 않았다.
- Redis 7.2/8.8 image matrix, 전역 lockfile, production Redis SLO와 JMH 수준
  allocation/GC benchmark는 각각 이미 분리된 후속 범위 또는 운영 검증으로 남긴다.

## 문서 검수

| 항목 | 결과 |
|---|---|
| SPW-01 문제·범위·독자 명시 | PASS |
| SPW-02 구조와 근거 연결 | PASS |
| SPW-03 용어·명령 일관성 | PASS |
| SPW-04 결정·제외 범위 기록 | PASS |
| SPW-05 실제 검증 결과 기록 | PASS |
