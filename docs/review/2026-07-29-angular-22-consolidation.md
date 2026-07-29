# Angular 22 호환성 업그레이드 검토

## 결론

P0=0, P1=0으로 전달 가능하다. 초기 검토에서 확인된 두 P1은 같은 변경에서
해결하고 재검증했다.

## 검토 관점

| 관점 | 결과 | 근거 |
| --- | --- | --- |
| 성능 | 통과 | 프로덕션 빌드가 Angular 22에서 성공했다. |
| 안정성 | 통과 | Gradle Node를 Angular 22의 Node 22 하한인 `22.22.3`으로 올렸고, Gradle build/test가 성공했다. |
| 보안 | 통과 | peer 우회 옵션을 사용하지 않았고 `npm audit --omit=dev`는 취약점 0개를 보고했다. 전체 audit의 dev 도구 의존성 5건은 이번 변경으로 production 경로에 도입되지 않았다. |
| 운영 | 통과 | CI, frontend CI, nightly의 frontend job을 Node `22.22.3`/npm `11.12.0`으로 고정하고 실행 버전을 로그에 남긴다. |
| 개발자/API | 통과 | Angular runtime, build, compiler, CLI, CDK/Material, TypeScript가 단일 peer 호환 트리로 해석되며 API·도메인 소스는 변경하지 않았다. |
| 사용자/호출자 | 통과 | 생산 번들 생성이 성공했고, npm 테스트는 기준선과 같은 localStorage 환경 실패 31건 외 새 실패가 없다. Gradle test는 173건 전체 통과했다. |

## 해결한 P1

1. Gradle이 Node `22.14.0`을 내려받아 Angular 22 engine 하한 미만이던 문제를
   `22.22.3`으로 수정했다.
2. GitHub Actions가 부동 `22`와 번들 npm을 사용하던 문제를 Node `22.22.3`과
   npm `11.12.0`으로 고정했다.

## 검증 증거

- `npm ci`: 성공
- `npm ls @angular/core @angular/build @angular/compiler @angular/compiler-cli @angular/cdk @angular/material typescript --all`: Angular `22.0.8`, CDK/Material `22.0.6`, TypeScript `6.0.3` 단일 트리
- `npm run build`: 성공
- `npm test -- --watch=false`: 기준선과 같은 6 파일/31 테스트 실패 (`localStorage.getItem` 환경 문제)
- `./gradlew :frontend:appointment-frontend:build`: 성공
- `./gradlew :frontend:appointment-frontend:test`: 26 파일/173 테스트 성공
- GitHub Actions YAML parse 및 `git diff --check`: 성공

## 남은 게이트

정확한 PR head의 GitHub CI와 현재 리뷰 스레드 확인 후, 사용자 승인으로만 병합한다.
