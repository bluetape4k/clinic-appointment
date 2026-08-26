# Issue #395 messaging fixture·assertions lesson

## 상황

`appointment-messaging` 테스트 일부가 매번 `SchemaUtils.create`로 schema를 만들고
generic Kotlin/JUnit assertion과 local `check`를 섞어 사용했다. 이 방식은 Exposed
fixture reset 규칙과 `bluetape4k-assertions` 사용 계약을 테스트마다 다르게 만들고,
예제 사이트가 제공하는 재사용 경계를 흐린다.

## 결정

- 일반 H2/PostgreSQL fixture는 `SchemaUtils.createMissingTablesAndColumns(...)` 뒤에
  의존 테이블의 역순 `deleteAll()`을 호출한다.
- query-plan과 writer scope 테스트는 `shouldBeEqualTo`, `shouldBeTrue`,
  `assertFailsWith`로 통일한다.
- 20,000-row PostgreSQL claim benchmark는 fresh schema 생성 비용과 query plan을
  격리해야 하므로 직접 `SchemaUtils.create`를 유지하되, KDoc와 compliance guard에
  예외를 명시한다.
- 새 fixture helper나 assertion abstraction은 만들지 않고 기존 bluetape4k API와
  singleton Testcontainers launcher를 재사용한다.

## 결과와 검증

- compliance test는 변경 전 직접 create/generic assertion을 검출하는 RED를 기록했다.
- 변경 후 fixture/assertion targeted test와 PostgreSQL/Kafka integration test가
  통과했다.
- full `:appointment-messaging:test`, `:appointment-messaging:check`, 문서·정적
  검증 결과는 최종 review artifact에 exact source tip과 함께 기록한다.

## 다음 guard

새 messaging 테스트를 추가할 때는 먼저 compliance test의 예외 목록을 검토한다.
성능 측정이 아니라면 incremental schema와 reverse `deleteAll()`을 사용하고,
값 비교·boolean·예외 검증은 `io.bluetape4k.assertions`에서 가져온다. 예제 코드의
재사용을 위해 새 assertion wrapper와 ad-hoc fixture lifecycle을 만들지 않는다.

## 문서 작성 점검

- [x] SPW-01: 상황·결정·재사용 경계를 source와 Issue에서 고정했다.
- [x] SPW-02: RED/GREEN과 다음 guard를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 code token을 보존했다.
- [ ] SPW-04: 최종 review·CI evidence를 read-back한다.
