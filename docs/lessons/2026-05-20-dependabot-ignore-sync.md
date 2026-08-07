# Dependabot ignore 동기화

## 배경

`bluetape4k-dependencies`가 하위 저장소 Dependabot ignore block에 중앙 관리
의존성을 더 추가했다.

## 결정

생성된 ignore list를 이 저장소에 전파해 Dependabot이 중앙 catalog에서 관리하는
의존성에 대해 repo-local PR을 열지 않도록 한다.

## 결과

로컬 `.github/dependabot.yml`이 이제 중앙에서 관리하는 새 Bouncy Castle,
ClassGraph, Tomcat 좌표를 ignore한다.

## 검증

- `git diff --check`

## 향후 참고

중앙 의존성 변경이 끝나면 shared version sync와 함께
`sync-dependabot-ignores.py`를 실행한 뒤 중앙 downstream CI gate를 다시 실행한다.
