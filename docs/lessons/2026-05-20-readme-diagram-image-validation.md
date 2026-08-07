# README 다이어그램 이미지 검증

## 배경

clinic-appointment의 README 다이어그램을 공용 pastel infographic renderer로
갱신했다. 현재 Mermaid block과 git history에서 복원한 기존 README 다이어그램
이미지 링크를 모두 다룬다.

## 결정

README에는 PNG를 노출하고 재사용을 위해 SVG source를 PNG 파일 옆에 보관한다.
다이어그램 label은 영문만 사용한다. `Diagram`, `Architecture`, `Sequence Diagram`
같은 일반 제목은 module별 English 제목으로 바꾼다. Sequence label에서 비영어
텍스트가 사라지면 의미 없는 일반 label 대신 참여 component 이름을 사용한다.

## 결과

- 렌더링된 artifact 14개
- PNG 파일 7개
- SVG source 파일 7개
- README 이미지 링크 누락 없음
- README 파일에 로컬 SVG 이미지 embed 없음
- 남은 Mermaid code block 없음
- shape-check 후보 없음

## 검증

- `node /Users/debop/work/bluetape4k/.omx/scripts/refine-readme-diagrams.mjs .`
- README 이미지 링크 및 Mermaid 잔여물 검사기
- PNG/SVG shape 검사기
- Visual contact sheet 검토: `/tmp/clinic-appointment-diagram-review-samples.png`
- `git diff --check`

## 향후 지침

가능하면 원본 Mermaid source에서 다시 생성하고, 이미 교체한 block은 git history도
확인한다. 이미지 크기는 콘텐츠에 맞춰 정하고, 의미 없이 채우는 가짜 node는
추가하지 않는다. SVG source를 보존하고 publish 전에 sample sheet를 확인한다.
