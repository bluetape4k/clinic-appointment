# Issue #184 방문 예약 commitment 구현 교훈

## 배경

단일 상품, 반복 시술권, 복합 패키지를 하나의 예약 모델로 다루려면 구매 당시
실행 BOM을 불변 Plan revision으로 고정하고, 한 방문 안의 여러 세부 진료와 자원
점유를 proposal 단위로 함께 관리해야 했다. 상품·구매·환불·임상 완료의 원본
판정은 외부 서비스가 소유하고 예약서비스는 검증된 event와 snapshot만 소비했다.

## 유지할 결정

- 새 구매는 기존 Plan에 합치지 않고 새 Plan으로 만든다.
- 상품 version 변경은 기존 이력을 수정하지 않고 동일 Plan의 새 revision을
  활성화한다. 새 변경 proposal은 과거 proposal item revision이 아니라 현재 활성
  Plan revision의 미완료 미래 항목으로 계산한다.
- 고객 요청은 정책에 따라 자원을 점유하지 않는 `PROPOSED` 또는 제한 시간 동안
  점유하는 `HELD`가 된다. 관리자 승인 시 `HELD` allocation은 재생성하지 않는다.
- 확정 예약 변경은 새 proposal과 고객 동의가 완료될 때까지 기존 확정 포인터와
  allocation을 유지한다.
- 만료·취소·확정은 commitment version CAS, allocation 변경, legacy projection,
  감사 event, outbox, 멱등 결과를 하나의 Exposed transaction에서 처리한다.
- 환불은 결제서비스가 소유하고 예약서비스에는 `REFUND` 같은 등록 취소 사유 code만
  전달한다. 자유 텍스트나 환불 금액을 예약 event에 넣지 않는다.

## 리뷰에서 발견한 핵심 누락

첫 구현은 고객이 proposal을 수락하거나 관리자가 직접 확정할 때 현재 정책을 다시
해석했다. 이 방식은 제안 이후 정책이 변경되면 고객이 본 조건과 서버가 검증하는
조건이 달라진다.
영속 proposal의 정책 snapshot ID로 snapshot hash·세대·원본 version을 다시
조회하고, 응답에도 이를 노출하도록 고쳤다.

최초 동의 검증은 DB가 생성할 appointment/proposal ID를 아직 알 수 없다.
proposal hash가 appointment ID를 포함하면 외부 동의가 검증한 hash와 영속
proposal hash가 달라진다. 생성 ID를 proposal 의미 hash에서 제외하고, tenant,
clinic, 환자 fingerprint, Plan, 정책 snapshot, 전역 unique evidence, 영속
proposal 소유권으로 재사용을 차단하는 계약을 명시했다.

또한 변경 proposal이 기존 proposal item의 과거 Plan revision을 사용하면 상품
version 전환·완료·환불이 만든 새 활성 revision을 되돌릴 수 있다. 기존 item은
Plan 식별자를 찾는 provenance로만 사용하고 실제 계산은 현재 활성 revision으로
수행하도록 변경했다.

## 검증 교훈

- H2 성공만으로 allocation 잠금·capacity 정합성을 주장하지 않는다.
- PostgreSQL 100 caller 동시 확정, 10만 allocation EXPLAIN, 보존 대상별 2만 row,
  MySQL migration을 별도 증거로 남긴다.
- 보존 index는 필터 열만 맞추지 말고 실제 `ORDER BY ... LIMIT` 순서를 지원해야
  한다. quarantine index는 `resolved_at, id`가 status보다 앞에 있어야 실제
  보존 query가 의도한 index를 선택했다.
- OpenAPI 경로를 추가하면 보안 오류 envelope 분류기, 필수 header 테스트, 공개
  문서의 경로 목록도 같은 변경에서 갱신해야 한다.

## 후속 운영 전제

production 활성화 전에는 retention owner가 정확히 하나라는 배포 증거, 실제
Gateway/동의/상품·구매 projection adapter, broker ACL과 schema registry,
PostgreSQL backup/restore 및 redrive reconciliation drill이 필요하다. 이 전제는
코드의 기능 완료와 별개로 운영 체크리스트에서 차단한다.
