# Issue #392 solver planning fact bulk benchmark

## 실행 조건

- fixture: 한 clinic, 의사 100명, 의사별 스케줄 2행
- 부재: 대상 날짜 1행과 범위 밖 1행
- 명령:

```bash
./gradlew :appointment-core:test \
  --tests 'io.bluetape4k.clinic.appointment.repository.DoctorRepositoryBulkTest' \
  --no-daemon --console=plain
```

## 결과

| DB | 의사 수 | 경과 시간 | 스케줄 SELECT | 부재 SELECT |
|---|---:|---:|---:|---:|
| H2 | 100 | 85 ms | 1 | 1 |
| PostgreSQL | 100 | 5 ms | 1 | 1 |

이 결과는 의사 수가 증가해도 planning fact 조회 query shape가 고정되는지 확인하는
smoke benchmark다. 운영 SLO나 동시 solver 처리량을 의미하지 않는다.
