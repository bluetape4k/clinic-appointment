# Issue #403 Jackson3 strict 경계 구현 계획

## 계획 헤더

- 목표: API raw JSON ingress와 event outbox codec의 strict/canonical 경계를
  구현하고 재현 가능한 테스트 증거를 남긴다.
- 기준: Issue #403, 설계 문서
  `docs/superpowers/specs/2026-08-25-issue-403-jackson3-strict-boundaries-design.md`
- 범위: `appointment-api`, `appointment-event`, 관련 문서와 테스트
- 제외: `appointment-messaging` mapper 교체, PR 생성·merge, unrelated root
  변경

## Task 1 — API raw boundary RED/GREEN

1. `SchedulingPolicyRequestContractTest`에 policy draft raw body의 top-level
   duplicate, nested duplicate, trailing token을 filter 단에서 거부하는
   테스트를 추가한다.
2. 먼저 targeted test를 실행해 strict guard가 없다는 RED 증거를 확인한다.
3. `CatalogPayloadSizeFilter`에 bounded body를 보존하면서 policy path에만
   Jackson3 duplicate/trailing 검사와 safe rejection을 추가한다.
4. 기존 oversized envelope 테스트와 새 hostile 테스트를 함께 실행한다.

검증 명령:

```bash
./gradlew :appointment-api:test --tests '*SchedulingPolicyRequestContractTest*' --rerun-tasks --no-configuration-cache
```

## Task 2 — event shared strict factory RED/GREEN

1. `NotificationOutboxCodecTest`, `WaitlistNotificationOutboxAdapterTest`,
   새 `AppointmentEventJsonTest`에 duplicate, trailing, constraint,
   canonical/golden 계약을 추가한다.
2. strict factory가 없을 때의 RED 결과를 기록한다.
3. `appointment-event` 내부 `AppointmentEventJson`에 messaging과 동일한
   Jackson3 strict feature 조합을 두고 두 codec이 공유하도록 변경한다.
4. codec별 오류 타입과 기존 domain validation을 유지한다.
5. read/write 모두 UTF-8 byte 상한을 적용하고 waitlist durable identifier와
   canonical golden을 검증한다.

검증 명령:

```bash
./gradlew :appointment-event:test --tests '*NotificationOutboxCodecTest*' --tests '*WaitlistNotificationOutboxAdapterTest*' --tests '*AppointmentEventJsonTest*' --rerun-tasks --no-configuration-cache
```

## Task 3 — dependency/documentation cleanup

1. API source import와 dependency insight를 다시 확인한다.
2. `appointment-api`의 사용하지 않는 직접 `bluetape4k-jackson3`
   dependency를 제거한다.
3. scheduling-policy 문서에 duplicate/trailing/size rejection 계약을
   한국어로 추가하고 문서 동기화 테스트를 통과시킨다.
4. 변경된 한국어 산출물에 `audit-korean-terms.mjs`를 실행한다.

검증 명령:

```bash
./gradlew :appointment-api:compileKotlin :appointment-api:test --rerun-tasks --no-configuration-cache
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs <changed-korean-files>
```

## Task 4 — module verification and review

1. API, event, messaging targeted test를 순차 실행한다.
2. 세 모듈 full test를 순차 실행하고 테스트 수·실패·오류·skip을 기록한다.
3. 7-Tier 관점(기능/계약, 보안, 동시성·성능, 데이터, 관측성·운영,
   Kotlin/bluetape 패턴, 테스트·문서)으로 독립 review를 수행한다.
4. P0/P1 finding이 0인지 확인하고, 남은 P2가 있으면 Issue #403에
   구현 follow-up으로 기록한다.

검증 명령:

```bash
./gradlew :appointment-api:test :appointment-event:test :appointment-messaging:test --rerun-tasks --no-configuration-cache
git diff --check
git status --short --branch
```

## Task 5 — Issue evidence read-back

1. 구현 worktree diff와 테스트 로그를 요약한다.
2. `gh issue view 403`로 현재 Issue metadata/body를 재확인한다.
3. owner handle과 mutation-check를 사용해 Issue #403에 한국어 구현
   증거 comment를 추가한다. PR은 만들지 않는다.
4. comment URL과 live read-back을 최종 DoD에 남긴다.

## Writer gate

- SPW-01: 설계 문서와 Issue #403의 확인 가능한 근거를 연결했다.
- SPW-02: 각 task를 파일·테스트·명령 단위로 쪼갰다.
- SPW-03: RED/GREEN 순서와 실패 시 다음 조치를 명시했다.
- SPW-04: 구현 범위와 제외 범위를 분리해 scope creep를 막았다.
- SPW-05: 모든 문서·KDoc·Issue prose는 repository-local 한국어 정책을
  따른다.
