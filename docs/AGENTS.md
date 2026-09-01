# AGENTS.md

This file refines the root rules for `docs/`.

## Scope

- This directory contains the user-facing architecture, quick-start, migration, support-matrix, and operational documentation.
- Treat docs as product contract, not after-the-fact notes.

## Editing rules

- Keep Chinese and English variants aligned when the same feature is documented in both languages.
- Update `sql-support-matrix.md` whenever supported, rejected, or partially supported SQL semantics change.
- Update `architecture.md` when execution flow, layering, or component responsibilities change.
- Update quick-start and guide documents when configuration keys, defaults, or onboarding steps change.
- Preserve terminology consistency:
  - encryption field / assisted query column / like query column
  - separate-table encryption
  - sensitive response / masked column / masked algorithm
- Do not document speculative support. If behavior is fail-fast or unsupported, say so directly.

## Special area

- `superpowers/` contains design notes and plans. Keep dated filenames and avoid rewriting historical intent unless the user asked for that maintenance explicitly.
