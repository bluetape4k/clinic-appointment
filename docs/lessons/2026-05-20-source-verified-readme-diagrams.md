# Source-Verified README Diagrams

## Context

Clinic README diagrams contained stale labels (`JwtAuthFilter`, `Redis Leader?`) and an ERD that omitted several current scheduling tables.

## Decision

Update labels to current source names and replace the ERD with a current table overview derived from `appointment-core/model/tables`.

## Verification

Validate SVG XML, rerender PNG assets, and grep the diagram for stale or invented table/class labels.

## Future Guidance

ERD images should be regenerated from current Exposed table objects, not from old Mermaid snapshots.
