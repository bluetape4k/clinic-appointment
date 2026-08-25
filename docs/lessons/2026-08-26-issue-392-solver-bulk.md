# Issue #392 solver planning fact bulk 조회 교훈

## 관찰

solver 기준 데이터는 한 번의 aggregate 로딩처럼 보이지만 의사별 repository 호출이
내부에 있으면 스케줄과 부재가 각각 `N`회 round-trip으로 늘어난다. 이 shape는 작은
fixture와 일반 기능 테스트만으로는 드러나지 않는다.

## 적용한 원칙

- 반복되는 범위 조회는 repository bulk API로 끌어올리고, 호출자는 `doctorIds` 순서로
  `orEmpty()`를 flatten해 기존 결과 계약을 보존한다.
- bulk query에도 기존 tenant subquery와 date predicate를 그대로 넣어 성능 개선이
  데이터 경계를 약화시키지 않게 한다.
- `StatementInterceptor`로 SQL 횟수를 세고 H2와 PostgreSQL 양쪽에서 같은 budget을
  확인해야 query-count 회귀를 조기에 잡을 수 있다.

## 다음 적용 대상

planning fact를 추가하는 새 solver 입력은 먼저 범위 bulk API와 query budget을 설계하고,
개별 row 조회는 단일 entity lookup처럼 실제로 필요한 경로에만 남긴다.
