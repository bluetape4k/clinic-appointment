# README 다이어그램 레이아웃 수정

## 배경

후속 visual QA에서 생성한 README 다이어그램의 레이아웃 결함 두 가지를 발견했다.

- 일부 architecture connector가 매우 짧은 선분으로 렌더링되어 arrow head만 보였다.
- sequence participant header label이 header box 위쪽으로 치우쳐 세로로 배치되었다.

관련된 sequence 문제도 함께 수정했다. 이전에는 self-call이 길이 0인 arrow로
렌더링되어 standalone arrow head처럼 보였다.

## 결정

기존 다이어그램 스타일을 유지하고 생성한 SVG/PNG asset의 geometry만 수정한다.
Architecture connector 선분은 인접한 card 사이의 보이는 간격을 가로질러야 한다.
Sequence participant label은 architecture card와 같은 vertical-centering baseline을
사용해야 한다. Sequence self-call은 길이 0인 선 대신 작은 loop로 렌더링한다.

## 검증

- README 이미지 링크 검사: missing=0, localSvgImageLinks=0, mermaidResidue=0
- PNG/SVG shape 검사: shapeCandidates=0
- architecture short connector 검사: shortArch=0
- sequence header alignment 검사: seqTop=0
- sequence zero-length arrow 검사: zeroSeq=0
- `git diff --check`
- exposed root architecture와 대표 sequence 다이어그램의 visual sample 검토

## 향후 지침

SVG 문법이 유효하더라도 arrow head만 보이는 connector는 렌더링 실패로 판단한다.
PR을 만들기 전에 geometry 검사가 architecture connector 길이, sequence header
baseline, sequence self-call arrow를 모두 확인해야 한다.

## 2026-05-20 ERD 레이아웃 후속 수정

`appointment-core-erd-01`은 기존 compact image snapshot이 아니라 현재 Exposed table
set과 `docs/requirements/erd.md`에서 다시 생성했다. 새 레이아웃에는 이전 이미지에
빠졌던 scheduling table이 포함되며, 반복되는 `clinicId` reference는 긴 교차 arrow를
여러 개 그리는 대신 이름이 있는 FK lane으로 라우팅한다.

향후 ERD 다이어그램에서는 관계 cluster에 따라 parent, child, bridge table을 배치한
뒤 orthogonal lane으로 FK를 라우팅한다. 관계선이 table 내부를 통과하거나 반복되는
parent FK가 다이어그램 중앙을 빽빽하게 가로지르는 묶음이 되는 레이아웃은 거부한다.
