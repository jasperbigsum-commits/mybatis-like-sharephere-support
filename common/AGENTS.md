# AGENTS.md

This file refines the root rules for `common/`.

## Scope

- `common` contains the reusable runtime core shared by migration and both Spring starters.
- Prefer putting product behavior here only when it is framework-agnostic and shared across adapters.

## Compatibility rules

- Target Java 8 only. Do not introduce Java 9+ APIs, records, switch expressions, `var`, or Jakarta-only types.
- Keep dependencies minimal and runtime-oriented. Avoid pulling Spring Boot concerns into this module.
- Be careful with API surface changes because this module is consumed by `migration`, `spring2-starter`, and `spring3-starter`.

## Change boundaries

- Metadata merge, rule validation, algorithm SPI, SQL rewrite, decryption, masking core, and interceptor behavior belong here.
- Auto-configuration, servlet/web integration, and Spring condition wiring do not belong here.
- Keep Javadoc current for public classes, SPI interfaces, configuration properties, and core runtime classes whose behavior is part of the library contract.
- Add concise inline comments for high-risk implementation details such as SQL AST transformations, parameter remapping, result-plan inference, independent-table batching, and sensitive-data masking decisions.
- Preserve the current fail-fast design for unsupported encrypted SQL semantics.
- Preserve log masking and exception hygiene. Do not expose plaintext, real ciphertext, or unsafe diagnostic detail.

## Testing focus

- Rewrite changes should update focused tests under `src/test/java/.../core/rewrite`.
- Metadata or rule-model changes should update metadata tests and invalid-configuration coverage.
- Decryption, masking, query-result planning, and interceptor changes should add focused unit coverage and trigger starter integration verification.
- If a change affects dialect quoting or hidden helper columns, verify both direct rewrite tests and end-to-end MyBatis paths.

## Important entry points

- `src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java`
- `src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidator.java`
- `src/main/java/io/github/jasper/mybatis/encrypt/plugin/DatabaseEncryptionInterceptor.java`
- `src/main/java/io/github/jasper/mybatis/encrypt/core/decrypt/ResultDecryptor.java`
- `src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java`
