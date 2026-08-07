# 2026-05-20 — Solver 벤치마크 차트

## 배경

solver 벤치마크 보고서의 표는 명확했지만, 실행 시간과 move speed의 관계는
차트로 보여 주는 편이 이해하기 쉬웠습니다.

## 결정

`docs/images/readme-charts/` 아래에 정적 SVG + PNG 차트를 추가하고 solver
벤치마크 보고서에서 PNG를 연결합니다. 결과 표는 기준 자료로 유지합니다.

## 결과

solver 벤치마크 보고서는 이제 small, medium, large 시나리오에서 설정한
시간 제한과 실행 시간을 비교하고 move speed도 보여 줍니다.

## 검증

- `xmllint --noout docs/images/readme-charts/*.svg`
- `identify docs/images/readme-charts/*.png`
- Markdown 이미지 링크가 로컬 차트 파일을 가리키는지 확인했습니다.

## 향후 참고

solver 벤치마크 보고서는 최소한 실행 시간과 시간 제한을 함께 보여 줘야
합니다. 그래야 회귀 여유를 가장 빠르게 확인할 수 있습니다.
