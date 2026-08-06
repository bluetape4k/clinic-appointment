# Issue #41 PostgreSQL `kotlinx-benchmark` 위험 등록부

| ID | 위험 | 영향 | 완화/검증 | 잔여 위험 |
|---|---|---|---|---|
| R1 | CI runner에서 Docker image를 pull하지 못함 | benchmark job 실패 | bluetape4k singleton launcher, smoke/full 분리, 명확한 artifact 로그 | 외부 registry/runner 장애는 코드로 제거 불가 |
| R2 | production Flyway migration과 benchmark seed가 어긋남 | 실제 claim 경로를 측정하지 못함 | `classpath:db/migration/postgresql` 전체 실행, V1–V22 schema history 확인 | 신규 migration 추가 시 baseline 재생성 필요 |
| R3 | Hikari 또는 Exposed connection 누수 | fork/반복 실행 불안정 | `@TearDown`에서 pool close, 실제 DataSource 경로 compile/run 검증 | JVM fork 강제 종료 시 cleanup 로그 의존 |
| R4 | `kotlinx-benchmark` 출력 구조 변경 | collector가 잘못된 수치를 선택 | plugin 0.4.17 고정, JSON validator fail-closed, task listing 확인 | plugin 업그레이드 때 parser 검토 필요 |
| R5 | PostgreSQL percentile 변동성 | 수치가 실행마다 달라짐 | fixed seed/row count, full은 nightly, threshold gate 금지 | 하드웨어·container noise는 남음 |
| R6 | chart와 결과 JSON drift | 문서 신뢰성 저하 | chart는 baseline JSON에서만 생성, SVG/PNG audit, source-equivalent locale 검사 | baseline 갱신 시 두 locale 재생성 필요 |
| R7 | benchmark 모듈이 coverage aggregate에 포함됨 | CI 시간/coverage 왜곡 | root Kover 설정과 workflow needs에서 benchmark 분리 | 신규 aggregate 자동화가 바뀌면 재점검 |
| R8 | 공개 constructor 범위 확대 | API surface 증가 | 이미 public class의 실제 benchmark wiring만 노출, 기본값/불변식 유지 | 후속 API 안정성 검토 필요 |
| R9 | benchmark가 production SLO로 오해됨 | 잘못된 운영 의사결정 | README/chart/PR에 “benchmark evidence, not deployment SLO” 명시 | 독자가 문서를 생략할 가능성 |

## 중단 및 롤백 기준

- migration 실패, schema history 불일치, validator가 percentile을 검증하지 못하는
  경우에는 수치를 문서화하지 않고 구현 단계로 되돌린다.
- Docker 장애만 재현되는 경우 코드 변경 없이 benchmark job을 재시도하고, PR의
  기능 검증과 성능 artifact를 분리해 기록한다.
- chart geometry 또는 locale parity가 실패하면 PNG/README를 배포하지 않고
  generator와 baseline을 함께 수정한다.
