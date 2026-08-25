# Issue #193 Angular CLI 전이 의존성 감사 inline review

## 검토 범위와 기준

검토 대상은 `frontend/appointment-frontend/package.json`과
`frontend/appointment-frontend/package-lock.json`이다. Issue #193의 원래
MCP 전이 의존성 감사 조건과 현재 registry/audit 결과를 대조하고, 지원되는
Angular patch family 갱신이 peer 계약·빌드·브라우저 계약을 깨뜨리지 않는지
확인했다.

이번 review는 전용 worktree의 fresh `npm ci`, `npm audit`, `npm ls`, Angular
build, unit test, Playwright E2E 결과와 diff read-back을 기준으로 한다.
`npm audit fix --force`나 검증되지 않은 dependency override는 사용하지 않았다.

## 심각도별 결과

| 심각도 | 건수 | 근거 및 처분 |
|---|---:|---|
| P0 | 0 | 앱 부팅 불가, 데이터 손실, 인증 우회 또는 production dependency 감사 실패를 확인하지 못했다. |
| P1 | 0 | Angular peer family, MCP SDK 전이 경계, `npm ci`, build, unit, E2E에 blocker가 없다. |
| P2 | 0 | 현재 audit 결과를 모두 해소했고, 별도 기능 변경 없이 dependency 경계만 갱신했다. |
| P3 | 0 | 이번 범위에서 추가 정리 항목을 등록하지 않았다. |

## 변경 검토

- Angular runtime·CDK·Material을 `22.1.3` patch family로 맞추고,
  `@angular/build`·`@angular/cli`를 `22.1.5`, `@angular/compiler-cli`를
  `22.1.3`으로 갱신했다.
- `@angular/cli@22.1.5`가 지원하는 `@modelcontextprotocol/sdk@1.30.0`을
  lockfile에 반영했다. SDK의 `@hono/node-server`는 허용 범위 안의 `2.1.1`을
  선택했다.
- 기존 `ip-address@10.1.1` 강제 override를 제거했다. `express-rate-limit`
  계약(`^10.2.0`)에 맞춰 lockfile이 `ip-address@10.5.0`을 선택하도록 했다.
- `postcss`가 허용하는 범위에서 `nanoid@3.3.18`을 `npm update`로 lockfile에
  반영했다. 애플리케이션 코드와 새 dependency는 추가하지 않았다.
- `npm ls --all`은 invalid/unmet dependency 없이 종료 코드 0을 반환했다.

## 검증 증거

- baseline `npm audit --json`: **5 high**, `npm audit --omit=dev`: **0**.
- candidate `npm ci`: **성공**, 399 packages 설치.
- candidate `npm audit --json`: **exit 0**, info/low/moderate/high/critical **모두 0**.
- candidate `npm audit --omit=dev --json`: **exit 0**, 모든 severity **0**.
- `npm ls --all`: **exit 0**, invalid/unmet 없음.
- `npx tsc --noEmit -p tsconfig.app.json`: **통과**.
- `npm run build`: **성공**, Angular bundle 생성.
- `npm test -- --watch=false`: **45개 파일 / 327개 테스트 통과**.
- `npm run test:e2e`: **10개 Chromium 시나리오 통과**.
- `git diff --check`: 문서·package diff 공백 오류 없음.
- 변경 파일에 Kotlin 소스가 없으므로 `bluetape-kotlin-patterns`의 Kotlin 전용
  규칙은 적용 대상이 아니다. `git diff --name-only | rg '\\.kt$'` 결과도 없다.

## 환경 차단과 경계

`./gradlew :frontend:appointment-frontend:build`는 Angular 작업 실행 전에
`node-22.22.3-darwin-arm64.tar.gz`의 Gradle dependency verification metadata가
없어 중단됐다. `build.gradle.kts`의 Node 버전과 verification metadata는 이번
변경 대상이 아니므로 임의로 갱신하지 않았다. npm 기반 `ci`·audit·build·unit·E2E는
독립적으로 통과했다.

## 결론

현재 dependency remediation과 frontend 검증 범위에서 P0/P1 blocker는 없다.
Issue #193의 Angular CLI/MCP audit chain은 지원되는 patch family로 갱신되었고,
현재 npm audit 결과는 production·development 모두 0이다.

**판정: MERGE-READY (PR/merge 전용 최신 head 승인 대기)**

## 문서 게이트

- SPW-01: 독자·범위·근거 파일·정확한 dependency 식별자를 고정했다. **PASS**
- SPW-02: review 범위, severity, concrete evidence, 처분, 차단, verdict를 포함했다. **PASS**
- SPW-03: Korean technical register와 command/API token 보존을 확인했다. **PASS**
- SPW-04: 현재 worktree의 diff와 fresh 명령 결과를 대조했다. **PASS**
- SPW-05: Markdown read-back 및 `git diff --check`를 완료했다. **PASS**
- KO-01~07: 사실·식별자·용어·reader-facing 표면을 검토하고 contextual terminology audit 대상 충돌이 없음을 확인했다. **PASS**
