# Dependencies 1.1.3 동기화

## 배경

`clinic-appointment`은 여전히 `bluetape4k-dependencies = "1.1.1"`을 사용하고
있었습니다. 게시된 release tag `bluetape4k-dependencies` `1.1.3`은 downstream
동기화를 위한 catalog 기준이며, 로컬 post-release branch에는 이미 다음 개발
버전이 들어 있을 수 있습니다.

## 결정

`bluetape4k-dependencies`를 유일한 bluetape4k BOM 소스로 유지하고 catalog
버전을 `1.1.3`으로 갱신합니다. `bluetape4k-bom` 또는
`bluetape4k-exposed-bom`을 직접 import하지 않습니다.

## 결과

로컬 catalog는 이제 `io.github.bluetape4k:bluetape4k-dependencies:1.1.3`을 통해
bluetape4k와 bluetape4k-exposed 버전을 resolve합니다.

- `git show 1.1.3:gradle/libs.versions.toml`에서 tag catalog가
  `bluetape4k-dependencies = "1.1.3"`을 선언하는 것을 확인했습니다.
- `./gradlew -q :appointment-core:dependencyInsight --configuration compileClasspath --dependency io.github.bluetape4k:bluetape4k-dependencies`가
  `io.github.bluetape4k:bluetape4k-dependencies:1.1.3`을 resolve했습니다.
- Gradle 파일을 대상으로 실행한 `rg`에서 직접적인
  `libs.jetbrains.exposed.bom`, `libs.bluetape4k.bom`, `bluetape4k-bom`,
  `bluetape4k-exposed-bom` 사용을 찾지 못했습니다.
- `./gradlew compileTestKotlin --no-daemon`이 통과했습니다.
