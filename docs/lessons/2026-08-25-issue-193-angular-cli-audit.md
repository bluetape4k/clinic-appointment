# Issue #193 Angular CLI 전이 의존성 감사 remediation lesson

## 문제

Issue #193을 처음 등록할 때는 `@angular/cli@22.0.8`에서
`@modelcontextprotocol/sdk@1.29.0`으로 이어지는 dev-only moderate 감사 결과를
추적했다. 현재 registry와 lockfile을 다시 대조하자 advisory가 갱신되어 baseline
`npm audit`에는 `brace-expansion`, `express-rate-limit`, `ip-address`, `nanoid`,
`socks` 관련 **5 high**가 표시됐다. `npm audit --omit=dev`는 계속 0이었다.

## 결정

- `npm audit fix --force`가 제안하는 Angular CLI downgrade는 적용하지 않았다.
- Angular runtime·CDK·Material을 `22.1.3`, build·CLI를 `22.1.5`, compiler-cli를
  `22.1.3`으로 같은 지원 family에 맞췄다. 이 갱신으로 CLI가 지원하는
  `@modelcontextprotocol/sdk@1.30.0`을 사용한다.
- 기존 `ip-address@10.1.1` override는 취약 버전을 고정하므로 제거했다. upstream
  범위가 허용하는 `ip-address@10.5.0`을 lockfile에 선택하게 두었다.
- `postcss`의 허용 범위 안에서 `nanoid@3.3.18`만 lockfile targeted update로
  반영했다. 검증되지 않은 override와 새 dependency는 추가하지 않았다.

## 결과와 검증

- `npm ci`: 성공.
- `npm audit --json`: exit 0, 모든 severity 0.
- `npm audit --omit=dev --json`: exit 0, 모든 severity 0.
- `npm ls --all`: exit 0, invalid/unmet dependency 없음.
- `npx tsc --noEmit -p tsconfig.app.json`: 통과.
- `npm run build`: 성공.
- `npm test -- --watch=false`: 45개 파일, 327개 테스트 통과.
- `npm run test:e2e`: Chromium 10개 통과.

## 놓친 점과 재발 방지

초기 issue 본문에 기록된 advisory 개수와 현재 npm advisory 데이터는 시간이
지나면 달라진다. 따라서 dependency 이슈를 재개할 때는 기존 issue 숫자를
그대로 재사용하지 말고 다음 순서로 현재 상태를 고정한다.

1. `npm ci`로 lockfile을 재현한다.
2. `npm audit --json`과 `npm audit --omit=dev --json`을 각각 실행해 runtime/dev
   범위를 분리한다.
3. `npm ls`로 advisory package와 실제 parent chain을 확인한다.
4. 지원되는 upstream patch family를 먼저 확인하고, downgrade·force·override는
   선택지에서 제외한다.
5. lockfile을 갱신한 뒤 `npm ci`, audit, build, unit, E2E를 다시 실행한다.

또한 package-level override는 보안 fix처럼 보여도 취약 버전을 장기간 고정할
수 있다. override를 추가하기 전에 upstream semver 범위와 현재 advisory를
확인하고, 제거 가능한 override는 제거 후 재현 가능한 `npm ci`로 검증한다.

## 남은 환경 경계

Gradle frontend task는 저장소의 Node archive dependency verification metadata가
없는 상태에서 `node-22.22.3-darwin-arm64.tar.gz`를 요청해 실패했다. 이 metadata
갱신은 별도 maintenance 이슈로 분리해야 하며, 이번 dependency audit fix의
성공 조건에 섞지 않는다.

## 문서 게이트

- SPW-01: lesson 독자·문제·근거 경로·정확한 버전을 고정했다. **PASS**
- SPW-02: 문제·결정·결과·검증·놓친 점·재발 방지·환경 경계를 포함했다. **PASS**
- SPW-03: Korean technical register와 명령·식별자·버전을 보존했다. **PASS**
- SPW-04: 현재 worktree의 package diff와 fresh 검증 결과를 대조했다. **PASS**
- SPW-05: Markdown read-back 및 `git diff --check`를 완료했다. **PASS**
- KO-01~07: 사실·용어·명령·식별자를 검토하고 terminology collision이 없음을 확인했다. **PASS**
