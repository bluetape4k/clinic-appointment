# Issue #315 후속 검증에서 남긴 교훈

## 배경

Issue #315의 첫 파일럿은 H2/PostgreSQL 단일 worker benchmark와 transaction
wiring을 검증했지만, pool contention·full-row 비용·authenticated route
인가 경계는 열어 두었다. 후속 작업은 production source/API/runtime
dependency를 건드리지 않고 이 세 경계를 확인하는 데 집중했다.

## 결정

- pool contention은 실제 PostgreSQL Hikari pool에서만 판정한다. 기본 H2
  실행의 `NOT_TESTED`를 성능 근거로 승격하지 않는다.
- `bluetape4k-exposed-spring-boot-jdbc:1.12.1`의 현재 repository 경로는
  DAO entity 전체를 읽는다. column-level projection capability를 새
  adapter로 감싸지 않고 `NOT_AVAILABLE`로 기록한다.
- projection 결과가 tenant 범위 안에 있어도 인가를 대신하지 않는다.
  `TenantClinicAccessChecker`의 tenant·clinic allow-list·workforce role
  검증을 별도로 통과해야 한다.

## 결과와 검증

PostgreSQL `maximumPoolSize=2`에서 4개 worker가 동시에 시작했고, 먼저
점유한 2개 커넥션을 해제한 뒤 네 조회가 모두 완료됐다. 결과 equality는
유지됐고 elapsed 값은 `min=11,791,625ns`, `median=12,972,250ns`,
`p95=16,407,542ns`였다. repository SQL은 `Clinics` 컬럼 8개 중 8개를
선택했다. 기존 checker는 허용 clinic 1건을 통과시키고 다른 clinic·tenant·
role을 각각 차단했다.

재현 명령과 raw 출력은
[`docs/benchmarks/issue-315-spring-data-projection/2026-08-25/summary.ko.md`](../benchmarks/issue-315-spring-data-projection/2026-08-25/summary.ko.md)에
기록했다. H2와 PostgreSQL 프로파일 모두 10개 테스트가 통과했다.

## 예상 밖의 점

pool contention을 통과해도 운영 채택 조건이 충족되지는 않았다. 현재
artifact의 full-row DAO 경로는 필요한 컬럼만 읽는 projection을 증명하지
않고, 실제 authenticated route wiring도 없다. 따라서 기존 candidate가
측정한 성능 열세와 함께 운영 채택 보류 판정이 유지된다.

## 다음 작업의 guard

1. 단일 worker elapsed를 pool concurrency 증거로 사용하지 않는다. 최소한
   실제 pool 크기보다 큰 동시 호출과 connection wait/release를 기록한다.
2. full-row DAO를 production projection으로 옮기기 전에 SQL selected-column
   집합과 민감 필드 접근을 별도 계약으로 고정한다.
3. tenant predicate, clinic allow-list, role 검증을 하나의 “projection
   권한” 주장으로 합치지 않는다. 실제 인증 route가 연결될 때 route
   integration test를 추가한다.
