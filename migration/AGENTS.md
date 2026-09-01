# AGENTS.md

This file refines the root rules for `migration/`.

## Scope

- `migration` provides standalone JDBC migration support for existing encrypted-field data.
- This module covers migration planning, checkpoint state, resume behavior, schema SQL generation, confirmation policy, and compensation flow.

## Compatibility rules

- Target Java 8 only.
- Keep this module usable outside Spring Boot. Do not couple migration execution to starter-specific configuration.
- Favor deterministic file and JDBC behavior. Migration state and resume logic must remain stable across reruns.

## Change boundaries

- Treat migration correctness and resumability as the primary contract.
- Do not weaken confirmation or risk-acknowledgement flows for convenience.
- Preserve idempotency where the current design already promises it.
- When changing generated SQL, keep dialect-specific constraints explicit. Do not silently guess unsupported database semantics.

## Testing focus

- Changes to checkpointing, cursor handling, or resume logic should update resume and state-store tests.
- Changes to schema generation should update schema SQL generator tests and any dialect-specific assertions.
- Changes to task orchestration or compensation should update execution-flow and compensation tests.
- Prefer extending existing H2/JDBC-based migration tests rather than adding unverified logic without execution coverage.

## Related docs

- `../docs/migration-guide.zh-CN.md`
- `../docs/migration-guide.en.md`
- `../docs/migration-production-runbook.zh-CN.md`
- `../docs/migration-production-runbook.en.md`
- `../docs/migration-cursor-design.zh-CN.md`
- `../docs/migration-cursor-design.en.md`
