# Issue #23 Capacitor foundation 구현 7-Tier review

## 검토 범위와 기준

- 대상 module slice: `frontend/appointment-frontend`
- 구현 기준: `origin/develop` (`49a86b1f`)부터 최종 구현 기준 HEAD
  (`a7d42a7a`)까지의 diff와 이 review 후속 보안·사용성 보완
- 부모 Epic: [#13](https://github.com/bluetape4k/clinic-appointment/issues/13)
- 현재 이슈: [#23](https://github.com/bluetape4k/clinic-appointment/issues/23)
- 다음 stacked 단계: [#430](https://github.com/bluetape4k/clinic-appointment/issues/430)
- 검토 방식: 여섯 독립 관점과 main-session 통합을 합친 7-Tier review

이번 slice는 기존 Angular 22 SPA 산출물을 Capacitor 8.5.0 WebView shell에
연결하는 foundation이다. API origin·CORS·cookie·CSRF, PWA, native storage,
typed bridge와 실제 디바이스 검증은 변경하지 않고 각각 #430·#431·#26·#25·#27·#24
경계로 유지했다.

## 관점별 결과

| Tier | 관점 | 근거 | 결과 |
|---|---|---|---|
| 1 | Performance | `package.json`, `capacitor.config.ts`, `angular.json`, 생성된 `android/`·`ios/` | 기존 Angular bundle을 복사하는 thin shell만 추가되어 runtime hot path, retry, polling, 무제한 buffer와 동시성 경로가 생기지 않았다. 기존 initial chunk `624.18 kB`와 lazy chunk 생성은 lesson에 기록했다. | N/A — P0=0, P1=0, P2=0, P3=0 |
| 2 | Stability | `capacitor.config.ts`, `npx cap config`, 두 번의 `cap sync`, `npx cap doctor` | config read-back과 동기화가 반복 실행에 성공했고 iOS·Android doctor가 모두 `looking great`를 반환했다. lifecycle·async·resource ownership 코드는 변경하지 않았다. | N/A — P0=0, P1=0, P2=0, P3=0 |
| 3 | Security | `capacitor.config.ts`, `android/`, `ios/`, `package-lock.json` | `server.url`, cleartext 허용, native token 저장, 임의 bridge와 API/auth 변경이 없다. Android `allowBackup=false`와 `backup_rules.xml`·`data_extraction_rules.xml`로 WebView/auth 데이터 복원 경계를 차단했고 FileProvider를 앱 전용 `Pictures/`로 축소했다. `npm audit --omit=dev --audit-level=moderate`는 0건이다. 전체 audit의 moderate 3건은 개발 전용 CLI의 `xcode -> uuid <11.1.1>` 경고로 다음 dependency maintenance에 보류한다. | 수정 후 PASS — P0=0, P1=0, P2=1 deferred, P3=0 |
| 4 | Operator/Ops | README 2개, lesson, generated platform project, `android/app/build.gradle`, rollback plan | `cap:sync`와 플랫폼 open 명령, SDK 요구사항, browser/native 검증 경계를 문서화했다. `google-services.json`은 없을 때만 조건부 skip하고 malformed/plugin 오류는 숨기지 않는다. native SDK가 없는 호스트에서는 생성·doctor까지만 수행하고 #24로 넘긴다. 변경된 CI/workflow·배포 설정은 없다. | 수정 후 PASS — P0=0, P1=0, P2=0, P3=0 |
| 5 | Developer/API | `package.json`, `package-lock.json`, `capacitor.config.ts`, Android package/test, plan allowlist | Capacitor 의존성과 script가 한 module에 고정되고 `engines.node >=22.0.0` 및 README prerequisite가 실제 toolchain과 일치한다. `webDir`는 실제 Angular output과 일치하고 generated Android test의 package/application ID도 `io.bluetape4k.clinic.appointment`로 맞췄다. plan의 diff allowlist는 source/plan/review/lesson 증거를 포함하도록 보완했다. `frontend/appointment-frontend/src`와 Kotlin production source는 변경하지 않았다. | 수정 후 PASS — P0=0, P1=0, P2=0, P3=0 |
| 6 | User/Caller | `README.md`, `README.ko.md`, lesson | 기존 route lazy loading, responsive shell, `TenantApiClient`, patient cookie/XSRF, workforce Bearer scope와 browser test를 그대로 재사용한다. Node.js 22 prerequisite와 지원하지 않는 native build/device 보장을 명시적으로 분리했다. | 수정 후 PASS — P0=0, P1=0, P2=0, P3=0 |
| 7 | Main-session integration | 전체 diff, spec/plan/review/lesson, Issue #23 metadata | 여섯 관점의 초기 P1/P2/P3를 통합하고 모두 수리하거나 보류 근거와 재검증을 반영했다. 변경은 frontend package/config/platform, README, 설계·실행·review·lesson으로 제한되며 workflow 증거 allowlist도 명시적이다. | PASS — P0=0, P1=0, P2=1 deferred, P3=0 |

## 수렴 기록

| 반복 | 초기 finding | 조치 | 재검증 결과 |
|---|---|---|---|
| 1차 독립 검토 | Security P1: Android `allowBackup=true`; Security/Operator P2: broad FileProvider root와 광범위한 Google Services 예외; Developer/User P2: plan allowlist·Node prerequisite; Security P3: signing 파일 ignore 주석 | `allowBackup=false`와 legacy/modern backup rule 추가, FileProvider를 앱 전용 경로로 축소, missing-only Gradle 조건, plan allowlist와 `engines.node`/README prerequisite 보완, secret ignore 활성화 | XML parse, `git check-ignore`, build/sync/unit/TypeScript/E2E/audit 재실행 |
| 최종 통합 | P2: Capacitor CLI 개발 전용 `xcode -> uuid <11.1.1>` moderate 3건 | 호환선·lockfile 재현성을 위해 `npm audit fix --force`를 적용하지 않고 다음 dependency maintenance로 보류 | runtime audit 0건, P0=0, P1=0; P2=1 deferred |

## 통합 판정과 검증 증거

최종 통합 결과는 `PASS — P0=0, P1=0`이다. 초기 P1 보안·P2 운영/개발자/사용자·P3
비밀 파일 ignore 지적은 수정했으며, 개발 전용 CLI audit 경고만 다음 dependency
maintenance로 P2 보류했다. 다음 검증은 이 diff와 현재 호스트 경계를 함께 확인한다.

| 검증 | 결과 |
|---|---|
| `npm run build` | 통과; initial `624.18 kB`, lazy chunks 생성 |
| `npm test -- --watch=false` | 통과; 45 files, 327 tests |
| `npx tsc --noEmit -p tsconfig.app.json` | 통과 |
| `npm run test:e2e` | 통과; Chromium 10 tests |
| `npm run cap:sync` + 두 번째 `npx cap sync` | 통과; 반복 sync 성공 |
| `npx cap config` | 통과; appId·appName·webDir read-back 일치 |
| `npx cap doctor` | 통과; iOS·Android `looking great` |
| `npm audit --omit=dev --audit-level=moderate` | 통과; runtime 0 vulnerabilities |
| `npm install --package-lock-only --ignore-scripts` | 통과; package-lock root `engines.node` read-back |
| `xmllint --noout` (Android manifest/backup rules/FileProvider XML) | 통과 |
| `git check-ignore -v` (signing/Google service files) | 통과; `.jks`, `.keystore`, `google-services.json` 무시 |
| Korean terminology audit | 통과; findings 0 |
| `git diff --check` | 통과 |
| `npm run docs:verify` | 기존 source contract 3건 실패; `frontend/appointment-frontend/src` 무변경이며 #295 범위 |

## 명시적 검증 경계와 후속 책임

- full Xcode가 아닌 Command Line Tools만 활성화되어 `xcodebuild` native build를
  수행할 수 없고, `adb`, `sdkmanager`, `xcrun simctl`도 호스트에 없다. 따라서
  이번 PASS는 platform 생성/config/sync와 browser 계약에 대한 것이며 native
  build·실기기 smoke를 의미하지 않는다. 해당 검증은 #24에서 수행한다.
- 로컬 Node `26.7.0`과 저장소 고정 Node `22.22.3`이 다르다. Capacitor CLI의
  `Node >=22.0.0` 조건과 `package.json`의 `engines.node`는 일치하지만 최종 Node 22
  CI 결과를 우선 증거로 삼는다.
- `bluetape-kotlin-patterns`와 `bluetape4k-assertions`는 이번 TypeScript/native
  foundation slice에 적용 대상 Kotlin production/test source가 없어 N/A다. 기존
  Angular unit/browser test와 저장소의 frontend 재사용 경계를 유지했으며, 무관한
  Kotlin assertion dependency를 추가하지 않았다.
- `npm run docs:verify`의 세 실패는 기존 tenant-aware source contract 공백으로
  #295가 소유한다. Capacitor slice에서 임의로 source를 수정하지 않았다.
- 전체 `npm audit`의 개발 전용 CLI 경고는 runtime 취약점이 아니며, 호환선과
  lockfile 재현성을 지키기 위해 `npm audit fix --force`를 거부했다. 다음 dependency
  maintenance에서 upstream 갱신 가능성을 재평가하는 P2 보류 항목이다.
- Android backup/restore, FileProvider URI, Google Services plugin 오류 처리는
  실제 SDK lint·build와 device 계측을 #24에서 재검증한다. 현재 호스트에서는 XML
  parse와 정적 설정 read-back까지만 증명했다.

## 문서 품질 게이트

- SPW-01 범위·독자: PASS
- SPW-02 명령·경로·Issue 링크의 source read-back: PASS
- SPW-03 unsupported native capability와 검증 경계: PASS
- SPW-04 README·lesson·plan·review 간 용어/결과 일치: PASS
- SPW-05 Korean terminology audit 및 `git diff --check`: PASS

### 최종 게이트

`frontend/appointment-frontend` 구현 slice는 7-Tier review를 통과했다.
P0/P1 blocker가 없고, P2 하나는 개발 전용 CLI dependency maintenance로 보류했다.
native SDK·기존 docs contract·Node 22 CI 차이는 위와 같이 책임 이슈와 검증 경계로
명시되어 있으므로 PR 생성과 CI 검증을 진행할 수 있다.
