# Dependencies 1.1.4 동기화

## 배경

`bluetape4k-dependencies` 1.2.0 release 준비 과정에서 이 앱의 catalog와
Dependabot ignore metadata가 최신 게시 dependency 기준보다 뒤처진 것을
확인했습니다.

## 결정

consumer catalog를 `bluetape4k-dependencies:1.1.4`에 맞추고 중앙에서 관리하는
Dependabot ignore를 동기화합니다. 해당 BOM이 게시되기 전에는 앱이 `1.2.0`을
가리키지 않도록 합니다.

## 결과

이 앱은 더 이상 중앙 dependencies release-train CI gate에 shared-version 또는
Dependabot-ignore drift를 만들지 않습니다.

## 검증

`bluetape4k-dependencies`에서 `sync-shared-versions.py`와
`sync-dependabot-ignores.py`를 `--workspace /Users/debop/work/bluetape4k
--write --check --summary` 옵션으로 실행해 검증했습니다.
