# Diagram Feedback Resolution

## Context

The clinic-appointment README and requirements diagrams were rebuilt after
multiple rounds of visual QA. The first regenerated assets were syntactically
valid SVG/PNG files, but several diagrams still failed as communication tools:
edge routing crossed cards, relationship labels covered unrelated lines, some
ports produced flat 0-degree joins, sequence cards were too narrow for their
labels, and some outputs looked unlike the established bluetape4k wiki
best-practice style.

This lesson records what was criticized and how the final assets addressed it.

## Repeated Problems

### 1. SVG render success was mistaken for diagram quality

The early requirements conversion treated Mermaid rendering as enough. The
result was visibly different from the hand-curated README diagrams and from the
wiki best-practice references.

Resolution:

- Replaced raw Mermaid outputs with curated SVG assets generated from explicit
  layout data.
- Kept PNG and SVG pairs for every final requirements diagram.
- Used contact sheets and direct PNG inspection as the quality gate instead of
  relying on XML validity alone.

Future guidance:

- Do not claim a diagram is done because `cairosvg`, Mermaid, or XML parsing
  succeeds.
- For public README diagrams, inspect the rendered PNG and reject layouts that
  are hard to read even if they are technically valid.

### 2. Lines crossed cards or ran through table bodies

Several ERD and data-flow drafts routed relationships through cards. Increasing
the asset canvas was not enough; the important fix was to move high-degree
nodes into the center and route around card bounds.

Resolution:

- Moved `clinics` and `appointments` into central ERD positions because they are
  relationship hubs.
- Increased table spacing where cardinality labels became ambiguous.
- Repositioned `Doctors`, `DoctorSchedules`, `TreatmentTypes`, `Equipments`,
  `OperatingHours`, `Closures`, and `Holidays` until relationship lines could
  leave from specific sides without crossing table interiors.
- Reworked data-flow diagrams so storage cards such as PostgreSQL sit under
  event/notification cards when that avoids horizontal card crossings.

Future guidance:

- In ERDs, place the highest-degree parent and transaction tables near the
  center first, then place side tables around them.
- A relationship line crossing a table body is a hard failure, not a cosmetic
  issue.
- If a line crosses a card, change node placement or port selection before
  adding another bend.

### 3. Cardinality and relationship labels were not legible

Initial ERD lines ended like generic arrows and did not explain 1:N
relationships. Later versions added labels, but some labels overlapped other
relationship lines or sat too close to adjacent labels.

Resolution:

- Added explicit relationship labels such as `1:N`, `N:1`, and optional FK
  meaning near the owning line.
- Separated `Doctors` and `DoctorSchedules` enough that the viewer can tell
  which side is one and which side is many.
- Moved labels off busy intersections and avoided placing a label over another
  relationship route.

Future guidance:

- ERD lines need relationship meaning, not just arrow direction.
- Relationship labels belong to their own line and must not cover another line.
- If labels become ambiguous, increase spacing before reducing font size.

### 4. Orthogonal routing needed consistent corner and arrow treatment

Several diagrams mixed diagonal lines, orthogonal lines, and sharp-corner
polylines. Arrowheads were also inconsistent across state, architecture,
sequence, and data-flow diagrams.

Resolution:

- Standardized final diagrams on orthogonal horizontal/vertical routes where
  possible.
- Used small-radius rounded corners for bent routes.
- Increased arrowhead size and applied the same arrowhead scale across diagrams.
- Avoided mixing diagonal and orthogonal routing in the same architecture
  diagram.

Future guidance:

- Prefer straight horizontal or vertical lines when source and target can align.
- Use a bend only when it reduces crossings or clarifies ownership.
- Keep bend count minimal; extra segments are visual noise.
- Do not mix diagonal and orthogonal routing styles in one diagram family.

### 5. Ports and edge angles matter

Some routes technically connected the right nodes but entered a card at a flat
0-degree angle or through a crowded side. Examples included the `Clinics` to
`Equipments` relationship and `AppointmentController` edges that all entered
from the same side.

Resolution:

- Changed `Clinics -> Equipments` to a bottom-to-right route and attached it to
  a lower point on the `Equipments` right side.
- Changed `Doctors -> Appointments` to a top/right-to-top route after moving
  supporting tables.
- Split `AppointmentController` connections by role: the Angular request enters
  bottom-to-top, while domain/event/database flow exits on other sides.

Future guidance:

- Port side is part of the diagram contract. A correct target node is not
  enough.
- Avoid multiple unrelated flows entering the same card side when the card has
  free top, right, bottom, or left ports.
- Reject 0-degree joins that make cardinality, ownership, or direction hard to
  read.

### 6. Sequence diagrams must follow the wiki best-practice style

The first sequence diagrams had narrow participant cards and crowded call lines.
Labels collided with other labels or sat too close to adjacent call lines.

Resolution:

- Rebuilt requirements sequence diagrams in the same visual family as the wiki
  best-practices examples.
- Widened participant cards beyond their label text.
- Increased card font size only after widening the cards so labels still had
  breathing room.
- Increased vertical spacing between calls and kept labels off neighboring call
  lines.

Future guidance:

- Sequence participant boxes must be wider than their text.
- Call labels need their own vertical space; labels touching neighboring calls
  are a failed layout.
- When applying a best-practice style, match spacing and hierarchy, not only
  colors.

### 7. Root and module README diagrams should be curated, not exhaustive

After the requirements diagrams were rebuilt, not every diagram belonged in
every README. Dumping the full catalog into module READMEs would make them hard
to scan.

Resolution:

- Kept `docs/requirements` as the full catalog.
- Added only representative diagrams to each README:
  - root README: one appointment creation flow
  - `appointment-api`: creation, booking, and status lifecycle
  - `appointment-core`: slot, closure reschedule, and equipment unavailability
  - `appointment-notification`: notification events and HA reminders
  - `appointment-solver`: solver data and closure-reschedule scenario
  - frontend README: patient booking and equipment unavailability
- Left `appointment-event` with its existing event architecture diagram because
  the new notification/event data-flow diagram is better owned by the
  notification module README.

Future guidance:

- Module README diagrams should answer the module reader's first question.
- Keep the full visual catalog in `docs/requirements`; use module READMEs as
  curated entry points.

### 8. README asset locations must stay canonical

Some root README diagrams were also copied under `docs/assets/readme-*`, while
the actual README references used `docs/images/readme-diagrams`. That duplicate
asset location made future updates ambiguous.

Resolution:

- Deleted unused `docs/assets/readme-charts` and `docs/assets/readme-diagrams`
  contents.
- Updated `scripts/generate-diagrams.mjs` so README diagram generation writes
  only to `docs/images/readme-diagrams`.
- Kept non-README illustrative assets such as
  `docs/assets/clinic-appointment-workbench.png` in `docs/assets`.

Future guidance:

- README-facing generated diagrams belong under `docs/images/readme-*`.
- `docs/assets` should hold standalone illustrative assets, not duplicate
  generated README diagrams.
- If a generated asset is not referenced, delete it instead of leaving a stale
  alternate copy.

## Verification Used

- Requirements SVG XML parse check.
- Requirements PNG/MMD/SVG asset count check.
- Requirements markdown asset link check.
- README image link check for root and module README files.
- `node --check scripts/generate-diagrams.mjs`.
- `git diff --check`.
- Visual review of rendered PNGs and contact sheets during diagram iterations.

## Rule For Future Diagram Work

Work one diagram at a time. Read the source README or requirement section,
choose the intended reader, generate SVG and PNG, inspect the PNG, and only then
move to the next diagram. If the user reports a visual defect, record whether it
was a layout, routing, label, port, style, or asset-location problem, then add a
targeted verification so the same defect is not reintroduced.
