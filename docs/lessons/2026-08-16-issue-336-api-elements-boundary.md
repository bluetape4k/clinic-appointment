# Issue #336 공개 API 경계 작업 교훈

## 원인

`java-library` 모듈의 production compile은 자체 implementation classpath로 통과했지만, 모듈 하나만 의존하는 소비자는 `Usage.JAVA_API` 요청으로 `apiElements`를 선택한다. 공개 supertype·생성자·메서드·annotation에 implementation 의존성의 타입이 남아 있으면 두 classpath가 달라져 소비자 컴파일이 실패한다. 통합 `appointment-api`가 같은 라이브러리를 직접 의존한 탓에 기존 애플리케이션 빌드에서는 이 경계가 가려졌다.

## 재사용할 패턴

1. 공개 선언의 실제 외부 type-use를 import와 KClass/시그니처로 소비하는 module-isolated fixture를 먼저 만든다.
2. fixture configuration에는 대상 project 하나만 넣고 `Usage.JAVA_API`와 JVM 21을 명시한다.
3. RED compiler error와 production source anchor를 짝지어 필요한 좌표만 `api` 또는 `compileOnlyApi`로 승격한다.
4. producer `jar`, selected `apiElements`, resolved coordinates, fixture compile task를 구조화 report와 task graph assertion으로 함께 고정한다.
5. auto-configuration의 optional classpath는 `ApplicationContextRunner`의 존재/부재 두 경로로 확인한다. compile-only 성공만으로 runtime dependency를 낮추지 않는다.
6. `api`와 `compileOnlyApi` direct coordinate 집합 및 producer의 direct API roots를 exact allowlist로 비교하고, report fingerprint를 현재 resolution에서 재계산한다. detached CI checkout에서도 `sourceRef=HEAD`를 기록할 수 있어야 한다.

## 다음 변경자를 위한 지침

새 public class, constructor, method, generic, supertype 또는 auto-configuration bean method가 외부 타입을 사용하면 production source와 같은 변경에서 다음을 갱신한다.

- 대응 module fixture의 직접 type-use
- 모듈 `api`/`compileOnlyApi` exact scope와 approved coordinate
- variants/classpath report의 inventory 및 fingerprint
- optional runtime 경로가 있으면 presence/absence 회귀
- RED/GREEN와 mutation 결과 문서

fixture를 통과시키기 위해 consumer configuration에 Kafka, Exposed, Redis, Testcontainers 또는 다른 애플리케이션 모듈을 우회 추가하지 않는다. 그 방식은 생산자의 `apiElements` 계약을 검증하지 못한다.

## 범위 경계

이번 작업은 같은 Gradle build의 project variant metadata와 source compatibility만 보장한다. 외부 Maven publication, 실제 broker/database/Redis, 운영 SLO, production benchmark는 별도 검증 대상이다.
