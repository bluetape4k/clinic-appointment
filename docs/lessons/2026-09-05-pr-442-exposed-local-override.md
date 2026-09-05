# Exposed 플러그인과 실행 라이브러리의 로컬 버전 재정의

## 실패와 원인

[PR #442](https://github.com/bluetape4k/clinic-appointment/pull/442)는 Exposed
플러그인만 1.4.0에서 1.5.0으로 변경했다. 현재 develop을 반영한 뒤에도
`scripts/verify-dependency-contract.sh`는 1.4.0을 기대해서 실패했고,
`./gradlew help`는 1.5.0 플러그인 마커 POM의 검증 해시 누락으로 실패했다.
이 단계의 실패는 애플리케이션 API 비호환을 의미하지 않는다.

## 결정

이 저장소는 예제이므로 중앙 catalog를 기본으로 사용하되 필요한 버전을
로컬에서 재정의한다. 중앙 라이브러리의 새 릴리스를 선행 조건으로 두지 않는다.

- catalog의 `exposed`를 플러그인과 `org.jetbrains.exposed:exposed-bom`이 함께 사용한다.
- 루트와 하위 프로젝트의 dependency management에 Exposed BOM을 마지막으로 가져온다.
  직접 참조뿐 아니라 DAO, R2DBC, migration 등 전이 라이브러리도 1.5.0으로 선택된다.
- Exposed 잠금만 선택적으로 갱신하고, 새 플러그인과 전이 artifact의 SHA-256을 등록한다.
  strict locking과 dependency verification은 유지한다.
- `io.github.bluetape4k.exposed` 라이브러리 버전과 Timefold 버전은 변경하지 않는다.

중앙 플랫폼에서 유입되는 `exposed-bom:1.4.0`은 잠금 파일에 메타데이터로 남는다.
실제 Exposed JAR는 모든 잠긴 구성에서 1.5.0이다. 따라서 전체 그래프의 모든
BOM까지 1.5.0이라고 표현하지 않는다.

## 검증과 재발 방지

수정 전 검사 스크립트와 `./gradlew help`의 실패를 각각 재현했다.
수정 후 `verifyDependencyGovernance`가 모든 해석 가능한 구성을 통과했다.
Core 579개, Event 178개, Notification 263개, Messaging 147개, Solver 98개가
통과했고, API는 906개 중 903개가 통과했다. API 기본 H2 구성에서 PostgreSQL/MySQL
전용 검증 2개와 운영 MySQL 접속 정보가 필요한 검증 1개는 조건부 비활성이었다.
`detekt`도 통과했다. 비활성이었던 성능·EXPLAIN 검증 2개는
`-Dspring.profiles.active=test,test-postgresql`로 별도 실행해 모두 통과했다.
운영 MySQL 접속 검증은 실행하지 않았으며, CI의 최종 판정은 PR 본문에 기록한다.

검사 스크립트는 모든 추적 중인 잠금 파일의 Exposed 실행 artifact를 검사하고,
core, Spring Boot 4 starter, JDBC migration의 실제 선택 버전도 확인한다.
다음 버전 전환에서도 플러그인 문자열 변경만으로 완료하지 않고, BOM 재정의,
잠금, 검증 해시, 실제 DB 테스트를 함께 확인한다.

리뷰에서는 catalog의 기존 “중앙 BOM만 단일 기준”이라는 주석이 실제 로컬
재정의와 모순됨을 확인해 수정했다. 버전 전환 시 실행 구성뿐 아니라 같은 파일의
정책 주석도 확인해야 이후 작업자가 정상적인 재정의를 제거하지 않는다.

## 출처

- 2026-09-05 Maven Central POM 직접 확인:
  [Exposed BOM 1.5.0](https://repo.maven.apache.org/maven2/org/jetbrains/exposed/exposed-bom/1.5.0/exposed-bom-1.5.0.pom).
  배포 좌표와 관리되는 모듈의 버전 확인에 사용했다.
- 로컬 근거: `gradle/libs.versions.toml`, `build.gradle.kts`, 각 `gradle.lockfile`,
  `gradle/verification-metadata.xml`, `scripts/verify-dependency-contract.sh`.
