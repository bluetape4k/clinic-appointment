# 전이 npm 취약점은 강제 audit fix 대신 허용 범위의 lockfile 해석으로 고친다

## Context

`jsdom@28.1.0`이 해석한 `undici@7.24.4`가 공개 advisory 범위에 포함되어
고위험 audit 결과를 만들었다. 상위 의존성은 이미 `undici@^7.21.0`을 허용하므로
수정 릴리스로의 lockfile 갱신만으로 해결할 수 있었다.

## Decision

`npm update undici --package-lock-only`로 `undici`를 `7.29.0`으로 갱신하고,
`package.json`과 Angular 패키지는 바꾸지 않는다. `npm audit fix --force`는
관련 없는 Angular CLI major downgrade를 제안하므로 사용하지 않는다.

## Verification

깨끗한 `npm ci` 뒤 `npm ls undici --all`은 jsdom 경로에서 `undici@7.29.0`을
보였다. `npm audit`의 고위험 건과 Undici finding은 0건이 되었고,
`npm audit --omit=dev`는 0건을 유지했다. 빌드는 통과했으며 테스트 결과는
기준선의 기존 localStorage 환경 실패(6 파일/31 테스트)와 동일했다.

## Future guard

전이 의존성 advisory는 먼저 상위 패키지의 semver 허용 범위를 확인한다. 고정
릴리스가 그 범위에 있으면 lockfile만 갱신하고, `--force`가 major downgrade나
무관한 재해석을 제안하면 보안 수정 범위를 분리한다. 남은 audit 결과는 심각도와
production 경로 여부를 분리해 별도 작업으로 추적한다.
