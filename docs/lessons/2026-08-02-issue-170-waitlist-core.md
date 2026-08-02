# Issue #170 대기열 핵심 기능 작업 lesson

## 맥락

Issue #170은 같은 날 예약 가능 용량을 대기열 제안으로 회수하는 Type-A 기능이다. 범위에는 상태 머신, Exposed 영속화, CAS/점유 경합, 만료·재시작 복구, H2/PostgreSQL/MySQL migration이 포함됐다. 이번 작업은 구현을 새로 넓히는 단계가 아니라, 사전 PR review와 durable lesson을 남기고 중단된 workflow receipt를 복구하는 단계였다.

## 결정

1. 기존 sequence 30의 receipt를 직접 수정하거나 잘라내지 않는다. `receipt-diagnose`와 `resume-check`로 신뢰 가능한 마지막 head를 확인한 뒤, 현재 session에 귀속된 owner를 `resume`으로 epoch 2로 전환한다.
2. 실패한 `main` lane의 기록은 immutable하게 보존하고, `main-recovery`에서 새 증거를 기록한 뒤 failed-lane resolution으로 연결한다. heartbeat는 liveness 증거일 뿐 완료 증거로 간주하지 않는다.
3. waitlist claim/recovery는 리소스 잠금과 상태 전이를 caller-owned `transaction {}` 안에서 수행한다. offer에 resource/capacity snapshot을 보존해 누락된 OFFERED hold를 복구하고, 시작 시각·hold deadline·decision expiry를 모두 경계로 사용한다.
4. 개인정보는 waitlist core나 이벤트 payload에 복제하지 않고 opaque reference만 유지한다. 이름·전화번호 보강은 후속 알림/회원 경계의 책임으로 남긴다.

## 예상 밖의 실패와 리뷰에서 발견한 누락

- 구현 프로세스가 workflow lease보다 오래 살아 `main` lane이 실패로 고정됐지만, receipt 자체는 sequence 30에서 checksum 검증을 통과한 건강한 상태였다. 문제는 데이터 손상이 아니라 completion proof와 owner liveness의 부재였다.
- 첫 구현 리뷰에서는 OFFERED 상태의 누락 hold 복구, ACCEPTED replay의 시작 시각 경계, OFFERED hold의 예약 시작 시각 초과, Exposed/Flyway 제약 동기화가 충분히 닫히지 않았다. 이 항목들은 claim repair/replay 검증, recovery 경계, schema check, migration matrix와 테스트를 추가한 뒤 최종 review에서 재검증했다.
- checklist의 CL-01과 liveness 증거가 초기 실행 순서에서 늦게 생성됐다. 이후 checklist를 worktree에 고정하고, owner transfer 전후 mutation-check와 recovery lane을 사용해 순서를 복구했다.

## 결과

- receipt owner transfer는 sequence 31에서 성공했고, 새 owner 파일은 mode `0600`이다. 기존 failed main history와 checksum lineage는 보존됐다.
- waitlist/state/reliability 테스트, migration matrix, 전체 core/API 테스트, build/compile, Kotlin/Exposed 정적 검사, diff 검증이 fresh pass했다.
- 최종 사전 PR 리뷰는 `PASS`, `P0=0`, `P1=0`이다. lesson과 checklist는 추적 가능한 로컬 커밋에 포함하며, PR/push/merge는 실행하지 않는다.

## 재사용 가능한 검증 증거

- targeted waitlist/state/reliability 및 migration 명령: 모두 exit 0.
- 전체 테스트 XML: core `594 tests / 0 failures / 0 errors / 0 skipped`, API `593 tests / 0 failures / 0 errors / 2 intentional skips`.
- build/compile: exit 0.
- `STATIC_SCAN_FINAL=PASS`, `DIFF_CHECK=PASS`.
- receipt: pre-recovery verify sequence 30 pass → owner transfer sequence 31 pass → recovery lane/component/main verification completion proof는 같은 run의 후속 receipt event로 기록한다.

## 다음 작업의 guard

1. Type-A 작업은 구현 전에 GNO 조회, checklist 생성, receipt topology/liveness 확인을 완료한다.
2. receipt가 running이지만 process가 바뀌었으면 먼저 `receipt-diagnose`와 `resume-check`를 실행한다. healthy receipt의 JSON을 직접 편집하지 말고 owner transfer 또는 등록된 recovery lane만 사용한다.
3. mutation 전에는 현재 session과 정확한 target path로 `mutation-check`를 실행하고, owner file은 `0600`인지 확인한다.
4. failed lane을 지우지 말고 correction lane을 완료한 뒤 lane-resolve, component evidence, main verification 순서로 completion proof를 채운다.
5. Kotlin 구현/리뷰에서는 `bluetape-kotlin-patterns` 정적 검사와 caller-owned Exposed transaction, Serializable DTO의 `serialVersionUID`, 한국어 KDoc, assertion 규칙을 사전 PR gate에 포함한다.
6. PR을 만들기 전에 A-08 final review와 A-09 lesson commit을 모두 완료하고, PR/merge 권한이 없는 경우에는 그 사실과 남은 gate를 checklist에 명시한다.
