# Shared version drift 정리

## Context

`clinic-appointment`는 `bluetape4k-dependencies`, JetBrains Exposed, Dokka 버전이 중앙 `bluetape4k-dependencies` catalog와 다르게 남아 있었다.

## Decision

Local catalog만 갱신해 `bluetape4k-dependencies=1.0.0`, `exposed=1.3.0`, `dokka=2.2.0`으로 맞춘다. `bluetape4k-dependencies:1.0.0`이 관리하는 published artifact 이름에 맞춰 `io.github.bluetape4k.exposed:bluetape4k-exposed-*`와 `io.github.bluetape4k.leader:bluetape4k-leader-*` 좌표도 함께 정렬한다. 다른 dependency update PR과 섞지 않는다.

## Outcome

중앙 source-of-truth 검증에서 clinic repo가 shared version drift를 만들지 않도록 정리했다.

## Verification

- `../bluetape4k-dependencies/.worktrees/feat/dependency-governance/scripts/sync-shared-versions.py --workspace <symlink-workspace> --repo clinic-appointment --check --summary`
- `./gradlew build --no-daemon`
- `git diff --check`

## Future Guard

Dependabot PR과 shared catalog alignment PR은 분리한다. 중앙 catalog가 올린 공통 버전은 source-of-truth script로 일괄 확인한다.
