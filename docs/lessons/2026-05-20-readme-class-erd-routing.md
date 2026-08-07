# README Class/ERD 라우팅

## 배경

README class와 ERD 이미지를 문서, blog post, presentation에서 재사용할 수 있도록
bluetape4k workspace 전체에서 다시 생성했다.

## 결정

class와 ERD 다이어그램에는 blocker를 고려해 lane을 선택하는 orthogonal connector
routing을 사용한다. pastel 색상과 기존 typography는 유지하되 cubic curve와
component 내부를 가로지르는 connector path는 피한다.

## 결과

다시 생성한 class/ERD SVG는 관계를 고려한 component 배치, 수평·수직 직선 lane,
더 작은 arrow marker, 수직으로 시작하고 끝나는 top/bottom port를 사용한다.
또한 horizontal lane은 component edge가 아니라 row midline 근처에 배치한다.

## 검증

- `node --check .omx/scripts/refine-readme-diagrams.mjs`
- 변경한 class/ERD SVG: cubic connector count `0`
- 변경한 class/ERD SVG: card-interior crossing candidates `0`

## 향후 지침

다이어그램을 다시 생성할 때는 blocker-aware route scoring을 유지하고, 이미지
변경 범위가 넓다면 수락하기 전에 contact sheet를 확인한다.
