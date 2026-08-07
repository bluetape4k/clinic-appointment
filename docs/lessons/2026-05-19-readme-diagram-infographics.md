# README 다이어그램 인포그래픽

## 배경

README 파일은 architecture, class, sequence, ERD 및 기타 다이어그램에 Mermaid
code block을 사용했다. workspace 전체의 시각 방향이 검토된 pastel infographic
PNG로 바뀌었고, 재사용을 위해 SVG source asset을 함께 보관한다.

## 결정

README의 Mermaid block을 생성한 PNG 이미지 링크로 교체하고, 대응하는 SVG source를
PNG 파일 옆에 저장한다. 다이어그램 텍스트는 영문만 사용하고, 큰 라벨에는
Architects Daughter, 상세 텍스트에는 Comic Mono를 사용하며 architecture, class,
sequence, ERD 다이어그램마다 전용 layout을 적용한다.

## 결과

bluetape4k.github.io/docs/readme-diagram-samples의 공용 2026-05-19 style guide로
README 다이어그램을 렌더링했다. repo-local 규칙이 있으면 root README asset은 해당
배치 규칙을 따른다.

## 검증

rsvg-convert로 PNG/SVG asset을 생성하고, 저장소 전체 변환 과정에서 README 링크를
확인했다.

## 향후 지침

README 다이어그램은 PNG embed로 사용하고, 편집을 위해 SVG source를 함께 보관한다.
시각적 일관성이 중요할 때 원본 Mermaid나 단순한 Mermaid theme 재색상으로 되돌리지
않는다.
