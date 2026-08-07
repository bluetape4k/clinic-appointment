# 다이어그램 피드백 반영

## 배경

clinic-appointment README와 요구사항 다이어그램은 여러 차례 시각 QA를 거친
뒤 다시 만들었습니다. 처음 다시 생성한 자산은 문법적으로 유효한
SVG/PNG였지만, 몇몇 다이어그램은 여전히 정보를 전달하는 도구로서 문제가
있었습니다. edge routing이 카드를 가로지르고, 관계 레이블이 관련 없는
선을 덮었으며, 일부 port는 평평한 0-degree 연결을 만들었습니다. 시퀀스
카드는 레이블을 담기에는 너무 좁았고, 일부 결과는 기존 bluetape4k wiki
best-practice 스타일과도 달랐습니다.

이 lesson에는 어떤 피드백이 있었고 최종 자산에서 어떻게 반영했는지를
기록합니다.

## 반복된 문제

### 1. SVG 렌더 성공을 다이어그램 품질로 오인

초기 요구사항 변환에서는 Mermaid 렌더링만으로 충분하다고 판단했습니다.
그 결과는 수작업으로 다듬은 README 다이어그램 및 wiki best-practice
참조와 눈에 띄게 달랐습니다.

해결:

- raw Mermaid 출력물을 명시적인 레이아웃 데이터에서 생성한 검수된 SVG
  자산으로 교체했습니다.
- 최종 요구사항 다이어그램마다 PNG와 SVG 쌍을 유지했습니다.
- XML 유효성만 믿지 않고 contact sheet와 PNG 직접 검사를 품질 gate로
  사용했습니다.

향후 지침:

- `cairosvg`, Mermaid 또는 XML parsing이 성공했다는 이유만으로 다이어그램이
  완료되었다고 판단하지 않습니다.
- 공개 README 다이어그램은 렌더링된 PNG를 검사하고, 기술적으로 유효하더라도
  읽기 어려운 레이아웃은 거부합니다.

### 2. 선이 카드를 가로지르거나 테이블 본문을 통과

여러 ERD와 data-flow 초안에서 관계선이 카드를 통과했습니다. 자산 캔버스를
키우는 것만으로는 충분하지 않았습니다. 핵심 수정은 연결 차수가 높은
노드를 중앙으로 옮기고 카드 경계를 피해 선을 라우팅하는 것이었습니다.

해결:

- `clinics`와 `appointments`는 관계 허브이므로 ERD 중앙 위치로 옮겼습니다.
- cardinality 레이블이 모호해지는 곳의 테이블 간격을 넓혔습니다.
- 관계선이 테이블 내부를 통과하지 않고 특정 측면에서 나갈 수 있도록
  `Doctors`, `DoctorSchedules`, `TreatmentTypes`, `Equipments`,
  `OperatingHours`, `Closures`, `Holidays`의 위치를 조정했습니다.
- 수평 카드 교차를 피할 수 있도록 PostgreSQL 같은 storage 카드를
  event/notification 카드 아래에 두는 방식으로 data-flow 다이어그램을
  다시 구성했습니다.

향후 지침:

- ERD에서는 연결 차수가 가장 높은 parent와 transaction 테이블을 먼저
  중앙 가까이에 배치한 뒤 주변에 보조 테이블을 둡니다.
- 관계선이 테이블 본문을 가로지르는 것은 미관 문제가 아니라 hard failure입니다.
- 선이 카드를 통과하면 bend를 추가하기 전에 노드 위치나 port 선택을
  바꿉니다.

### 3. Cardinality와 관계 레이블이 읽기 어려움

초기 ERD 선은 일반적인 화살표처럼 끝났고 1:N 관계를 설명하지 못했습니다.
이후 버전에서 레이블을 추가했지만, 일부 레이블이 다른 관계선을 겹치거나
인접한 레이블과 너무 가까웠습니다.

해결:

- 소유 관계선 가까이에 `1:N`, `N:1`, 선택적 FK 의미 같은 명시적인 관계
  레이블을 추가했습니다.
- 어느 쪽이 one이고 어느 쪽이 many인지 구분할 수 있도록 `Doctors`와
  `DoctorSchedules` 사이를 충분히 벌렸습니다.
- 혼잡한 교차점에서 레이블을 옮기고, 다른 관계 경로 위에 레이블을 놓지
  않았습니다.

향후 지침:

- ERD 선에는 화살표 방향뿐 아니라 관계의 의미가 있어야 합니다.
- 관계 레이블은 자신의 선에 속하며 다른 선을 덮어서는 안 됩니다.
- 레이블이 모호해지면 글꼴을 줄이기 전에 간격을 늘립니다.

### 4. Orthogonal routing의 모서리와 화살표 처리가 일관되지 않음

여러 다이어그램에서 대각선, 직교선, 각진 polyline을 섞어 사용했습니다.
state, architecture, sequence, data-flow 다이어그램 사이에서 arrowhead도
일관되지 않았습니다.

해결:

- 가능한 경우 최종 다이어그램을 수평·수직 직교 경로로 표준화했습니다.
- 꺾인 경로에는 작은 반지름의 둥근 모서리를 사용했습니다.
- arrowhead 크기를 키우고 다이어그램 전체에 동일한 arrowhead 비율을
  적용했습니다.
- 하나의 architecture 다이어그램에서 대각선과 직교 routing을 섞지
  않았습니다.

향후 지침:

- source와 target을 정렬할 수 있으면 곧은 수평 또는 수직선을 우선합니다.
- 교차를 줄이거나 소유권을 명확하게 할 때만 bend를 사용합니다.
- bend 수를 최소화합니다. 불필요한 선분은 시각적 잡음입니다.
- 하나의 다이어그램 계열에서 대각선과 직교 routing 스타일을 섞지 않습니다.

### 5. Port와 edge 각도가 중요함

일부 경로는 올바른 노드에 기술적으로 연결되었지만, 평평한 0-degree 각도로
카드에 들어가거나 혼잡한 측면을 통과했습니다. 예로 `Clinics`와
`Equipments` 관계, 모두 같은 측면에서 들어오던 `AppointmentController`
edge가 있었습니다.

해결:

- `Clinics -> Equipments`를 bottom-to-right 경로로 바꾸고 `Equipments` 오른쪽
  측면의 아래쪽 지점에 연결했습니다.
- 보조 테이블을 옮긴 뒤 `Doctors -> Appointments`를 top/right-to-top
  경로로 바꿨습니다.
- `AppointmentController` 연결을 역할별로 나눴습니다. Angular request는
  bottom-to-top으로 들어오고 domain/event/database 흐름은 다른 측면으로
  나갑니다.

향후 지침:

- Port side는 다이어그램 계약의 일부입니다. 올바른 target node만으로는
  충분하지 않습니다.
- 카드에 비어 있는 top, right, bottom, left port가 있으면 서로 관련 없는
  여러 흐름이 같은 카드 측면으로 들어가지 않게 합니다.
- cardinality, 소유권, 방향을 읽기 어렵게 만드는 0-degree 연결은 거부합니다.

### 6. Sequence 다이어그램은 wiki best-practice 스타일을 따라야 함

첫 시퀀스 다이어그램은 participant 카드가 좁고 호출선이 혼잡했습니다.
레이블이 다른 레이블과 충돌하거나 인접한 호출선에 너무 가까웠습니다.

해결:

- 요구사항 시퀀스 다이어그램을 wiki best-practices 예제와 같은 시각적
  계열로 다시 만들었습니다.
- participant 카드를 레이블 텍스트보다 넓게 만들었습니다.
- 카드 폭을 먼저 넓힌 뒤 카드 글꼴 크기를 키워 레이블 주변에 여유를
  확보했습니다.
- 호출 사이의 수직 간격을 늘리고 레이블이 인접 호출선에 닿지 않게 했습니다.

향후 지침:

- Sequence participant box는 텍스트보다 넓어야 합니다.
- 호출 레이블에는 고유한 수직 공간이 필요합니다. 인접 호출에 닿는 레이블은
  실패한 레이아웃입니다.
- best-practice 스타일을 적용할 때는 색상뿐 아니라 간격과 계층도 맞춥니다.

### 7. 루트와 모듈 README 다이어그램은 전체 목록이 아니라 선별본이어야 함

요구사항 다이어그램을 다시 만든 뒤 모든 다이어그램을 모든 README에 넣을
필요는 없었습니다. 전체 catalog를 모듈 README에 쏟아 넣으면 훑어보기
어려워집니다.

해결:

- `docs/requirements`를 전체 catalog로 유지했습니다.
- 각 README에는 대표 다이어그램만 추가했습니다.
  - 루트 README: appointment creation flow 1개
  - `appointment-api`: creation, booking, status lifecycle
  - `appointment-core`: slot, closure reschedule, equipment unavailability
  - `appointment-notification`: notification events, HA reminders
  - `appointment-solver`: solver data, closure-reschedule scenario
  - frontend README: patient booking, equipment unavailability
- `appointment-event`에는 기존 event architecture 다이어그램을 유지했습니다.
  새 notification/event data-flow 다이어그램은 notification 모듈 README가
  소유하는 편이 적절하기 때문입니다.

향후 지침:

- 모듈 README 다이어그램은 해당 모듈을 읽는 사람이 처음 갖는 질문에
  답해야 합니다.
- 전체 시각 catalog는 `docs/requirements`에 두고, 모듈 README는 선별된
  진입점으로 사용합니다.

### 8. README 자산 위치는 canonical 상태를 유지해야 함

일부 루트 README 다이어그램은 `docs/assets/readme-*`에도 복사되어 있었지만,
실제 README 참조는 `docs/images/readme-diagrams`를 사용했습니다. 이 중복
자산 위치 때문에 향후 갱신 대상이 모호해졌습니다.

해결:

- 사용하지 않는 `docs/assets/readme-charts`와 `docs/assets/readme-diagrams`
  내용을 삭제했습니다.
- README 다이어그램 생성 결과가 `docs/images/readme-diagrams`에만 기록되도록
  `scripts/generate-diagrams.mjs`를 갱신했습니다.
- `docs/assets/clinic-appointment-workbench.png` 같은 README 외부의
  설명용 자산은 `docs/assets`에 유지했습니다.

향후 지침:

- README용 생성 다이어그램은 `docs/images/readme-*` 아래에 둡니다.
- `docs/assets`에는 독립적인 설명용 자산을 두고, 생성된 README 다이어그램의
  중복본은 두지 않습니다.
- 생성된 자산을 참조하는 곳이 없으면 오래된 대체 복사본으로 남겨 두지 말고
  삭제합니다.

## 사용한 검증

- 요구사항 SVG XML parse 검사.
- 요구사항 PNG/MMD/SVG 자산 개수 검사.
- 요구사항 Markdown 자산 링크 검사.
- 루트 및 모듈 README 파일의 이미지 링크 검사.
- `node --check scripts/generate-diagrams.mjs`.
- `git diff --check`.
- 다이어그램을 반복하는 동안 렌더링된 PNG와 contact sheet를 시각 검토.

## 향후 다이어그램 작업 규칙

한 번에 하나의 다이어그램만 작업합니다. 소스 README 또는 요구사항 섹션을
읽고 대상 독자를 정한 다음 SVG와 PNG를 생성하고 PNG를 검사한 뒤 다음
다이어그램으로 넘어갑니다. 사용자가 시각적 결함을 보고하면 그것이 layout,
routing, label, port, style, asset-location 문제 중 무엇인지 기록하고,
같은 결함이 재발하지 않도록 대상 검증을 추가합니다.
