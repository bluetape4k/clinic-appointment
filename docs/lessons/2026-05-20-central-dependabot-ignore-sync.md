# 중앙 Dependabot ignore 동기화

## 배경

이제 `bluetape4k-dependencies`가 BouncyCastle, ClassGraph, Tomcat 계열의
Dependabot alert routing을 소유한다. 하위 저장소는 중앙에서 관리하는 이 패키지들에
대해 Dependabot version PR을 직접 받지 않아야 한다.

## 결정

로컬 ignore entry를 수동으로 관리하지 말고 `bluetape4k-dependencies`에서 생성한
중앙 ignore block을 동기화한다.

## 결과

이 저장소의 Dependabot 설정이 이제 새로 중앙 관리 대상이 된 의존성 이름을
ignore한다. 이후 버전 변경은 `bluetape4k-dependencies`에서 시작하고 해당 저장소의
sync script로 전파한다.

## 검증

- `scripts/sync-dependabot-ignores.py --workspace .. --write --check --summary`
- `git diff --check`
- `actionlint .github/workflows/ci.yml`
- `curl -I -sSfL https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz`
