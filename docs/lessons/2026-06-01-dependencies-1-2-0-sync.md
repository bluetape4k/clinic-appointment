# Dependencies 1.2.0 동기화

## 배경

최종 upstream BOM matrix가 Maven Central에서 확인 가능해진 뒤
`bluetape4k-dependencies:1.2.0`이 게시되었습니다.

## 결정

clinic appointment 공유 catalog를 `1.1.4`에서 `1.2.0`으로 올립니다.

## 결과

예제 앱은 이제 게시된 1.2.0 dependency-governance 기준을 사용합니다.

## 검증

- `sync-shared-versions.py --workspace .. --write --check --summary`가 catalog
  라인을 갱신했습니다.
- Maven Central이 `io.github.bluetape4k:bluetape4k-dependencies:1.2.0`에 대해
  HTTP 200을 반환했습니다.
