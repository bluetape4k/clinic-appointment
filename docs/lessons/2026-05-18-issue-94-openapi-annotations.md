# Issue #94 — OpenAPI 어노테이션

## 날짜: 2026-05-18

## 요약

8개 controller 전체에 `@Tag`, `@Operation`, `@ApiResponses`, `@Parameter` 추가.

## 주요 교훈

### 1. import 충돌 해결

프로젝트에 이미 `io.bluetape4k.clinic.appointment.api.dto.ApiResponse`가 존재하므로
Swagger의 `io.swagger.v3.oas.annotations.responses.ApiResponse`를 `OApiResponse`로
alias import하여 충돌 방지.

### 2. 어노테이션 배치 순서

```kotlin
@Operation(summary = "...")   // OpenAPI docs
@ApiResponses(...)            // HTTP response codes
@GetMapping("/path")          // Spring mapping
fun method(...)
```

기존 KDoc은 유지. `@Tag`는 클래스 레벨에서 `@RestController` 위에 배치.

### 3. `@Parameter` 선별 적용

모든 파라미터에 `@Parameter`를 붙이면 불필요한 잡음이 생깁니다. 날짜(ISO format),
`searchDays`, 선택적 `reason`처럼 의미가 불분명한 항목에만 적용합니다.
