# 이슈 #248 lesson: 부재와 DB 장애를 같은 404로 만들지 않는다

## 문제

`runCatching { transaction { repository.findByIdAndTenant(...) } }.getOrNull()`는 정상적인 `null`과 SQL/connection 예외를 같은 값으로 접었다. API 호출자는 존재하지 않는 리소스와 인프라 장애를 구분할 수 없고, 운영 알람과 재시도도 놓친다.

## 적용한 규칙

1. repository가 반환한 정상 `null`만 `ResponseEntity.notFound()`로 변환한다.
2. `transaction { ... }`에서 발생한 예외는 전역 예외 처리기까지 전달한다.
3. 영속 ID가 nullable인 domain record를 응답으로 바꿀 때는 `checkNotNull(value) { "...id must not be null" }`로 invariant와 진단 문맥을 함께 남긴다.
4. compliance 검사는 과거 이슈의 고정 파일 목록에 의존하지 않고 현재 모듈의 source tree를 재귀 순회한다.
5. 테스트 DB schema는 `createMissingTablesAndColumns`와 `deleteAll`을 기본으로 사용한다. 장애 재현을 위한 의도적인 `drop`은 사유를 문서화한다.

## 회귀 방지

`ResourceLookupFailureControllerTest`는 네 조회 컨트롤러 각각에 대해 DB 예외가 `SQLException`으로 남는지, 실제 미존재가 404인지 확인한다. 같은 테스트에서 `AppointmentRecord`와 `RescheduleCandidateRecord`의 ID 누락 오류 메시지도 고정한다.

## 남은 작업

모든 API `data class`의 직렬화 계약은 configuration/worker 내부 상태까지 포함하는 별도 설계가 필요하다. 대상 목록과 호환성 영향도를 확정한 뒤 별도 P2 이슈로 처리한다.
