# AGENTS.md

This file provides guidance to Codex when working in this repository.

## Rule layering

- This root file defines repository-wide defaults.
- More specific `AGENTS.md` files in subdirectories override or refine these rules for files under their directory.
- When working in a nested module, apply both the root rules and the closest child-directory rules.

## Project summary

- This is a Maven multi-module Java library for MyBatis/MyBatis-Plus field encryption, result decryption, and sensitive-response masking.
- The repository is intentionally conservative: unsupported encrypted-field SQL shapes should fail fast instead of being handled implicitly.
- Security boundaries are part of the product contract. Do not introduce behavior that leaks plaintext or real ciphertext into logs, exceptions, or debug output.

## Module map

- `common`: core metadata model, algorithm SPI, SQL rewrite engine, decryption, masking core, and MyBatis interceptor implementation. Keep this module framework-light.
- `migration`: standalone JDBC migration, checkpoint, schema generation, confirmation policy, and resume support for historical data migration.
- `bom`: dependency version alignment module for consumers.
- `spring-starter`: aggregator POM only.
- `spring-starter/spring2-starter`: Spring Boot 2.x adapter layer, `javax.servlet` based, Java 8 target.
- `spring-starter/spring3-starter`: Spring Boot 3.x adapter layer, `jakarta.servlet` based, Java 17 target.

## Toolchain and build rules

- The repository does not include Maven Wrapper. Use local `mvn`.
- Always prefer the repository-local Maven cache:
  - `mvn "-Dmaven.repo.local=.m2repo" test`
- Recommended acceptance command for most changes:
  - `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test`
- Root `pom.xml` targets Java 8 for shared modules. Do not use Java 9+ APIs in `common`, `migration`, or `spring-starter/spring2-starter`.
- `spring-starter/spring3-starter` is activated from the root build under JDK 17+ via the `java17-plus` profile. Do not assume it is always present in lower-JDK environments.
- Do not assume `-Dtest.groups` style filtering is wired unless you verify the current Surefire configuration first.

## Editing rules

- Read `docs/architecture.md` and `docs/sql-support-matrix.md` before changing rewrite, decryption, masking, or separate-table behavior.
- Public APIs, externally configurable properties, and core runtime components should have useful Javadoc that explains behavior, boundaries, and non-obvious constraints.
- Add short inline comments at core error-prone points, especially where batching, SQL rewrite safety, masking, separate-table reference semantics, or framework lifecycle ordering could be misread.
- Keep public behavior explicit. Do not add “best effort” support for encrypted-field `ORDER BY`, range predicates, aggregates, window functions, or similarly unsafe SQL unless the support matrix and tests are updated together.
- Keep `common` free of Spring Boot specific wiring when possible. Cross-module logic belongs in `common`; adapter and auto-configuration code belongs in the Spring starter modules.
- When changing Spring auto-configuration, web masking, properties binding, or starter-exposed beans, check both `spring2-starter` and `spring3-starter`. Maintain equivalent behavior unless a version-specific difference is required.
- Respect the `javax.*` versus `jakarta.*` split between Spring 2 and Spring 3 modules. Do not copy code blindly across the two starters.
- For separate-table encryption fields, preserve the current contract:
  - write SQL removes the logical encrypted column from the main table write path;
  - query predicates rewrite to `EXISTS` against the separate table;
  - post-write synchronization and post-read hydration remain explicit responsibilities of `SeparateTableEncryptionManager` and `ResultDecryptor`.
- Preserve masked logging behavior. Hash or assisted-query values may be logged only where the existing design already allows it; plaintext and real ciphertext must not appear.

## Testing expectations

- Any change in `common/src/main/java/.../core/rewrite` should add or update focused rewrite tests and at least one integration path that proves the end-to-end behavior.
- Any change in metadata loading or rule validation should update unit tests around annotation/config merging and invalid-rule rejection.
- Any change in decryption, masking, or query-result planning should update focused tests plus Spring Boot integration coverage when user-visible behavior changes.
- Any change in migration flow should update migration module tests for checkpointing, confirmation, compensation, or schema generation as applicable.
- Any change in starter wiring should be checked with the corresponding auto-configuration integration tests.

## Documentation expectations

- This repository keeps both Chinese and English user-facing docs. When public behavior, configuration, supported SQL, or migration semantics change, update both language variants where applicable.
- If supported or rejected SQL semantics change, update `docs/sql-support-matrix.md`.
- If runtime architecture or execution flow changes, update `docs/architecture.md`.
- If onboarding or usage changes, update `README.md` and related guides under `docs/`.

## Useful entry points

- Core rewrite orchestration: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java`
- Rewrite restrictions: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidator.java`
- Interceptor pipeline: `common/src/main/java/io/github/jasper/mybatis/encrypt/plugin/DatabaseEncryptionInterceptor.java`
- Result decryption: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/decrypt/ResultDecryptor.java`
- Separate-table synchronization and hydration: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java`
- Spring Boot auto-configuration:
  - `spring-starter/spring2-starter/src/main/java/io/github/jasper/mybatis/encrypt/config/MybatisEncryptionAutoConfiguration.java`
  - `spring-starter/spring3-starter/src/main/java/io/github/jasper/mybatis/encrypt/config/MybatisEncryptionAutoConfiguration.java`
