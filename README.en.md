# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

- Chinese migration guide: [docs/migration-guide.zh-CN.md](docs/migration-guide.zh-CN.md)
- English migration guide: [docs/migration-guide.en.md](docs/migration-guide.en.md)
- Cursor design guide: [docs/migration-cursor-design.en.md](docs/migration-cursor-design.en.md)
- Release guide: [RELEASE.md](RELEASE.md)

`mybatis-like-sharephere-support` is a lightweight field-encryption extension for MyBatis and MyBatis-Plus. It focuses on transparent field encryption, assisted equality lookup, LIKE lookup support, SQL rewrite, and automatic result decryption with a conservative fail-fast strategy.

## Modules

- `common`: core metadata model, algorithm SPI, SQL rewrite, result decryption, and interceptor logic
- `migration`: standalone Java 8-17 compatible JDBC migration and verification module for historical data
- `spring-starter/spring2-starter`: Spring Boot 2 integration with auto-configuration tests
- `spring-starter/spring3-starter`: Spring Boot 3 integration with auto-configuration tests
- `bom`: version alignment for published modules

## Default algorithms

- Primary encryption: `SM4`
- Assisted equality lookup: `SM3`
- LIKE support: `normalizedLike`
- `idCardMaskLike`: keeps the first 3 and last 3 characters for ID cards
- `phoneMaskLike`: keeps the last 4 characters for mobile and landline numbers
- `bankCardMaskLike`: keeps the last 4 characters for bank cards
- `nameMaskLike`: masks Chinese personal names with common rules and organization names with a heuristic "location prefix + first 2 + last 2" strategy

Four masking-style LIKE preprocessing implementations are also included, with semantics aligned to Apache ShardingSphere, but they are not auto-registered as default beans because each one requires explicit parameters:

- `KeepFirstNLastMLikeQueryAlgorithm`
- `KeepFromXToYLikeQueryAlgorithm`
- `MaskFirstNLastMLikeQueryAlgorithm`
- `MaskFromXToYLikeQueryAlgorithm`

The business-oriented algorithms above are auto-registered as Spring beans, so they can be referenced directly from `like-query-algorithm`, for example:

```yaml
mybatis:
  encrypt:
    tables:
      - table: user_account
        fields:
          - property: phone
            column: phone
            assisted-query-column: phone_hash
            like-query-column: phone_like
            like-query-algorithm: phoneMaskLike
```

If you want one of the parameterized generic cover algorithms instead, declare your own bean and reference it from `like-query-algorithm`, for example:

```java
@Bean("customPhoneMaskLike")
public LikeQueryAlgorithm customPhoneMaskLike() {
    return new KeepFirstNLastMLikeQueryAlgorithm(3, 4);
}
```

This is closer to common domestic commercial-crypto expectations, but using these algorithms alone does not prove full compliance. Compliance still depends on end-to-end key management, product selection, operational controls, and auditability.

## Current capabilities

- Annotation-based and configuration-based encryption rules
- Automatic MyBatis SQL rewrite for `INSERT`, `UPDATE`, `DELETE`, and `SELECT`
- Same-table encrypted storage and separate-table encrypted storage
- Equality and LIKE rewrite through assisted columns
- Automatic result decryption for registered entity fields
- Algorithm SPI extension points
- Standalone migration and verification tasks for historical data

## DTO Result Inference

When the returned DTO does not declare `@EncryptField` / `@EncryptTable`, you can add
`@EncryptResultHint` to the mapper method. The framework preloads the source entity or table rules first,
then reuses ResultMap mappings, column aliases, and auto camel-case mapping to infer which DTO properties need decryption.

```java
@EncryptResultHint(entities = UserRecord.class)
@Select("""
    select u.id, u.name, u.phone
    from user_account u
    where u.id = #{id}
    """)
PlainUserProjectionDto selectPlainUser(@Param("id") Long id);
```

Single-table `select *` / `select t.*` is also supported:

```java
@EncryptResultHint(entities = UserRecord.class)
@Select("select * from user_account where id = #{id}")
PlainUserProjectionDto selectPlainUserByWildcard(@Param("id") Long id);
```

Notes:

- `select *` is recommended only for single-table queries or explicit `t.*`
- for multi-table joins with bare `select *`, inference stays conservative because source columns are ambiguous
- `@EncryptResultHint` only preloads source rules; it does not redefine field encryption metadata

## Known limits

- No support for encrypted-field `ORDER BY`
- No support for range predicates on encrypted fields such as `BETWEEN`, `>`, `<`
- No promise of correct rewrite for arbitrary deeply nested or highly dynamic SQL
- Conservative failure is preferred over silent corruption

## Build And Release

Use this for normal local builds:

```bash
mvn -Dmaven.repo.local=.m2repo install
```

By default, GPG signing and Central publishing plugins are not activated, which keeps local development and normal CI builds lightweight.

Enable the release profile only for real publishing:

```bash
mvn -Dmaven.repo.local=.m2repo -Drelease.publish=true deploy
```

Notes:

- GPG signing and Central Publishing are activated only when `release.publish=true`
- without that flag, `package` / `install` performs only local build and local repository installation
- the examples use the project-local `.m2repo` to keep dependency resolution isolated and reproducible

## Runtime error model

The runtime module now exposes a structured exception model for caller-side handling:

- `EncryptionConfigurationException`
  configuration errors, missing algorithms, invalid separate-table rules, and rewrite failures
- `UnsupportedEncryptedOperationException`
  SQL semantics that clearly hit encrypted fields but are intentionally unsupported

Both extend `EncryptionException` and expose `getErrorCode()` for stable machine-friendly handling, for example:

- `MISSING_CIPHER_ALGORITHM`
- `MISSING_ASSISTED_QUERY_ALGORITHM`
- `INVALID_TABLE_RULE`
- `MISSING_ASSISTED_QUERY_COLUMN`
- `MISSING_LIKE_QUERY_COLUMN`
- `SQL_REWRITE_FAILED`
- `AMBIGUOUS_ENCRYPTED_REFERENCE`
- `INVALID_ENCRYPTED_QUERY_OPERAND`
- `UNSUPPORTED_ENCRYPTED_INSERT`
- `UNSUPPORTED_ENCRYPTED_ORDER_BY`
- `UNSUPPORTED_ENCRYPTED_RANGE`
- `UNSUPPORTED_ENCRYPTED_GROUP_BY`
- `UNSUPPORTED_ENCRYPTED_OPERATION`

## Migration module

The `migration` module is intentionally decoupled from Spring Boot auto-configuration and the MyBatis runtime interceptor chain. It is built for operator-driven backfill, verification, resume, and risk confirmation.

Key behavior:

- Migration plans are built only from registered MyBatis entity metadata
- DTO-style multi-table field models are rejected
- Same-table mode updates `storageColumn`, `assistedQueryColumn`, and `likeQueryColumn`
- Separate-table mode inserts or reuses external encrypted rows by `assistedQueryColumn` hash and rewrites the main-table source column to that hash reference
- Tasks run in primary-key batches and resume from the last committed checkpoint
- File-based progress stores persist table range, processed counts, and the last processed id
- Optional confirmation policies force operators to confirm the exact tables and columns that will be changed
- Structured `MigrationException` subtypes expose machine-friendly `getErrorCode()` values for application-side handling

Minimal example:

```java
MigrationTask task = JdbcMigrationTasks.create(
        dataSource,
        EntityMigrationDefinition.builder(UserAccount.class, "id")
                .batchSize(500)
                .verifyAfterWrite(true)
                .build(),
        metadataRegistry,
        algorithmRegistry,
        encryptionProperties,
        new FileMigrationStateStore(Paths.get("migration-state"))
);

MigrationReport report = task.execute();
```

In multi-datasource setups you can keep one global default dialect and override it by datasource bean name.
`datasource-name-pattern` supports pipe-separated names or wildcard expressions:

```yaml
mybatis:
  encrypt:
    sql-dialect: MYSQL
    datasource-dialects:
      - datasource-name-pattern: "dmPrimary|dmArchive-*"
        sql-dialect: DM
      - datasource-name-pattern: "oracle-report"
        sql-dialect: ORACLE12
```

Notes:

- unmatched datasources fall back to the global `sql-dialect`
- `*` matches any character sequence and `?` matches one character
- the runtime interceptor, separate-table support, and migration module share the same resolver

If the project already uses the Spring Boot starter, prefer injecting `MigrationTaskFactory` directly instead of assembling all migration infrastructure dependencies in business code:

```java
@Service
public class UserAccountMigrationRunner {

    private final MigrationTaskFactory migrationTaskFactory;

    public UserAccountMigrationRunner(MigrationTaskFactory migrationTaskFactory) {
        this.migrationTaskFactory = migrationTaskFactory;
    }

    public MigrationReport migrate() {
        return migrationTaskFactory.executeForEntity(
                UserAccount.class,
                "id",
                builder -> builder
                        .batchSize(500)
                        .verifyAfterWrite(true)
        );
    }
}
```

Field-related selectors in the migration builder now support either the encrypt property name or the main-table
source column name. For example, both `backupColumn("idCard", "id_card_backup")` and
`backupColumnByColumn("id_card", "id_card_backup")` are valid.

Auto-configuration provides these defaults:

- `MigrationTaskFactory`
- `MigrationStateStore`
  default: `FileMigrationStateStore`
- `MigrationConfirmationPolicy`
  default: `AllowAllMigrationConfirmationPolicy`
- `GlobalMigrationTaskFactory`
  routes to a concrete datasource-scoped migration factory in multi-datasource applications

If you want to change the default checkpoint directory or require explicit operator confirmation, override those beans and `MigrationTaskFactory` will reuse them automatically:

```java
@Configuration
public class MigrationSupportConfiguration {

    @Bean
    public MigrationStateStore migrationStateStore() {
        return new FileMigrationStateStore(Paths.get("migration-state"));
    }

    @Bean
    public MigrationConfirmationPolicy migrationConfirmationPolicy() {
        return new FileMigrationConfirmationPolicy(Paths.get("migration-confirmation"));
    }
}
```

If the application has multiple JDBC datasources, inject `GlobalMigrationTaskFactory` directly:

```java
@Service
public class ArchiveMigrationRunner {

    private final GlobalMigrationTaskFactory globalMigrationTaskFactory;

    public ArchiveMigrationRunner(GlobalMigrationTaskFactory globalMigrationTaskFactory) {
        this.globalMigrationTaskFactory = globalMigrationTaskFactory;
    }

    public MigrationReport migrateArchive() {
        return globalMigrationTaskFactory.executeForEntity("archiveDs", UserAccount.class, "id");
    }
}
```

Migration defaults can also be declared once in configuration instead of being repeated in every builder:

```yaml
mybatis:
  encrypt:
    migration:
      default-cursor-columns:
        - id
      cursor-rules:
        - table-pattern: "user_account"
          cursor-columns:
            - record_no
        - table-pattern: "order_*"
          cursor-columns:
            - tenant_id
            - biz_no
      checkpoint-directory: migration-state
      batch-size: 500
      verify-after-write: true
      exclude-tables:
        - "flyway_schema_history|undo_log"
      backup-column-templates:
        - table-pattern: "user_*"
          field-pattern: "idCard|phone"
          template: "${column}_backup"
```

Notes:

- tasks that hit `exclude-tables` fail fast with `TABLE_EXCLUDED`
- `backup-column-templates` apply only when a field overwrites the source plaintext column and no explicit `backupColumn(...)` is provided
- `default-cursor-columns` are reused by `MigrationTaskFactory.createForTable("user_account")`, `executeForEntity(UserAccount.class)`, and the one-click entry
- `cursor-rules` lets specific tables override the global default cursor columns
- `checkpoint-directory` is the default persistent checkpoint directory; the starter now uses file-backed state storage instead of in-memory state
- templates support `${table}`, `${property}`, and `${column}`

Cursor constraints:

- cursor columns must be stable, sortable, and must not be updated by the migration itself
- if a cursor column matches one main-table write target, plan creation fails fast with `CURSOR_COLUMN_MUTABLE`
- if one single cursor column is not unique enough, prefer a composite cursor such as `record_no + id`
- recommended preference order: `id` > immutable business key > composite cursors such as `created_at + id`

If you want the simplest possible full migration after rules are registered, call:

```java
List<MigrationReport> reports = migrationTaskFactory.executeAllRegisteredTables();
```

For multi-datasource applications:

```java
List<MigrationReport> reports = globalMigrationTaskFactory.executeAllRegisteredTables("archiveDs");
```

This entry deduplicates by physical table name, so the same table is migrated only once even if it is discovered from both annotation scanning and external table rules.
Even when a checkpoint falls behind a committed batch, the writer replays idempotently and skips rows that already match the target state instead of inserting duplicate external rows or overwriting the main table again.
When the same `dataSource + entity/table` migration task is started concurrently, instances compete for a checkpoint lock first; losers fail fast with `CHECKPOINT_LOCKED` so the task cannot run twice in parallel.
When troubleshooting cursor-related issues, enable `debug` logging. The migration module emits `migration-read-batch`, `migration-load-current-row`, `migration-update-main-row`, and `migration-verify-main-row`, including the SQL, cursor values, and Java types.

State files now persist general cursor fields such as `cursorColumns.*`, `cursorJavaTypes.*`,
`rangeStartValues.*`, `rangeEndValues.*`, and `lastProcessedCursorValues.*`.
For single-column cursors, compatibility aliases like `idColumn` and `lastProcessedId` are still written.
When a task comes from `GlobalMigrationTaskFactory`, state and confirmation filenames are prefixed with the datasource name to avoid collisions between similarly named tasks.

## Confirmation before mutation

Use `FileMigrationConfirmationPolicy` when you want operators to review the exact mutation scope before execution:

```java
MigrationTask task = JdbcMigrationTasks.create(
        dataSource,
        definition,
        metadataRegistry,
        algorithmRegistry,
        encryptionProperties,
        new FileMigrationStateStore(Paths.get("migration-state")),
        new FileMigrationConfirmationPolicy(Paths.get("migration-confirmation"))
);
```

The first run creates a confirmation file and stops. Operators review the listed `operation|table|column` entries, then set `approved=true` and rerun the task.

You can also use `ExpectedRiskConfirmationPolicy` to inject an explicit allowlist from external configuration. If the actual mutation scope differs from the configured expected scope, execution fails immediately.

## Tests

The migration module test suite covers:

- execution-flow tests for same-table, separate-table, table-name driven, and non-`id` cursor scenarios
- backup-behavior tests for source-column overwrite with plaintext backup
- resume-behavior tests for single-column and composite cursors
- plan-factory tests for selector resolution and metadata validation
- focused unit tests for cursor codec invariants and state-file compatibility

Example command used in this repository:

```powershell
& 'D:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd' -q -pl migration -am test
```

## Additional docs

- Chinese migration guide: [docs/migration-guide.zh-CN.md](docs/migration-guide.zh-CN.md)
- English migration guide: [docs/migration-guide.en.md](docs/migration-guide.en.md)
- Architecture notes: [docs/architecture.md](docs/architecture.md)
- SQL support matrix: [docs/sql-support-matrix.md](docs/sql-support-matrix.md)
