# Issue #309 설계 문서 6면 검토

## 검토 대상과 결론

- 대상: `docs/superpowers/specs/2026-08-19-issue-309-composite-repository-transaction-design.md`
- 기준: 승인된 설계안 2, Issue #309 본문, 작업 브랜치의 실제 Kotlin/Gradle 소스
- 검토 방식: 요구사항·아키텍처·보안·성능·운영·사용자 API 관점의 독립 점검 후 통합
- 결과: **CLEAR**
- P0: 0건
- P1: 0건
- P2: 2건(구현 계획에서 확인할 정밀화 항목, 현재 설계를 막지 않음)

## 1. 요구사항·수용 기준 관점

| 점검 | 결과 | 근거 |
| --- | --- | --- |
| Composite 유지 | 통과 | 2.1, 3절에서 `COMPOSITE-DSL`과 공개 API 보존을 명시했다. |
| bluetape4k-exposed 우선 | 통과 | `LongJdbcRepository`와 Spring Data DAO API를 구분하고 적용 후보를 한정했으며, ID 없는 append 포트는 별도 `APPEND-DSL`로 남겼다. |
| transaction 책임 | 통과 | `SPRING-TRANSACTION`, `SPLIT-TRANSACTION`, `BOOTSTRAP-BOUNDARY`를 분리했다. |
| PostgreSQL/H2 검증 | 통과 | 7절에 singleton PostgreSQL과 H2 wiring/basic CRUD 매트릭스를 고정했다. |
| issue 범위 제외 | 통과 | 8절에 DAO 전환·전 repository 변환·외부 실행 모델 변경을 제외했다. |

판정: P0/P1 없음.

## 2. 아키텍처·설계 관점

현재 record/Table DSL에는 `LongJdbcRepository`가 맞고, `ExposedJdbcRepository`는
DAO Entity 계약이라는 저장소 확인 결과를 설계에 반영했다. Composite의 scope/lock/
batch 불변식을 공통 CRUD로 숨기지 않는 경계도 명확하다. Spring proxy와 Exposed
current transaction의 관계, self-invocation 금지, split flow의 세 단계 순서를
설계에 포함했다.

판정: 통과. P0/P1 없음.

## 3. 보안·데이터 무결성 관점

tenant/clinic/patient 범위와 idempotency scope를 보존하고, 서로 다른 Database
handle을 섞지 않는 규칙을 명시했다. password 검증과 외부 동작을 하나의 긴
transaction으로 합치지 않도록 했다. transaction rollback과 replay finalization을
별도 테스트 대상으로 적은 점은 중복 side effect와 부분 commit을 줄인다.

판정: 통과. P0/P1 없음.

## 4. 성능·안정성 관점

batch insert, lock, `SKIP LOCKED`, query-plan DSL을 보존하여 단순화로 인한
성능 회귀를 피한다. read-only, 짧은 split transaction, singleton launcher와
실행 직렬화 규칙을 테스트 매트릭스에 포함했다. 모든 service를 하나의 transaction으로
감싸지 않으므로 connection 보유 시간이 늘어나는 위험도 제한된다.

판정: 통과. P0/P1 없음.

## 5. 운영·검증 관점

H2와 PostgreSQL의 검증 목적을 나누고, `@Testcontainers`를 금지하는 저장소 규칙과
bluetape singleton launcher를 명시했다. Gradle targeted test, module test, static
검색, `git diff --check`를 DoD에 포함했다. CI/로컬 명령은 구현 계획에서 실제
테스트 클래스와 순서를 확정해야 한다.

판정: 통과.

P2-1: 구현 계획에서 현재 singleton launcher의 정확한 fixture/helper 이름과
직렬 실행 명령을 파일 단위로 고정할 것.

## 6. 라이브러리 사용자·API 관점

기존 `save`와 특수 메서드의 이름·결과를 보존하고, 공통 interface 도입이 public
bean 이름이나 생성자 호환성을 깨지 않도록 했다. DAO Entity 전환을 별도 issue로
분리하여 사용자가 잘못된 Spring Data API를 기대하지 않게 했다. KDoc에 caller-owned
transaction과 guard 계약을 남기도록 요구했다.

판정: 통과.

P2-2: 구현 시 `LongJdbcRepository`의 default `saveAll`을 사용하지 않는 메서드는
왜 명시적 insert/update를 유지하는지 KDoc 또는 lesson에 한 줄 더 고정할 것.

## 7. 통합 판정과 SPW/KO gate

### 통합 판정

- 요구사항 충족: 통과
- 범위 경계: 통과
- 실패·rollback·replay 계약: 통과
- 테스트 가능성: 통과(launcher/helper 이름은 계획 단계에서 확정)
- API/스키마 호환성: 통과
- 잔여 P2: 2건, 계획에서 해소 가능

### Superpowers Technical Artifact Gate

- SPW-01 목적·범위: 통과
- SPW-02 근거 ledger: 통과(이슈 URL, branch/base, 현재 소스 관찰)
- SPW-03 대안·결정: 통과(승인된 2안과 거부 이유)
- SPW-04 실패·검증: 통과
- SPW-05 독립 검토·판정: 통과(본 문서)

### Korean Naturalness Gate

- KO-01 문장 주어/서술어: 통과
- KO-02 직역투·과도한 피동: 통과
- KO-03 용어 일관성: 통과(`caller-owned`, `current transaction`, API 식별자는 고정)
- KO-04 표·목록 병렬성: 통과
- KO-05 명령·식별자 보존: 통과
- KO-06 독자 실행 가능성: 통과
- KO-07 맞춤법·문장부호·diff check: 통과

최종 결론: **CLEAR — 설계 문서는 구현 계획 단계로 진행 가능**.
