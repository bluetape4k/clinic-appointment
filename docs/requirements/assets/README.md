# Requirements Diagram Assets

This directory stores rendered assets generated from Mermaid blocks in
`docs/requirements/*.md`.

Each diagram keeps the extracted Mermaid source (`.mmd`) plus rendered `.svg`
and `.png` files.

| Source | Diagram | Assets |
|---|---|---|
| `architecture.md` | Module dependency graph | `architecture-01-module-dependency.{mmd,svg,png}` |
| `erd.md` | Table relationships | `erd-01-table-relationships.{mmd,svg,png}` |
| `domain-model.md` | Appointment state machine | `domain-model-01-appointment-state-machine.{mmd,svg,png}` |
| `data-flow.md` | Appointment creation flow | `data-flow-01-appointment-create.{mmd,svg,png}` |
| `data-flow.md` | Slot query flow | `data-flow-02-slot-query.{mmd,svg,png}` |
| `data-flow.md` | Closure reschedule flow | `data-flow-03-closure-reschedule.{mmd,svg,png}` |
| `data-flow.md` | Equipment unavailability flow | `data-flow-04-equipment-unavailability.{mmd,svg,png}` |
| `data-flow.md` | Notification event flow | `data-flow-05-notification-events.{mmd,svg,png}` |
| `data-flow.md` | Solver data flow | `data-flow-06-solver-data.{mmd,svg,png}` |
| `user-scenarios.md` | Patient booking sequence | `user-scenarios-01-patient-booking.{mmd,svg,png}` |
| `user-scenarios.md` | Status lifecycle sequence | `user-scenarios-02-status-lifecycle.{mmd,svg,png}` |
| `user-scenarios.md` | Closure reschedule sequence | `user-scenarios-03-closure-reschedule-solver.{mmd,svg,png}` |
| `user-scenarios.md` | Equipment unavailability sequence | `user-scenarios-04-equipment-unavailability.{mmd,svg,png}` |
| `user-scenarios.md` | HA reminder sequence | `user-scenarios-05-ha-reminder.{mmd,svg,png}` |

Rendering uses `@mermaid-js/mermaid-cli` with `mermaid-config.json` and
`puppeteer-config.json`.
