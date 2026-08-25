# Issue #295 설계·구현 계획 inline review

## 검토 범위와 근거

설계 문서와 구현 계획을 현재 `origin/develop` 기준 source, backend controller mapping, frontend baseline test/build 결과와 대조했다. 검토 대상은 Issue #295 잔여범위이며 Kotlin·backend·Capacitor 변경은 범위 밖이다. 여섯 관점을 main session에서 독립적으로 적용한 뒤 통합했다.

## 관점별 결과

| 관점 | 결과 | 근거 |
|---|---|---|
| 성능 | P2 없음 | 공통 transport는 기존 `HttpClient` 한 번을 감싸며 추가 round trip을 만들지 않는다. portal history cache와 ETag 흐름을 유지한다. |
| 안정성 | P2 수정 | SSE는 기존 `AbortController` 해제 테스트가 있으므로 계획에 cancellation 증거를 명시했다. tenant 전환 응답은 기존 epoch/cache 규칙을 유지한다. |
| 보안 | P1 없음 | patient cookie와 workforce Bearer를 URL 추측이 아닌 `HttpContext` scope로 분리하고, token storage를 금지한다. tenant allow-list 불일치는 fail-closed다. |
| 운영 | P2 없음 | backend endpoint를 추가하지 않고 Gradle dependency verification 실패를 baseline 환경 gap으로 보존한다. rollback·rerun 지점을 계획에 적었다. |
| 개발/API | P2 수정 | transport는 `HttpResponse`만 제공하고 response envelope/domain 변환은 기존 client/service에 남긴다. public service signature와 기존 Portal API는 유지한다. |
| 사용자/호출자 | P2 수정 | workforce login endpoint가 없다는 사실을 명시하고 host 주입 `AuthService.bootstrap`을 계약으로 고정했다. 다중 tenant token은 자동 추측하지 않는다. |

## 통합 검토

### 수정한 항목

1. 계획 문서 상단에 목표·아키텍처·기술 스택을 추가해 실행자가 독립적으로 시작할 수 있게 했다.
2. SSE `AbortController` cancellation을 기존 spec으로 검증한다는 항목을 단계 5에 반영했다.
3. README 변경은 public method와 DTO를 유지하는 내부 transport 교체이므로 이번 변경에서는 N/A로 기록한다. 대신 설계·계획·review·lesson을 한국어로 남긴다.

### 수용 기준 추적 확인

| 기준 | 계획 단계 | 최신 상태 |
|---|---|---|
| tenant path | 1, 4, 5, 6 | PASS |
| cookie/Bearer 분리 | 1, 3, 4, 5 | PASS |
| workforce bootstrap | 2 | PASS |
| 공통 상태 전파 | 2, 3, 6 | PASS |
| DTO/error drift | 4 | PASS |
| backend/mobile 비변경 | 전체 | PASS (구현 후 diff 재확인 필요) |

## 판정

- P0: 0
- P1: 0
- P2: 0 (검토 중 발견한 계획 명시 누락을 수정 완료)
- P3: 0
- 설계 review: PASS
- 계획 review: PASS
- 구현 진입 조건: 설계·계획 문서가 현재 worktree에 존재하고, 사용자가 실행을 승인했으며, baseline evidence가 기록되어 있다.

## 문서 게이트

- SPW-01: PASS — 검토 scope, source, 독자, 언어, unknown을 고정했다.
- SPW-02: PASS — 관점별 evidence, disposition, 추적표, verdict를 포함했다.
- SPW-03: PASS — 한국어 기술 문체와 동일 용어를 유지했다.
- SPW-04: PASS — backend mapping·frontend source·baseline과 문서 주장을 대조했다.
- SPW-05: PASS — 표와 상태를 read-back했고 P0/P1/P2/P3를 정규화했다.

## Kotlin 패턴 적용성

이번 단계까지 Kotlin 파일 변경은 0개다. `$bluetape-kotlin-patterns` KT-01~KT-05는 적용 대상이 아니며, 구현 후 `git diff --name-only`로 Kotlin 파일 0개를 다시 확인한다. TypeScript transport와 Angular interceptor에는 해당 skill의 재사용·작은 경계·테스트 우선 원칙만 적용한다.
