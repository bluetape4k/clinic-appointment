# 요구사항 다이어그램 자산

이 디렉터리는 `docs/requirements/*.md`에 포함된 Mermaid 스케치의 다이어그램
자산을 저장합니다.

기존 Mermaid 문서에서 추출한 다이어그램은 의미 스케치인 `.mmd`와 검수된
`.svg`, `.png` 파일을 함께 유지합니다. Mermaid 소스가 없는 새 다이어그램은
로케일 접미사가 붙은 `.svg` 쌍을 의미·시각 소스로 직접 사용할 수 있습니다.
SVG 소스는 bluetape4k 다이어그램 스타일과 Fireworks 그래프 레이아웃 규칙을
따르며, PNG 파일은 렌더링 파생물입니다.

독자용 다이어그램은 명시적인 로케일 접미사를 사용합니다.

- `*-en.svg`와 `*-en.png`에는 영어 텍스트를 담습니다.
- `*-ko.svg`와 `*-ko.png`에는 소스와 동등한 한국어 텍스트를 담습니다.
- 테마를 인식하는 다이어그램은 `*-en-dark.{svg,png}`와
  `*-ko-dark.{svg,png}`를 추가로 제공할 수 있습니다. 이 파일의 node ID,
  topology, 좌표, connector 의미는 라이트 변형과 일치해야 합니다.
- 두 로케일 변형은 동일한 식별자, topology, 상대 레이아웃, connector 의미,
  색상, 기술 이름을 유지해야 합니다. 로케일별 글꼴 메트릭에 따라 정확한
  텍스트 경계와 캔버스 크기는 달라질 수 있습니다.
- 접미사가 없는 `.mmd`, `.svg`, `.png` 파일은 과거의 의미 스케치와 시각
  소스로 유지합니다. 독자용 요구사항 문서는 일치하는 로케일 접미사 자산을
  사용합니다.

| 소스 | 다이어그램 | 자산 |
|---|---|---|
| `architecture.md` | Module dependency graph | `architecture-01-module-dependency.{mmd,svg,png}` |
| `architecture.md` | Multitenancy identity와 key authority | `architecture-02-multitenancy-key-authority-{en,ko}.{svg,png}` |
| `erd.md` | Table relationship | `erd-01-table-relationships.{mmd,svg,png}` |
| `domain-model.md` | Appointment state machine | `domain-model-01-appointment-state-machine.{mmd,svg,png}` |
| `data-flow.md` | Appointment creation flow | `data-flow-01-appointment-create.{mmd,svg,png}` 및 locale/theme variant |
| `data-flow.md` | Slot query flow | `data-flow-02-slot-query.{mmd,svg,png}` |
| `data-flow.md` | Closure reschedule flow | `data-flow-03-closure-reschedule.{mmd,svg,png}` |
| `data-flow.md` | Equipment unavailability flow | `data-flow-04-equipment-unavailability.{mmd,svg,png}` |
| `data-flow.md` | Durable notification outbox flow | `data-flow-05-notification-events.{mmd,svg,png}` 및 locale/theme variant |
| `data-flow.md` | Solver data flow | `data-flow-06-solver-data.{mmd,svg,png}` |
| `user-scenarios.md` | Patient booking sequence | `user-scenarios-01-patient-booking.{mmd,svg,png}` 및 locale/theme variant |
| `user-scenarios.md` | Status lifecycle sequence | `user-scenarios-02-status-lifecycle.{mmd,svg,png}` |
| `user-scenarios.md` | Closure reschedule sequence | `user-scenarios-03-closure-reschedule-solver.{mmd,svg,png}` |
| `user-scenarios.md` | Equipment unavailability sequence | `user-scenarios-04-equipment-unavailability.{mmd,svg,png}` |
| `user-scenarios.md` | Durable reminder sequence | `user-scenarios-05-ha-reminder.{mmd,svg,png}` 및 locale/theme variant |

프로젝트 표준 CairoSVG CLI를 사용해 일치하는 SVG에서 각 로케일 PNG를
렌더링합니다.

```bash
~/.local/bin/cairosvg docs/requirements/assets/<diagram>-en.svg \
  -o docs/requirements/assets/<diagram>-en.png -s 2
~/.local/bin/cairosvg docs/requirements/assets/<diagram>-ko.svg \
  -o docs/requirements/assets/<diagram>-ko.png -s 2
```

렌더링 후 `xmllint`로 두 SVG 파일을 검증하고, 두 PNG 파일을 전체 크기로
검사합니다. README 참조, SVG 소스, PNG 렌더 중 하나만 갱신하면 로케일 쌍이
완성되지 않은 것으로 봅니다.

예약 생성 흐름, 환자 예약 시퀀스, 알림 outbox 흐름, 내구성 리마인더 시퀀스는
하나의 로케일·테마 모델에서 생성합니다.

```bash
node scripts/generate-notification-outbox-diagram.mjs
```

이 명령은 영어·한국어 라이트 자산과 일치하는 `-dark` SVG·PNG 변형을
생성합니다. 독자용 Markdown은 `<picture>`로 이 자산을 포함하므로 선택되는
자산이 브라우저 색 구성표를 따릅니다.
