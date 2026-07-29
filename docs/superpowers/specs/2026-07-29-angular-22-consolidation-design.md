# Angular 22 호환성 업그레이드 설계

## 문제

열린 Dependabot PR #144, #145, #148, #188은 Angular 패키지 일부만
업데이트한다. 특히 `@angular/build` 22.x는 Angular compiler, compiler CLI,
core, platform-browser의 22.x peer dependency와 TypeScript 6.0을 요구한다.
현재 21.x 패키지군과 섞이면 `npm ci`가 peer dependency 충돌로 실패한다.

## 결정

`frontend/appointment-frontend`에서 Angular 런타임, 도구, Material/CDK를
동일한 Angular 22 호환선으로 함께 갱신한다.

- Angular 런타임 및 도구: `22.0.8`
- Angular CDK 및 Material: `22.0.6` (22.x의 현재 호환 릴리스)
- TypeScript: `~6.0.3`
- 기존 `rxjs`, `tslib`, 앱 코드와 빌더 설정은 설치·컴파일 오류가 요구하는
  최소 범위에서만 변경한다.

## 대안과 배제 사유

1. `@angular/build`를 21.x에 고정하고 각 Dependabot PR을 개별 처리한다.
   Angular 22 업데이트를 계속 막고 상호 충돌하는 PR을 남기므로 배제한다.
2. `--legacy-peer-deps` 또는 `--force`로 설치를 우회한다. 선언된 peer 계약을
   무시해 CI와 런타임 재현성을 잃으므로 배제한다.
3. Angular 22 패키지군을 원자적으로 갱신한다. peer 계약을 만족하고 하나의
   검증 가능한 변경으로 수렴하므로 채택한다.

## 경계

- 대상은 `frontend/appointment-frontend/package.json`,
  `frontend/appointment-frontend/package-lock.json`, 그리고 Angular 22가
  요구하는 최소 소스/설정 보정으로 제한한다.
- 예약 도메인, Kotlin 모듈, API 계약, 데이터베이스 스키마는 변경하지 않는다.
- 열린 Dependabot PR은 대체 PR이 녹색이 된 뒤에만 정리한다.

## 실패 모드와 대응

1. `npm ci` peer dependency 충돌: Angular/TypeScript 버전을 같은 호환선으로
   맞추고 `npm ls`로 트리를 확인한다.
2. 빌드 또는 타입 오류: Angular 22 마이그레이션 오류를 최소 소스/설정 변경으로
   해결하고 다시 빌드한다.
3. 기존 프런트엔드 테스트 실패: 현재 `develop`과 업그레이드 후보를 동일 명령으로
   비교하여 새 실패와 기존 실패를 분리한다.
4. CI 환경 불일치: Node 26.5.0과 npm 11.17.0에서 로컬 검증하고 PR의 정확한
   head CI를 다시 확인한다.

## 수용 기준

- `npm ci`가 peer dependency 충돌 없이 성공한다.
- `npm ls`가 Angular 22/TypeScript 6.0 호환 트리를 보인다.
- `npm run build`가 성공한다.
- `npm test -- --watch=false`의 결과가 기준선과 비교되어 새 실패가 없음을 보인다.
- 단일 PR이 기존 Angular Dependabot PR의 충돌을 대체하고, 정확한 head CI가
  성공한다.

## 롤백

새 브랜치와 단일 PR로만 변경한다. 검증 실패 시 PR을 병합하지 않고, `develop`은
`6f8d05fbe26f8ef43acf3771ac6b785fcc75d778` 상태로 유지한다.
