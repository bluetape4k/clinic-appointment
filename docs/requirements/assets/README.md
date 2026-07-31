# Requirements Diagram Assets

This directory stores diagram assets for the Mermaid sketches embedded in
`docs/requirements/*.md`.

Each diagram keeps the extracted Mermaid source (`.mmd`) as the semantic sketch
plus curated `.svg` and `.png` files. The rendered assets are maintained as
direct SVG diagrams following the bluetape4k diagram style and Fireworks graph
layout rules; the Mermaid files are not the final visual source of truth.

Reader-facing diagrams use explicit locale suffixes:

- `*-en.svg` and `*-en.png` contain the English text.
- `*-ko.svg` and `*-ko.png` contain source-equivalent Korean text.
- Theme-aware diagrams may additionally provide `*-en-dark.{svg,png}` and
  `*-ko-dark.{svg,png}`. Their node IDs, topology, coordinates, and connector
  semantics must match the light variants.
- Both locale variants must preserve the same identifiers, topology, relative
  layout, connector semantics, colors, and technical names. Locale font
  metrics may change the exact text bounds and canvas dimensions.
- The unsuffixed `.mmd`, `.svg`, and `.png` files remain historical semantic
  sketches and visual sources. Reader-facing requirements documents use the
  matching locale-suffixed assets.

| Source | Diagram | Assets |
|---|---|---|
| `architecture.md` | Module dependency graph | `architecture-01-module-dependency.{mmd,svg,png}` |
| `erd.md` | Table relationships | `erd-01-table-relationships.{mmd,svg,png}` |
| `domain-model.md` | Appointment state machine | `domain-model-01-appointment-state-machine.{mmd,svg,png}` |
| `data-flow.md` | Appointment creation flow | `data-flow-01-appointment-create.{mmd,svg,png}` plus locale/theme variants |
| `data-flow.md` | Slot query flow | `data-flow-02-slot-query.{mmd,svg,png}` |
| `data-flow.md` | Closure reschedule flow | `data-flow-03-closure-reschedule.{mmd,svg,png}` |
| `data-flow.md` | Equipment unavailability flow | `data-flow-04-equipment-unavailability.{mmd,svg,png}` |
| `data-flow.md` | Durable notification outbox flow | `data-flow-05-notification-events.{mmd,svg,png}` plus locale/theme variants |
| `data-flow.md` | Solver data flow | `data-flow-06-solver-data.{mmd,svg,png}` |
| `user-scenarios.md` | Patient booking sequence | `user-scenarios-01-patient-booking.{mmd,svg,png}` plus locale/theme variants |
| `user-scenarios.md` | Status lifecycle sequence | `user-scenarios-02-status-lifecycle.{mmd,svg,png}` |
| `user-scenarios.md` | Closure reschedule sequence | `user-scenarios-03-closure-reschedule-solver.{mmd,svg,png}` |
| `user-scenarios.md` | Equipment unavailability sequence | `user-scenarios-04-equipment-unavailability.{mmd,svg,png}` |
| `user-scenarios.md` | Durable reminder sequence | `user-scenarios-05-ha-reminder.{mmd,svg,png}` plus locale/theme variants |

Render each locale PNG from its matching SVG with the project-standard
CairoSVG CLI:

```bash
~/.local/bin/cairosvg docs/requirements/assets/<diagram>-en.svg \
  -o docs/requirements/assets/<diagram>-en.png -s 2
~/.local/bin/cairosvg docs/requirements/assets/<diagram>-ko.svg \
  -o docs/requirements/assets/<diagram>-ko.png -s 2
```

After rendering, validate both SVG files with `xmllint` and inspect both PNG
files at full size. A locale pair is incomplete if only the README reference,
SVG source, or PNG render was updated.

The appointment creation flow, patient booking sequence, notification outbox
flow, and durable reminder sequence are generated from one locale/theme model:

```bash
node scripts/generate-notification-outbox-diagram.mjs
```

It emits the English/Korean light assets plus matching `-dark` SVG and PNG
variants. Reader-facing Markdown embeds them with `<picture>` so the selected
asset follows the browser color scheme.
