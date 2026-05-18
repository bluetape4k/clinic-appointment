# Issue #94 — OpenAPI Annotations

## 날짜: 2026-05-18

## 요약

8개 controller 전체에 `@Tag`, `@Operation`, `@ApiResponses`, `@Parameter` 추가.

## 주요 교훈

### 1. Import 충돌 해결

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

### 3. @Parameter 선별적 적용

모든 파라미터에 `@Parameter`를 붙이면 noise. 날짜(ISO format), searchDays, 
optional reason 등 의미가 불분명한 것에만 적용.
