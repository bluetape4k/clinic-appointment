# Issue #40 Kafka4 메시징 결정 교훈

## 맥락

Issue #40은 production code 없이 Kafka4-only 메시징 계약을 spec, ADR과 backlog에
고정하는 작업이었다. 최종 검토는 문서 일관성뿐 아니라 후속 #41/#42가 그대로 실행해도
안전한 운영 계약과 검증 command인지 확인했다.

## 결정

- docs-only 범위 검사는 금지 확장자 목록이 아니라 exact 허용 경로 목록으로 차단한다.
- no-match가 성공 조건인 `rg`는 exit 1만 정상으로 인정하고 다른 오류를 성공으로 숨기지
  않는다.
- Kafka partition 증설은 단일 hot aggregate 해결책으로 취급하지 않는다. 같은 key의 이후
  record가 다른 partition으로 remap될 수 있으므로 pause/hold, drain/checkpoint 또는 새
  topic migration, dual-read/offset 전환과 ordering 증명을 선행한다.

## 놀라움과 실패

초기 계획의 `rg && exit 1 || true`는 금지 경로가 발견되어 `exit 1`이 실행돼도 마지막
`true`가 전체 command를 성공으로 만들었다. 확장자 blacklist도 `.java`, `.properties`,
`.xml`과 임의 Markdown을 놓쳤다. 또한 초기 spec은 lag/skew 대응으로 partition 증설을
먼저 검토하도록 했지만, Kafka의 partition-count 변경이 기존 key의 향후 routing을 바꿔
same-aggregate ordering 계약을 끊을 수 있다는 조건을 명시하지 않았다.

## 결과

- 변경 경로를 먼저 수집하고 anchored exact allowlist 밖의 모든 경로를 실패시킨다.
- whitespace 검사는 uncommitted diff만 보는 `git diff --check` 대신 branch 전체를 보는
  `git diff --check origin/develop`를 사용한다.
- spec과 ADR은 partition 증설을 irreversible ordering migration으로 분류하고 #41/#42의
  차단 gate에 포함한다.

## 검증 증거

- 실제 Issue #40 경로 9개 허용 목록에 대해 allowlist command가 exit 0을 반환했다.
- 허용 문서와 `src/main/java/com/example/Foo.java`를 섞은 simulation은 unexpected path를
  검출했다.
- `git diff --check origin/develop`와 placeholder no-match 검사가 exit 0을 반환했다.
- performance와 stability 최종 review는 수정된 exact diff를 다시 검토하도록 재실행했다.

## 검토에서 놓친 점

3-R의 여섯 계획 관점은 처음에 확장자 blacklist와 partition 증설의 ordering 위험을 모두
차단하지 못했다. command는 prose만 읽지 말고 정상 입력과 적대적 입력을 각각 실행해야
하며, 분산 시스템의 capacity 조정은 routing invariant까지 함께 검토해야 한다.

## 미래 guard

1. docs-only workflow는 exact allowlist에 허용 artifact를 열거하고 unknown path fixture로
   false PASS 여부를 시험한다.
2. negative search는 match, no-match, command error 세 상태를 구분한다.
3. Kafka partition/key 변경 계획은 기존 record와 신규 record의 ordering, offset,
   consumer assignment와 rollback 불가능성을 명시하지 않으면 구현 gate를 통과하지 못한다.
