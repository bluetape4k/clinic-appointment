# README Hero And WIP Refresh

## Context

The clinic appointment app had bilingual README files but lacked the shared
workbench-style visual entrypoint and a current WIP snapshot.

## Decision

Store the generated clinic scheduling workbench in
`docs/assets/clinic-appointment-workbench.png`, reference it from both README
locales, and create `WIP.md` from currently assigned GitHub issues.

## Outcome

The README now opens with the clinic scheduling visual and a clearer purpose
statement before feature details.

## Verification

- Confirmed the generated asset exists as a PNG under `docs/assets`.
- Verified both README locales reference the shared image path.
