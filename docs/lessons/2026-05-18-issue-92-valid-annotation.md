# Issue #92 — @Valid 어노테이션 누락 수정

## 근본 원인

Controller의 `@RequestBody` 파라미터에 `@Valid`가 없어서 DTO에 Bean Validation 제약 조건을 선언해도 실제로 검증이 실행되지 않았다. 잘못된 요청이 서비스 레이어까지 도달하여 예측 불가능한 에러를 유발했다.

## 결정 사항

1. 모든 `@RequestBody` 파라미터에 `@Valid` 추가 (6개)
2. DTO에 적절한 제약 조건 어노테이션 추가 (`@Positive`, `@NotBlank`, `@NotNull`, `@FutureOrPresent`)
3. `GlobalExceptionHandler`에 `MethodArgumentNotValidException` + `HttpMessageNotReadableException` 핸들러 추가
4. Kotlin data class에서 `@field:` use-site target 필수 사용

## 교훈

### Kotlin data class + Bean Validation

- Kotlin `data class` 생성자 프로퍼티에 어노테이션을 달 때 **`@field:`** 접두어가 없으면 어노테이션이 생성자 파라미터에만 적용되어 Bean Validation이 무시된다.
- Non-nullable Kotlin 타입(`LocalDate`, `LocalTime` 등)에 `@field:NotNull`을 붙여도 Jackson이 먼저 실패하므로 `MethodArgumentNotValidException` 대신 `HttpMessageNotReadableException`이 발생한다. → 별도 핸들러 필요.

### `@FutureOrPresent` 제약 조건의 테스트 영향

- 테스트에서 고정 날짜를 사용하면 시간이 지남에 따라 `@FutureOrPresent`에 걸려 테스트가 실패할 수 있다.
- 날짜를 충분히 미래로 설정하거나, `LocalDate.now().plusMonths(6)` 같은 동적 날짜 생성을 고려해야 한다.

## 검증

- 89개 테스트 통과 (3개 validation error-path 테스트 신규 추가)
- Tier 4 리뷰: P0=0, P1=0 (HIGH 2건 즉시 수정 반영)
