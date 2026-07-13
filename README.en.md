# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

`mybatis-like-sharephere-support` is a field-encryption extension for MyBatis and MyBatis-Plus.
It focuses on four runtime concerns:

- encrypted persistence
- assisted equality / LIKE lookup columns
- automatic result decryption
- controller-boundary response masking
- `SafeLog`-based log masking extension

Default algorithms:

- primary encryption: `SM4`
- assisted equality lookup: `SM3`
- default LIKE preprocessing: `normalizedLike`

This default is closer to common domestic commercial-crypto expectations, but it still does not
prove end-to-end compliance by itself.

## Documentation Map

### 1. Quick Start

- [快速使用指南（中文）](docs/quick-start.zh-CN.md)
- [Quick Start (English)](docs/quick-start.en.md)

Read this first if you want the shortest path to:

- encrypted write
- decrypted query result
- masked HTTP response
- minimal configuration, annotation attributes, and schema examples

### 2. Persistence Encryption

- [持久层加密指南（中文）](docs/persistence-encryption-guide.zh-CN.md)
- [Persistence Encryption Guide (English)](docs/persistence-encryption-guide.en.md)
- [SQL Support Matrix](docs/sql-support-matrix.md)

Read this when you need to understand:

- `@EncryptField` column roles
- same-table vs separate-table storage
- DTO inference and `@EncryptResultHint`
- SQL rewrite boundaries
- full `@EncryptField` attribute reference and common field combinations

### 3. Response Masking

- [Sensitive Response Guide](docs/sensitive-response-guide.en.md)

Read this when you need:

- `@SensitiveResponse`
- `@SensitiveField`
- stored masked values vs DTO-level masking
- custom field maskers or reuse of `likeAlgorithm`
- annotation attribute reference, custom masker examples, and strategy selection

### 4. Logsafe Extension

- [日志脱敏指南（中文）](docs/logsafe-guide.zh-CN.md)
- [Logsafe Guide (English)](docs/logsafe-guide.en.md)

Read this when you need:

- `SafeLog.of(obj)` or `SafeLog.kv(key, value)` for explicit safe application logs
- `LogsafeTextMasker` for third-party logs, exception messages, gateway logs, or reporting SDKs
- Spring Boot starter behavior for the Logback terminal filter, MDC trace context, and async MDC propagation
- configuration keys, code examples, boundaries, and troubleshooting guidance

`logsafe` is an independent logging-safety extension. It reuses existing `@SensitiveField` metadata
and registered LIKE masking algorithms without changing SQL rewrite, result decryption, or
controller-boundary response masking. The Spring Boot 2 / 3 starters auto-configure the shared API;
when Logback is detected, they attach a terminal safety-net filter that can be disabled with
`mybatis.encrypt.logsafe.terminal.enabled=false`.

### 5. Historical Migration

- [存量迁移指南（中文）](docs/migration-guide.zh-CN.md)
- [Migration Guide (English)](docs/migration-guide.en.md)
- [迁移生产上线操作手册（中文）](docs/migration-production-runbook.zh-CN.md)
- [Migration Production Runbook (English)](docs/migration-production-runbook.en.md)
- [迁移游标设计指南（中文）](docs/migration-cursor-design.zh-CN.md)
- [Migration Cursor Design Guide (English)](docs/migration-cursor-design.en.md)

Read this when you need migration builder arguments, configuration properties, auto-configured
Bean responsibilities, DDL generation, checkpoint recovery, rollout confirmation, or a production execution checklist.

### 5. Architecture And Maintenance

- [Architecture](docs/architecture.md)
- [Development And Verification](docs/development.md)
- [Project Goals And Principles](docs/projects.md)
- [Release Guide](RELEASE.md)

## What To Read First

If you want to:

- get started quickly: read [Quick Start (English)](docs/quick-start.en.md)
- understand encrypted persistence and query rewrite: read [Persistence Encryption Guide (English)](docs/persistence-encryption-guide.en.md)
- mask controller responses: read [Sensitive Response Guide](docs/sensitive-response-guide.en.md)
- mask logs, exception messages, and third-party log output: read [Logsafe Guide (English)](docs/logsafe-guide.en.md)
- migrate historical plaintext data: read [Migration Guide (English)](docs/migration-guide.en.md)
- execute a migration during a production window: read [Migration Production Runbook (English)](docs/migration-production-runbook.en.md)
- check SQL support boundaries: read [SQL Support Matrix](docs/sql-support-matrix.md)

## Capability Summary

- annotation-based and configuration-based encryption rules
- `INSERT` / `UPDATE` / `DELETE` / `SELECT` rewrite
- same-table and separate-table encrypted storage
- assisted equality lookup via `assistedQueryColumn`
- LIKE lookup via `likeQueryColumn`
- automatic result decryption
- DTO-source inference via `@EncryptResultHint`
- controller-boundary response masking via `@SensitiveResponse`
- three `@SensitiveField` styles:
  - built-in type masking
  - reuse of a registered LIKE algorithm through `likeAlgorithm`
  - custom Spring bean through `masker + options`
- `SafeLog` log masking extension:
  - reuse of `@SensitiveField`
  - reuse of registered LIKE masking algorithms
  - fallback string-level masking for common sensitive log keys
  - `LogsafeTextMasker` as a terminal text-masking SPI
  - automatic appender filter injection when Logback is detected
- migration tasks, DDL generation, resumable checkpoints, and confirmation policies

## Known Boundaries

- encrypted-field `ORDER BY` is intentionally rejected
- encrypted-field range predicates such as `BETWEEN`, `>`, `<` are intentionally rejected
- arbitrary deeply nested SQL is still conservative
- function-wrapped encrypted expressions are not fully covered
- fail-fast is preferred over silent semantic corruption

## Dependency Entry

Spring Boot 3:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For fuller Maven / Gradle / BOM examples, read [Quick Start (English)](docs/quick-start.en.md).

## Local Build

```bash
mvn -Dmaven.repo.local=.m2repo install
```

Spring Boot 3 focused test run:

```bash
mvn -Dmaven.repo.local=.m2repo -pl spring-starter/spring3-starter -am test
```

More maintenance guidance is available in [Development And Verification](docs/development.md).
## Explicit JDBC Facade

If a code path uses `JdbcTemplate` or raw JDBC directly, `EncryptedJdbcExecutor` is available as
an explicit Spring bean. It takes the configured `dataSourceName`, reuses the existing SQL rewrite
and result decryption pipeline, and does not change the normal MyBatis interceptor flow.
