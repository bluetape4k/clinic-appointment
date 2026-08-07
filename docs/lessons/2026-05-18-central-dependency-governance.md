# 중앙 의존성 거버넌스 동기화

## 배경

하위 저장소의 Dependabot PR이 공용 의존성 버전을 저장소별로 따로 업데이트하면서
bluetape4k 조직 전체에 버전 drift가 발생했다.

## 결정

공용 의존성 버전은 먼저 `bluetape4k-dependencies`에서 변경한 뒤
`sync-shared-versions.py`로 이 저장소에 반영한다. 이 저장소의 Dependabot에서는
중앙에서 관리하는 의존성 이름도 ignore하여 이후 PR이 중앙 기준 데이터 원본을
통하도록 한다.

## 결과

로컬 version catalog와 `.github/dependabot.yml`이 이제 중앙 dependency-governance
정책을 따른다.

## 검증

- 이 저장소에서 `sync-shared-versions.py --write --check --summary` 실행
- 이 저장소에서 `sync-dependabot-ignores.py --write --check --summary` 실행
- `git diff --check`

## 향후 방지 규칙

중앙에서 관리하는 의존성에 대한 repo-local Dependabot PR은 merge하지 않는다.
`bluetape4k-dependencies`를 업데이트한 뒤 이 저장소를 동기화한다.
