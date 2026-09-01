# AGENTS.md

This file refines the root rules for `spring-starter/`.

## Scope

- `spring-starter` is the adapter area for Spring Boot integration.
- Shared expectations for both starter variants live here; version-specific rules live in child directories.

## Change boundaries

- Keep behavior aligned between `spring2-starter` and `spring3-starter` unless a platform difference requires divergence.
- If you change bean names, default algorithms, properties binding, auto-configuration conditions, or interceptor registration, review both starter variants.
- Prefer shared behavior in `common`; starter modules should focus on wiring, conditions, configuration properties exposure, and web integration.

## Validation

- Starter wiring changes should be checked with auto-configuration tests in the affected starter.
- If a change affects user-facing configuration or documented defaults, update both Chinese and English docs where applicable.
