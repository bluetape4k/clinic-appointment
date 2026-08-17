# Issue #308: CI PostgreSQL 단일 행렬 교훈

## 상황

기존 `ci.yml`의 API와 Flyway 검증, `nightly.yml`의 API 검증은 H2·PostgreSQL·MySQL
matrix를 각각 실행했다. 현재 예제의 통합 검증 기준은 PostgreSQL이며, CI matrix가
세 DB를 동등한 지원 대상으로 보이게 만들었다.

## 결정

clinic-appointment의 일상 CI와 nightly CI에서 API 및 Flyway 검증 대상을
PostgreSQL 하나로 고정한다. 두 workflow의 matrix에는 `PostgreSQL`과
`test,test-postgresql` 프로파일만 남긴다.

## 근거

- 이 예제의 공식 통합·동시성·outbox 검증 기준은 실제 PostgreSQL singleton
  launcher를 사용하는 테스트다.
- H2와 MySQL을 같은 CI 행렬에서 반복 실행하면 예제의 기준 데이터베이스가
  분산되고, 지원 범위와 단순 검증 범위가 혼동된다.
- 이번 변경은 workflow 범위에 한정한다. 애플리케이션 코드, Gradle 의존성,
  Flyway 파일, 테스트 fixture의 H2/MySQL 잔존 여부는 Issue #308의 별도
  후속 범위로 남긴다.

## 결과

일반 CI와 nightly CI의 API/Flyway 행렬은 PostgreSQL 한 행만 실행한다. README는
공식 지원 데이터베이스가 PostgreSQL 하나임을 명시한다.

## 놓치기 쉬운 점

workflow matrix를 단일 행으로 줄여도 저장소 안의 H2/MySQL profile, migration,
fixture가 자동으로 삭제되는 것은 아니다. 이를 함께 제거하려면 Issue #308의
별도 production-behavior 변경 범위와 테스트 계획이 필요하다.

## 재발 방지 규칙

1. CI 또는 nightly의 API/Flyway DB 행렬을 늘릴 때는 Issue #308의 PostgreSQL
   단일 지원 정책과 별도 승인 여부를 먼저 확인한다.
2. PostgreSQL 통합 테스트는 `test,test-postgresql` 프로파일과 repository의
   singleton launcher를 사용한다.
3. workflow 변경 후 `actionlint`, YAML 파싱, H2/MySQL matrix 참조 검색을
   함께 실행한다.

## 검증

- `.github/workflows/ci.yml`의 API/Flyway matrix: PostgreSQL 단일
- `.github/workflows/nightly.yml`의 API matrix: PostgreSQL 단일
- PostgreSQL 외 DB 항목을 workflow에서 검색한 결과: 0건
