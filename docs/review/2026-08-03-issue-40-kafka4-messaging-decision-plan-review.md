# Issue #40 Kafka4 메시징 결정 구현 계획 3-R 검토

## 검토 범위

- 대상: `docs/superpowers/plans/2026-08-03-issue-40-kafka4-messaging-decision-plan.md`
- 승인된 spec: `docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`
- 기준 branch: `origin/develop` (`5ed18733adac4e57090267b485766644709f3324`)
- 실행 범위: ADR과 backlog 문서화, 검증, PR/CI와 승인 후 closeout
- 비범위: Kotlin, Gradle, module, Flyway, runtime 설정과 #41/#42 구현

여섯 독립 관점이 exact plan을 읽기 전용으로 검토했다. main session은 finding의 중복과
심각도를 정규화하고 모든 P0/P1과 실행 명확성을 해치는 P2/P3를 계획에 반영한 뒤 영향받은
관점을 다시 실행했다.

## 실행 모델 확인

2-R 당시 기록된 user-scope model drift는 현재 해소됐다. 3-R 종료 시점에
`~/.codex/agents/{verifier,code-reviewer,writer}.toml`은 모두 `gpt-5.6-luna`와
`max` reasoning을 가리키고, `~/.codex/.omx-config.json`도 해당 role을
`gpt-5.6-luna`로 매핑했다. 별도 Codex 프로세스도 같은 worktree에서 leader/verifier
`gpt-5.6-luna`, autonomy directive와 변경된 hook 두 파일을 로드했고, 이후
`omx doctor`는 18 PASS, warning/failure 0건을 반환했다. 이 검사는 repository 밖 설정을
변경하지 않았다.

## 초기 finding과 수정

| 관점 | 초기 결과 | 반영한 수정 | 최종 결과 |
|---|---:|---|---:|
| Performance | P1=1, P2=1 | #41/#42의 부하·지연·복구·자원 수치와 재현 명령을 ADR 후속 차단 gate로 추가하고 수용 기준 수를 바로잡음 | P0=0, P1=0 |
| Stability | P1=2, P2=1 | 3-R을 문서 변경 전 gate로 이동하고 merge/sync/cleanup을 분리했으며 placeholder 검사를 fail-closed로 수정 | P0=0, P1=0 |
| Security | P1=1, P2=1 | credential, least privilege, PII/PHI, replay 승인, auto-create 금지, readiness와 cleanup 승인 범위를 명시 | P0=0, P1=0 |
| Operator/Ops | P1=1, P2=1, P3=1 | no-match `rg`를 명시적 PASS로 처리하고 설치된 skill 이름 및 Task 4 책임을 바로잡음 | P0=0, P1=0 |
| Developer/API | P1=2, P2=1 | governed catalog 권위와 독립 version override 금지를 ADR/검사에 추가하고 동일 command·skill 문제를 수정 | P0=0, P1=0 |
| User/caller | P1=1, P2=2 | TODO 설명을 DB outbox+Kafka4로 맞추고 schema backlog를 중립화했으며 두 backlog 문서의 갱신일을 맞춤 | P0=0, P1=0 |

## 최종 여섯 관점 판정

| 관점 | 판정 | 최종 근거 |
|---|---|---|
| Performance | PASS | ADR exact prose가 burst/지속 부하, p95/p99, lag catch-up, oldest age, outage recovery, skew, heap/thread와 재현 명령을 #41/#42 차단 gate로 보존한다. |
| Stability | PASS | 3-R과 plan commit이 Task 1 선행 조건이며 PR merge, root sync, worktree/branch cleanup이 각각 검증 가능한 단계다. |
| Security | PASS | credential·principal·개인정보 경계, 승인된 replay, offset rewind/topic deletion 금지와 startup/readiness 검사가 계획에 포함됐다. |
| Operator/Ops | PASS | clean no-placeholder 상태가 exit 0으로 증명되고 exact-head CI, fresh approval, merge/sync/cleanup read-back이 실행 순서에 있다. |
| Developer/API | PASS | governed catalog가 버전 권위이며 #41 producer/module/relay와 #42 consumer/schema/DLT/replay 책임이 분리된다. |
| User/caller | PASS | Kafka4 결정 완료와 구현 미완료가 ADR, TODO, requirements README에서 일관되며 RabbitMQ와 Avro를 선택된 backlog로 오인하지 않는다. |

## Main-session 통합 검토

통합 실행 직전 main session은 production-path 검사에 있던
`rg && exit 1 || true`가 match까지 성공으로 덮을 수 있는 P1을 추가로 발견했다. 변경
경로 수집 실패를 먼저 차단한 뒤 exact Markdown allowlist와 비교하도록 교체했다. 이로써
Java, XML, properties와 임의 Markdown까지 fail-closed로 거부한다. whitespace 검사는
commit된 branch diff도 포함하도록 `git diff --check origin/develop`로 고정했다.

| 항목 | 판정 | 근거 |
|---|---|---|
| Spec 추적성 | PASS | spec의 12개 수용 기준이 Task 1~3의 exact prose와 검증 command에 연결된다. |
| 실행 순서 | PASS | 승인 spec/2-R -> plan/3-R -> plan commit -> ADR/backlog -> 검증/review -> PR/CI -> fresh merge approval 순서다. |
| 범위 통제 | PASS | 허용 write scope는 Markdown 문서뿐이며 production source/config/migration 검사는 fail-closed다. |
| 문서 일관성 | PASS | Kafka4-only, governed catalog, DB outbox authority와 #41/#42 미구현 상태가 세 사용자 문서에서 동일하다. |
| 운영 closeout | PASS | push와 remote head, live PR metadata, CI/review, merge SHA, root parity와 scoped cleanup을 각각 다시 읽는다. |
| 검증 명령 | PASS | `git diff --check`와 placeholder no-match command가 현재 plan에서 exit 0을 반환했다. |

## 최종 집계

| Priority | 수량 | 처리 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 초기 finding 전부 계획에 반영하고 해당 관점 재검토 통과 |
| P2 | 0 | 실행 혼동 가능성이 있는 finding까지 모두 반영 |
| P3 | 0 | Task 제목과 책임을 일치시켜 해소 |

### Step 3-R verdict: PASS

계획은 Issue #40의 문서 결정만 실행하며 후속 runtime 구현을 #41/#42로 남긴다. exact
plan은 여섯 관점과 main integration에서 P0=0/P1=0이고, plan/review commit 뒤 Task 1을
시작할 수 있다.
