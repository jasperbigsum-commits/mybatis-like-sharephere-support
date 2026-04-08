# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project architecture

This repository implements a MyBatis/MyBatis-Plus field encryption plugin inspired by ShardingSphere. The runtime path has five layers that are easier to understand together than file-by-file:

1. **Metadata loading**
   - `EncryptMetadataRegistry` merges configuration-based rules from `DatabaseEncryptionProperties` with annotation-based rules loaded by `AnnotationEncryptMetadataLoader`.
   - Rules are cached both by physical table name and by entity class. `warmUp(...)` is called from runtime hooks so statement parameter/result entity types are registered before rewrite/decrypt work starts.
   - Separate-table fields are validated here: they must define `storageTable` and `assistedQueryColumn`.

2. **Algorithm registry**
   - `AlgorithmRegistry` resolves three algorithm families by bean name: `CipherAlgorithm`, `AssistedQueryAlgorithm`, and `LikeQueryAlgorithm`.
   - Default beans are wired in `MybatisEncryptionAutoConfiguration`: `sm4`/`sm3`/`normalizedLike`, plus compatibility options `aes` and `sha256`.

3. **SQL rewrite engine**
   - `SqlRewriteEngine` is the core runtime piece. It parses SQL with JSqlParser and rewrites `INSERT`, `UPDATE`, `DELETE`, and `SELECT` before execution.
   - Same-table encrypted fields are rewritten to storage columns and helper columns (`assistedQueryColumn`, `likeQueryColumn`).
   - Separate-table fields are handled differently:
     - write SQL drops the logical encrypted column from the main-table statement;
     - query predicates on that logical field are rewritten into `EXISTS` subqueries against the separate table;
     - unsupported operations fail fast rather than producing unsafe semantics.
   - `RewriteResult` carries rewritten SQL, updated parameter mappings, and masked logging output.

4. **MyBatis interception pipeline**
   - `DatabaseEncryptionInterceptor` hooks three MyBatis lifecycle points:
     - `StatementHandler.prepare` → rewrite SQL and parameters
     - `Executor.update` → synchronize separate-table encrypted data after writes
     - `ResultSetHandler.handleResultSets` → decrypt and hydrate returned entities
   - This means write-path behavior is split: main-table SQL is rewritten before execution, then separate-table synchronization happens after the executor returns.

5. **Result decryption and separate-table hydration**
   - `ResultDecryptor` decrypts same-table ciphertext fields in-place on returned entity objects.
   - `SeparateTableEncryptionManager` handles the separate-table path:
     - after writes, it synchronizes external encrypted rows;
     - after reads, it loads ciphertext from the external table by entity id and hydrates decrypted values back into entity properties.
   - Query-time support for separate-table fields therefore depends on both SQL rewrite and post-query hydration.

## Important behavior boundaries

- The project is intentionally conservative. Unsupported encrypted-field operations such as `ORDER BY`, `GROUP BY`, range predicates, encrypted aggregates, and some advanced subquery/window scenarios are rejected explicitly in `SqlRewriteEngine`.
- The support matrix in `docs/sql-support-matrix.md` is the authoritative summary of what is supported, fail-fast by design, or only partially covered.
- The high-level design notes in `docs/architecture.md` are worth reading before changing the rewrite or interception flow.

## Test strategy in this repository

The test suite is layered and each layer catches different regressions:

- `src/test/java/.../core/...` tests cover metadata loading, SQL rewrite behavior, log masking, and decryption units.
- `MybatisEncryptionIntegrationTest` exercises the plugin end-to-end with plain MyBatis + H2, including separate-table synchronization and hydration.
- `MybatisEncryptionAutoConfigurationIntegrationTest` verifies Spring Boot auto-configuration wiring and guards against duplicate interceptor registration.

When changing rewrite logic or separate-table behavior, update both the focused unit tests and the integration tests.