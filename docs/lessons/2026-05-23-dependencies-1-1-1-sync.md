# Dependencies 1.1.1 동기화

## 배경

`bluetape4k-dependencies` 1.1.0은 artifact availability audit에서 게시되지
않은 mock web application 모듈의 생성 alias를 발견한 뒤 1.1.1로 대체되었습니다.
이 애플리케이션은 공유 catalog를 사용하므로 release train과 정렬된 상태를
유지해야 합니다.

## 결정

표준 shared-version 동기화 경로를 통해 `bluetape4k-dependencies = "1.1.1"`을
사용합니다. 중앙 catalog에서 이미 제거한 artifact에 애플리케이션 로컬
override를 추가하지 않습니다.

## 결과

PR #130에서 이 저장소를 1.1.1 catalog에 맞췄고 CI 통과 후 머지했습니다.

## 검증

- 머지 전에 GitHub PR #130 status check가 통과했습니다.
- downstream PR을 머지한 뒤 워크스페이스 수준의
  `scripts/sync-shared-versions.py --workspace .. --check --summary`가
  통과했습니다.

## 향후 지침

공유 catalog 패치로 게시 가능성이 해결되면 Maven Central `repo1`에서 새
버전을 resolve할 때까지 기다린 뒤 downstream CI를 다시 실행합니다. 여러
bluetape4k 저장소에 영향을 주는 애플리케이션 dependency drift는 catalog에서
해결합니다.
