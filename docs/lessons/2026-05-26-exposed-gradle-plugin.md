## 배경

Exposed 테이블을 정의하는 clinic appointment 모듈에 JetBrains Exposed Gradle
plugin을 도입했습니다.

## 결정

애플리케이션은 관리되는 `bt4k` catalog와 독립적으로 유지합니다. plugin
버전은 로컬에서 선언하고 `bluetape4k-dependencies`를 dependency BOM으로
유지합니다.

## 결과

Core, API, event, notification 모듈에서 이제 명시적인 migration 설정과 함께
`generateMigrations`를 제공합니다.

## 검증

`git diff --check`, `./gradlew -q help`, `:appointment-core:tasks --all`을
실행했습니다.

## 향후 보호 규칙

애플리케이션 catalog에 명시적인 JetBrains Exposed 버전이 없으면
`bluetape4k-dependencies`가 사용하는 Exposed BOM 라인과 일치하는 로컬
plugin 버전을 추가합니다.
