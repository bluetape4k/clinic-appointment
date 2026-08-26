# Issue #425 persistence capability 경계 교훈

## 배경

알림 worker와 observation store가 concrete `JdbcNotificationOutboxRepository`를 public
생성자로 직접 받았고, waitlist adapter와 consumer fixture에도 concrete persistence 표면이
남아 있었다. 구현자가 repository 세부사항을 알아야 fake를 주입할 수 있어 persistence
소유권과 호출자 API 방향이 섞인 상태였다.

## 결정

- worker 연산은 `NotificationOutboxWorkPersistence`, bounded observation은
  `NotificationOutboxObservationPersistence` capability로 분리했다.
- `JdbcNotificationOutboxRepository`는 기존 SQL·transaction 호출 의미를 유지하면서 두
  capability와 writer를 구현한다.
- Spring 자동 구성은 concrete repository를 내부에서 capability wrapper에 연결한다.
  사용자 fake bean의 `@Primary`·`@Qualifier` 대체 계약은 만들지 않고, 테스트와 호출자는
  wrapper 생성자에 fake capability를 직접 주입한다.
- waitlist concrete repository constructor overload와 fixture concrete import를 제거했다.
- constructor ABI 변경과 named-argument `repository` → `persistence` migration을 README,
  spec, plan에 명시했다.

## 결과

첫 RED contract는 concrete constructor·overload·import 세 경계를 실패로 포착했다. 이후
최소 capability interface와 wrapper 위임을 추가하고, fake delegate·Spring wiring·lease/
readiness/retry/retention·fixture/API boundary를 재검증했다. notification module check와
API canary compile/test가 통과했고 migration fingerprint에는 변화가 없었다.

## 재사용 원칙

- 새 persistence abstraction을 늘리기 전에 기존 repository 연산의 transaction·lease·retry
  의미를 그대로 capability로 옮긴다.
- public wrapper는 capability만 보고, concrete 조립은 auto-configuration 내부에 둔다.
- 테스트 fixture 식별자는 `Base58.randomString(8)`을 사용하고, 검증은
  `bluetape4k-assertions`로 통일한다.
- source scan, jar inventory, reflection/API fixture를 함께 사용해 Kotlin source와 JVM ABI
  경계를 각각 증명한다.

## 유지할 guard

- consumer fixture compile 전에 concrete repository token source guard를 실행한다.
- capability contract에 constructor reflection과 fake delegation을 남긴다.
- Spring 기본 wiring regression을 유지해 내부 concrete 조립이 끊기지 않게 한다.
- 새 wrapper가 concrete implementation을 다시 공개할 때는 source/ABI migration decision과
  7-Tier review를 먼저 갱신한다.

## 검토 상태

- 7-Tier 구현 검토: `PASS — P0=0, P1=0, P2=0, P3=0`
- Korean terminology audit: findings=0
- merge: fresh approval 전 `PENDING`
