# 2026-05-20 — Solver benchmark chart

## Context

The solver benchmark report had clear tables, but the execution-time and move
speed relationship was easier to understand as a chart.

## Decision

Add a static SVG + PNG chart under `docs/images/readme-charts/` and link the PNG
from the solver benchmark report. Keep the result table as the source of truth.

## Outcome

The solver benchmark report now shows execution time against the configured time
limit and move speed for small, medium, and large scenarios.

## Verification

- `xmllint --noout docs/images/readme-charts/*.svg`
- `identify docs/images/readme-charts/*.png`
- Markdown image link checked against the local chart file.

## Future

Solver benchmark reports should show at least execution time versus time limit,
because that is the fastest way to see regression headroom.
