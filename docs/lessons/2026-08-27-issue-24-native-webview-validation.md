# Issue #24 native WebView 검증 lesson

## 배경

브라우저 mobile profile은 route와 viewport 회귀를 빠르게 잡지만, iOS WKWebView와
Android WebView의 native build, intent, simulator/emulator, process lifecycle을
증명하지 않는다. 현재 개발 호스트는 full Xcode와 Android SDK가 없어 이 경계를
실행할 수 없었다.

## 결정

1. `native:environment`가 xcodebuild/xcrun/adb/sdkmanager 상태를 안전한 metadata로
   출력한다.
2. `native-webview-ci.yml`은 `ref`와 `expected_sha`를 모두 받아 checkout SHA를
   검증하고, 플랫폼별 build·install·launch·deep-link 결과를 별도 report로 업로드한다.
3. report schema는 platform, exact commit, bounded toolchain/commands, passed/failed만
   허용하며 credential과 raw output을 거부한다.
4. Playwright WebKit iPhone/Chromium Pixel contract는 native 결과와 별도로 집계한다.
5. 달력 route의 `toISOString()` UTC 변환은 한국 표준시 WebView deep-link에서 날짜를
   하루 앞당길 수 있으므로 현지 날짜 formatter/parser를 공용화하고 회귀 테스트를
   추가한다.

## 결과와 증거

- mobile contract 4건, Chromium 전체 E2E 23건, Angular calendar-state 18건 통과
- native validator/report/workflow contract 통과 및 `actionlint` 통과
- `:appointment-api:build`와 기존 `bluetape4k-assertions` 회귀 경계 통과
- local probe는 `targets.ios=false`, `targets.android=false`; 이는 실패를 숨기지 않는
  올바른 PENDING 증거다.

## 놓치기 쉬운 점

- Playwright `iPhone 13` descriptor는 WebKit을 사용하므로 CI에 `webkit` browser를
  설치해야 한다.
- 모바일 project를 Playwright 전체 testDir에 열어 두면 기존 WebKit cookie/keyboard
  시나리오가 의도치 않게 확장된다. mobile contract 전용 `testMatch`로 범위를 고정하고,
  기존 전체 회귀는 Chromium project로 유지한다.
- `reactivecircus/android-emulator-runner`의 script는 job `working-directory`를
  상속하지 않으므로 script 내부에서 frontend directory로 이동해야 한다.
- 날짜 route는 `Date.toISOString().slice(0, 10)`을 사용하면 현지 자정이 UTC 전날로
  직렬화될 수 있다. route/API 경계는 현지 날짜 formatter를 사용한다.

## 재발 방지

- native CI를 실행할 때 workflow 파일이 있는 branch를 `--ref`에 지정하고, 같은
  `HEAD_SHA`를 입력 `ref`와 `expected_sha`에 전달한다.
- report artifact의 `commit`이 job head와 같은지 확인하기 전에는 native PASS나 Epic
  closeout을 선언하지 않는다.
- hosted runner가 없으면 Issue checkbox를 미완료로 두고, 다음 세션에서 environment
  probe와 exact workflow receipt부터 재개한다.
