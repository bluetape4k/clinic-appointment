# #257 보안 응답 상태 누수 격리

## 증상

의존성 업그레이드 통합 검증에서 scheduling policy 보안 테스트가 간헐적으로
무인증 또는 잘못된 JWT 요청의 `401 Unauthorized` 대신 `403 Forbidden`을 관찰했다.
테스트 클래스만 단독 실행하면 통과했으므로 테스트 순서나 실행기 상태가 요청 간
인증 정보를 오염시키는지 확인해야 했다.

원래 통합 실패 자체는 같은 조건으로 매번 재현되지 않았고, 재시도·sleep·assertion
완화로 숨기지 않았다. 대신 동일한 보안 경계에서 재현 가능한 context 누수를 먼저
격리했다.

## RED 재현

초기 격리 테스트 `request completion clears authentication installed by downstream filters`를
추가해 downstream `FilterChain`이 `SecurityContextHolder`에
`UsernamePasswordAuthenticationToken`을 설치하도록 했다. 기존 구현은 요청 시작 시
context만 지우고 chain 반환 후에는 지우지 않았기 때문에 다음 검증이 실패했다.

```text
Expected <null>, but was <UsernamePasswordAuthenticationToken ... downstream-admin ...>
2 tests completed, 1 failed
```

이는 servlet thread가 재사용될 때 downstream 인증이 다음 요청으로 전파될 수 있음을
보여준다. `SecurityConfig`의 access-denied 경로는 principal 존재 여부에 따라
`401`/`403`을 구분하므로, 남은 인증 정보는 익명 또는 invalid-token 요청을 인증된
권한 부족 요청으로 잘못 분류할 수 있다.

## 수정

`JwtAuthenticationFilter`의 `filterChain.doFilter`를 `try/finally`로 감싸고 요청 종료
시 `SecurityContextHolder.clearContext()`를 항상 수행한다. 요청 시작 초기화와
bearer 검증 규칙은 유지했으며, 실행 순서 변경·sleep·재시도·상태 코드 assertion 완화는
추가하지 않았다.

## GREEN 및 회귀 검증

- 수정 직후 초기 `JwtAuthenticationFilterTest`: `SUCCESS: Executed 2 tests`.
- 회귀 검증은 workflow의 pinned write scope에 맞춰
  `SchedulingPolicySecurityIntegrationTest`로 옮겼고, 최종 통합 클래스는
  `SUCCESS: Executed 5 tests`로 통과했다.
- H2 `appointment-api` 보안 패키지: `SUCCESS: Executed 62 tests`.
- PostgreSQL `appointment-api` 보안 패키지: `SUCCESS: Executed 62 tests`.
- MySQL `appointment-api` 보안 패키지: `SUCCESS: Executed 62 tests`.
- `SchedulingPolicySecurityIntegrationTest`에서 익명/invalid JWT `401`, 인증된 권한
  부족 `403`, correlation id 및 privacy 계약을 세 dialect에서 유지했다.
- 변경된 Kotlin 파일에 대해 `git diff --check`와 모듈 테스트/컴파일이 통과했다.

## 남은 위험

전체 PostgreSQL API aggregate에는 이 이슈와 별개인 migration 중복 객체 오류
(`scheduling_clinics`의 `pg_type_typname_nsp_index`)가 남아 있다. 테스트 종료 시
Redis reconnect 경고도 관찰되지만 보안 패키지 Gradle 결과는 모두 성공했다. 운영
환경의 servlet container/thread 재사용 검증은 배포 승인 범위 밖이므로 별도 운영
검증으로 남긴다.
