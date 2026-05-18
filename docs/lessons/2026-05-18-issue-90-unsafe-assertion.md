# Issue #90: AppointmentService 의 unsafe `!!` assertion 제거

## 근본 원인

`AppointmentService`의 세 곳에서 `!!` (force-unwrap) 연산자를 사용하여
null 발생 시 `KotlinNullPointerException`을 던짐. 이 예외는 `GlobalExceptionHandler`에
매핑되지 않아 HTTP 500 Internal Server Error로 노출됨.

## 수정 내용

| 위치 | Before | After | 예외 타입 | HTTP |
|------|--------|-------|----------|------|
| `create()` — `saved.id` | `saved.id!!` | `saved.id.requireNotNull("saved.id")` | `IllegalArgumentException` | 400 |
| `updateStatus()` — 후속 조회 | `findByIdOrNull(id)!!` | `?: throw NoSuchElementException(...)` | `NoSuchElementException` | 404 |
| `cancel()` — 후속 조회 | `findByIdOrNull(id)!!` | `?: throw NoSuchElementException(...)` | `NoSuchElementException` | 404 |

## 검증

- appointment-api: 85 tests passing
- appointment-core: 226 tests passing
- 총 311 tests, 44초

## 교훈

1. `!!`는 항상 금지 — 어떤 상황에서도 적절한 예외 타입으로 대체해야 함
2. `requireNotNull`은 반드시 `io.bluetape4k.support.requireNotNull` 사용 (stdlib 아님)
3. "not found" 시나리오는 `NoSuchElementException` → HTTP 404 매핑 활용
4. 의존성 해석 문제: Sonatype snapshot BOM이 릴리스 버전을 참조할 수 있음 —
   로컬 테스트 시 `mavenLocal()` 추가로 우회 가능하나 커밋에 포함하면 안 됨
