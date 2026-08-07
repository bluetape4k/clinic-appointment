# Angular peer family 업그레이드는 원자적으로 수행해야 한다

## 배경

네 개의 Dependabot PR이 Angular 패키지를 개별적으로 올리려고 했습니다. 각
PR에서 Angular 22 build tooling 옆에 Angular 21 compiler/compiler-cli 패키지가
남았고, 그 결과 frontend build를 실행하기 전에 `npm ci`가 실패했습니다.

## 결정

Angular runtime 패키지, build/CLI/compiler tooling, CDK/Material, TypeScript를
peer 호환 manifest 하나로 함께 업그레이드합니다. Angular peer 충돌을 숨기기
위해 `--force`나 `--legacy-peer-deps`를 사용하지 않습니다.

## 검증

Node 26.5.0과 npm 11.17.0에서 clean `npm ci`가 Angular 22.0.8,
CDK/Material 22.0.6, TypeScript 6.0.3을 resolve했습니다. `npm run build`도
통과했습니다. frontend 테스트 결과는 현재 `develop` 기준과 같았습니다.
기존 `localStorage.getItem` 환경 오류로 6개 파일 / 31개 테스트가 실패했습니다.
Gradle이 관리하는 Node runtime은 22.14.0에서 22.22.3으로 올렸으며, 이는
Angular 22가 선언한 Node 22 최저 버전을 충족합니다. 이후 Gradle frontend
build와 test는 26개 파일, 173개 테스트로 통과했습니다.

## 향후 보호 규칙

Dependabot이 Angular build, compiler, CLI 또는 runtime 패키지를 major line
너머로 올리면, 전체 peer family와 저장소가 관리하는 모든 Node runtime을
격리된 branch에서 함께 정리합니다. 머지 전에 후보 테스트 결과를 새로 만든
기준 결과와 비교합니다.
