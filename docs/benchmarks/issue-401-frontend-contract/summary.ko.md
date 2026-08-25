# Issue #401 frontend 계약 검증 요약

## 검증 결과

| 항목 | 결과 |
|---|---|
| package Angular major | core/router/CLI/compiler-cli 모두 22 |
| 문서 검사 | root 2개·module 2개·requirements 1개, 5/5 통과 |
| source 계약 | route·guard·tenant storage·portal URL·patient auth URL·staff residual 8/8 통과 |
| stale 문구 | Angular 18/21, Karma, 전체 endpoint 완료, tenant routing 후속 문구 0건 |
| diagram source/output | generator와 architecture/module overview 4개 SVG/PNG를 Angular 22로 정렬 |
| 7-Tier blocker | P0=0, P1=0, P2=0, P3=0 |

`npm run docs:verify`는 문서와 source의 현재 checkout을 함께 읽는 정적 계약
검사다. 이 요약은 frontend runtime behavior나 직원 tenant/auth 전환의 완료를
주장하지 않으며, 해당 residual은 #295에서 추적한다.
