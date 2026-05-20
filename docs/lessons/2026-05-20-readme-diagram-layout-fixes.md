# README Diagram Layout Fixes

## Context

Follow-up visual QA found two layout defects in generated README diagrams:

- some architecture connectors were rendered as very short line segments where only the arrow head was visible
- sequence participant header labels were vertically biased toward the top of the header box

A related sequence issue was also fixed: self-calls previously rendered as zero-length arrows, which looked like a standalone arrow head.

## Decision

Keep the existing diagram style and update only geometry in the generated SVG/PNG assets. Architecture connector line segments must span the visible gap between adjacent cards. Sequence participant labels must use the same vertical-centering baseline as architecture cards. Sequence self-calls should render as a small loop instead of a zero-length line.

## Verification

- README image link check: missing=0, localSvgImageLinks=0, mermaidResidue=0
- PNG/SVG shape check: shapeCandidates=0
- architecture short connector check: shortArch=0
- sequence header alignment check: seqTop=0
- sequence zero-length arrow check: zeroSeq=0
- `git diff --check`
- visual samples reviewed for exposed root architecture and representative sequence diagrams

## Future Guidance

Treat arrow head-only connectors as a failed rendering even when the SVG is syntactically valid. Geometry checks should cover architecture connector length, sequence header baseline, and sequence self-call arrows before PR creation.

## 2026-05-20 ERD Layout Follow-up

`appointment-core-erd-01` was regenerated from the current Exposed table set
and `docs/requirements/erd.md`, not from the old compact image snapshot. The
new layout includes the scheduling tables that were missing from the previous
image and routes repeated `clinicId` references through a named FK lane instead
of drawing many long crossing arrows.

Future ERD diagrams should place parent, child, and bridge tables by
relationship cluster, then route FKs with orthogonal lanes. Reject any layout
where relationship lines pass through table interiors or where repeated parent
FKs turn the diagram into a dense center-crossing bundle.
