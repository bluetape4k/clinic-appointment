# 아키텍처 설계

## 모듈 의존성 그래프

```mermaid
graph TD
    core[appointment-core\n도메인·리포지토리·상태머신·서비스]
    event[appointment-event\n도메인 이벤트 발행/구독]
    solver[appointment-solver\nTimefold AI 스케줄러]
    notification[appointment-notification\nHA 알림 스케줄러]
    api[appointment-api\nSpring Boot REST API]
    frontend[appointment-frontend\nAngular 21]

    core --> event
    core --> solver
    core --> notification
    event --> notification
    core --> api
    event --> api
    solver --> api
```

![모듈 의존성 그래프](assets/architecture-01-module-dependency-ko.png)

[SVG](assets/architecture-01-module-dependency-ko.svg) · [Mermaid source](assets/architecture-01-module-dependency.mmd)

> `appointment-api`는 `appointment-notification`에 **의존하지 않는다**.
> 알림은 도메인 이벤트를 구독하여 독립적으로 동작한다.

## 주요 설계 결정 (ADR)

### ADR-1: 디렉토리 구조 — 하이브리드 플랫 구조

**결정**: 백엔드 모듈은 루트 직하 플랫 배치, Angular는 `frontend/` 서브디렉토리.

**이유**: 백엔드 6개 모듈은 플랫으로 충분히 관리 가능. Angular는 Node.js 기반으로 Kotlin 빌드 체계와 다르므로 분리가 자연스럽다.

**결과**: `settings.gradle.kts`에서 백엔드는 `includeModules()`, 프론트엔드는 `includeFrontendModules()`로 자동 스캔.

---

### ADR-2: bluetape4k-projects 의존성 — 조건부 Composite Build

**결정**: 로컬에 `../bluetape4k-projects`가 있으면 `includeBuild`로 소스 직접 참조, 없으면 Maven Central 좌표 사용.

**이유**: 로컬 개발 시 bluetape4k 라이브러리 수정 즉시 반영. CI 환경에서는 Maven Central 자동 폴백.

```kotlin
// settings.gradle.kts
val bluetape4kProjectsDir = file("../bluetape4k-projects")
if (bluetape4kProjectsDir.exists()) {
    includeBuild(bluetape4kProjectsDir) { ... }
}
```

---

### ADR-3: 패키지명 — io.bluetape4k.clinic.appointment

**결정**: `io.bluetape4k.scheduling.appointment` → `io.bluetape4k.clinic.appointment`

**이유**: 독립 저장소이므로 `clinic` 도메인을 명시. `scheduling`은 bluetape4k-experimental 내부 컨텍스트이므로 독립 저장소에 부적합.

---

### ADR-4: SlotCalculationService vs SolverService 역할 분리

**결정**: 두 서비스를 공존시키되 용도를 명확히 분리.

| 서비스 | 용도 | 특성 |
|--------|------|------|
| `SlotCalculationService` | 환자 대면 실시간 슬롯 조회 (단건) | Greedy, 빠름 |
| `SolverService` | 관리자 배치 최적화 (대량 예약 재배치) | Timefold, 전역 최적 |

---

### ADR-5: 알림 모듈 독립성

**결정**: `appointment-api`는 `appointment-notification`에 의존하지 않음.

**이유**: 알림은 `AppointmentDomainEvent`를 구독하는 독립 컴포넌트. API가 알림 모듈 없이도 동작해야 한다.

**결과**: API 서버와 알림 스케줄러는 별도 프로세스로 배포 가능.

---

### ADR-6: git 히스토리 — 단순 소스 복사

**결정**: `bluetape4k-experimental/scheduling/`에서 파일만 복사, 초기 커밋으로 시작.

**이유**: 커밋 히스토리가 짧았고, 독립 저장소의 깨끗한 시작이 더 가치 있다. 원본은 `bluetape4k-experimental`에서 참조 가능.

---

### ADR-7: API Controller 테스트 — MockMvc → RestClient

**결정**: `@SpringBootTest(RANDOM_PORT)` + Spring Boot 4 `RestClient` 방식으로 전면 전환 (v0.3.0).

**이유**: MockMvc는 실제 HTTP 스택을 타지 않아 필터/인터셉터 누락 위험. RestClient는 실제 포트에서 전 계층을 통과하므로 통합 테스트 신뢰도가 높다.

---

### ADR-9: appointment-core 패키지 구조 — model/service 배치

**결정**: 슬롯 계산 value type(`AvailableSlot`, `SlotQuery`, `TimeRange`)을 `service.model` 대신 `model.service`에 배치.

**이유**: `model/` 하위에 DB 무관 데이터 타입을 일관성 있게 집중. `service.model`은 "서비스의 내부 구현 세부 사항"처럼 읽히고, `model.service`는 "서비스 계층용 도메인 모델"로 의도가 명확하다.

**결과**:

| 패키지 | 내용 |
|--------|------|
| `model.dto` | DB 조회 결과 Record DTO (16개 엔티티) |
| `model.service` | 슬롯 계산 value type — `AvailableSlot`, `SlotQuery`, `TimeRange` |
| `model.tables` | Exposed ORM 테이블 정의 |

미래에 Exposed DAO 방식 Entity가 추가되면 `model.entities`에 배치. `model.entities`는 Exposed에 의존하므로 `appointment-domain` 모듈(예정)에 위치.

---

### ADR-8: Flyway 마이그레이션 — 벤더별 SQL 분리

**결정**: H2 / PostgreSQL / MySQL 각각 별도 SQL 파일 유지 + CI 매트릭스로 전 벤더 검증.

**이유**: ORM-agnostic DDL 문법 차이(자동 증가, 타입명 등)가 벤더별로 달라 단일 SQL로 모두 커버 불가.

**결과**: `resources/db/migration/h2/`, `postgresql/`, `mysql/` 경로 분리. `FlywayMigrationTest`가 CI에서 3 벤더 모두 실행.

---

### ADR-10: Scheduling Policy Foundation — V9/V10 분리 배포

**결정**: V9는 scheduling policy definition, scope head, effective snapshot,
activation command, preview job, generic outbox 호환 컬럼을 먼저 추가한다. V10은
legacy 예약/플랜 outbox column 제거 또는 aggregate-only 소비 전환을 별도 배포로
다룬다.

**이유**: 예약 정책은 향후 예약 생성, 운영 장애 복구, 재확인, overbooking, 고객
신뢰도 signal에 영향을 준다. 한 번의 migration에서 writer, reader, worker, consumer를
동시에 바꾸면 aggregate-null, dual-write 불일치, stale snapshot을 운영 중에 되돌리기
어렵다.

**결과**:

| 단계 | 계약 |
|------|------|
| V9 | 새 테이블과 generic aggregate column 추가, legacy column 유지, writer dual-write |
| V10 준비 | aggregate-null 0건, legacy/new parity, 모든 writer version dual-write 관측 |
| V10 | aggregate-only 소비 또는 legacy 제거를 별도 검증 뒤 수행 |

### ADR-11: Scheduling Policy Transaction Ownership

**결정**: controller는 HTTP status/header와 path-derived scope만 구성한다. 정책 lifecycle,
revision/generation CAS, approval separation, preview evidence, activation command
claim은 application service와 repository transaction이 소유한다.

**이유**: 정책 명령은 DB row lock, idempotency unique key, durable command lease,
scope head revision이 동시에 맞아야 한다. HTTP 계층에서 부분 판단을 하면 multi-dialect
동시성 계약을 유지하기 어렵다.

**결과**:

| 영역 | 소유자 |
|------|------|
| path tenant/clinic 검증 | controller + `TenantClinicAccessChecker` |
| actor 해석 | `ActorContextResolver` |
| lifecycle/CAS/승인 | `SchedulingPolicyCommandService` |
| preview admission/polling | `SchedulingPolicyPreviewService` |
| due activation claim | `SchedulingPolicyWorker` + repository lease |
| metric cardinality 제한 | `SchedulingPolicyMetrics` |

### ADR-12: Effective Read Double-Read and Fail-Closed

**결정**: effective snapshot은 compile 전후로 권위 generation을 다시 읽는다. 두 generation이
다르면 snapshot을 반환하지 않고 retryable conflict로 실패한다.

**이유**: 정책이 activation되는 순간에 오래된 tenant baseline과 새 clinic override를 섞어
예약 결정을 내리면, 호출자는 재현 가능한 snapshot hash를 신뢰할 수 없다.

**결과**: `GET .../effective`는 generation conflict를
`POLICY_EFFECTIVE_READ_CONFLICT`, 권위 저장소 장애를
`POLICY_EFFECTIVE_READ_UNAVAILABLE`로 반환한다. 두 경우 모두 stale cache를 관대하게
반환하지 않는 fail-closed 계약이다.

---

### ADR-13: 외부 메시징 — Kafka4 전용 outbox relay

**결정**: 외부 broker 기반 메시징은 `bluetape4k-kafka4`, Spring Kafka 4,
Jackson 3 조합만 지원한다. DB가 aggregate와 outbox의 transaction authority이며,
별도 relay가 commit된 outbox를 Kafka4에 발행한다. 버전은 bluetape4k governed catalog를
따르며 clinic-appointment가 Kafka client, Spring Kafka 또는 Jackson 버전을 독립적으로
override하지 않는다.

**전달 계약**:

- end-to-end at-least-once와 stable event ID 기반 producer/consumer 멱등성을 사용한다.
- consumer dedup unique key는 stable logical consumer/stream identity와 `eventId`로 구성하고,
  topic/partition/offset은 provenance로만 기록해 partition 변경이나 topic migration 뒤에도
  같은 event의 side effect가 다시 실행되지 않게 한다.
- aggregate scope를 partition key로 사용해 같은 aggregate의 순서를 보존한다.
- partition 증설은 단일 hot aggregate 해결책이 아니며, 기존 key remap에 대비한 producer
  pause/relay hold, drain/checkpoint 또는 새 topic migration과 ordering 증명 없이 실행하지 않는다.
- envelope는 `eventId`, `eventType`, `schemaVersion`, UTC `occurredAt`,
  tenant/clinic/aggregate scope, correlation/causation ID와 bounded payload를 가진다.
- DB와 Kafka를 하나의 전역 exactly-once transaction으로 표현하지 않는다.
- unsafe typing, FQN type header, 기본 tombstone/null payload와 raw PHI DLT 복제를 금지한다.

**보안·운영 gate**:

- broker credential을 저장소에 커밋하지 않고 producer/consumer principal을 필요한
  topic/action과 application scope로 제한한다.
- patient/PII 식별자를 key, metric label, log 또는 raw payload 출력에 넣지 않는다.
- replay는 별도 group, 승인된 scope/offset, dry-run과 audit을 요구하며 운영 group
  offset을 되감지 않는다. rollback도 offset rewind나 topic 삭제로 event를 숨기지 않는다.
- application topic auto-create를 금지하고 startup/readiness에서 authn/authz, topic/config,
  serializer/envelope 호환성을 확인한다.

**후속 검증 gate**: #41/#42는 구현 전에 burst와 지속 부하, publish-to-ack p95/p99,
consumer lag catch-up, oldest-age, broker outage recovery, partition skew, heap/thread 상한과
재현 명령을 수치화한다. relay lease/fencing·bounded backpressure와 record/header/depth
상한, partition-change ordering migration도 해당 spec과 테스트의 차단 기준이다.

**후속 책임**:

| 이슈 | 책임 |
|---|---|
| #41 | `appointment-messaging`, producer envelope/partition key, 세 dialect outbox lease/fencing migration, bounded relay와 readiness |
| #42 | consumer idempotency/offset, Schema Registry compatibility, retry/DLT/quarantine와 승인된 replay |

**기각**: Kafka3는 Spring Boot 3/Jackson 2 line이라 기각한다. RabbitMQ는 replay와
schema evolution 요구 및 bluetape4k runtime 지원이 약해 기각한다. broker-neutral
abstraction은 Kafka partition/offset/replay 의미를 숨기는 YAGNI이므로 도입하지 않는다.

**근거**: 상세 failure mode, 보안·운영 계약과 검증 gate는
`docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`를 따른다.
