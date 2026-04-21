# Migration Guide

[中文](migration-guide.zh-CN.md) | [English](migration-guide.en.md)

## When to read this

Read this document after your entity rules are already defined and you still need to backfill historical plaintext data.

Suggested reading order:

1. [Quick Start](quick-start.en.md)
2. [Persistence Encryption Guide](persistence-encryption-guide.en.md)
3. this migration guide
4. [Migration Cursor Design Guide](migration-cursor-design.en.md) for cursor strategy

## Goal

`mybatis-like-sharephere-support-migration` is a standalone migration and verification module for historical data. It backfills encrypted storage based on existing entity rules and provides resumable execution plus operator confirmation before mutation.

Design boundaries:

- Plans are created only from registered MyBatis entity metadata
- DTO-style multi-table metadata is rejected
- The module is decoupled from Spring Boot auto-configuration and the MyBatis runtime interceptor chain
- Operators can be forced to confirm the exact tables and columns that will change

## Supported migration modes

### 1. Same-table mode

Used when the main business table stores the plaintext source column and derived encrypted columns in the same row.

Behavior:

- Read plaintext from the source column
- Write `storageColumn`
- Write `assistedQueryColumn`
- Write `likeQueryColumn`
- Optionally verify after write

### 2. Separate-table mode

Used when ciphertext and derived columns live in an external encryption table.

Behavior:

- Read plaintext from the main table source column
- Derive ciphertext, hash, and like values
- Reuse an external row by hash when possible, otherwise insert a new external row
- Update the main table source column to the external reference id
- Optionally verify both the main-table reference and external-table row

## Main entry point

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

Common `EntityMigrationDefinition.builder(...)` arguments:

| Argument | Typical usage | Purpose | Example |
| --- | --- | --- | --- |
| entity type | required | source entity metadata for the migration | `UserAccount.class` |
| cursor column | required | batch progression column | `"id"` / `"record_no"` |
| `batchSize` | strongly recommended | rows processed per batch | `200` / `500` / `1000` |
| `verifyAfterWrite` | recommended | verify rows after mutation | `true` |
| `backupColumn(...)` | common when overwriting source columns | back up original plaintext before overwrite | `"phone_backup"` |
| `excludeFields(...)` | optional | skip some encrypted fields | partial-field migrations |

Practical guidance:

- start with a small batch such as `200` or `500`
- when the main-table source column will be overwritten, prefer an explicit backup column
- for very large tables, generate DDL first, migrate in batches, then enable strict verification

## Spring auto-injection

If you already use `spring2-starter` or `spring3-starter`, prefer injecting
`MigrationTaskFactory` directly instead of repeatedly calling `JdbcMigrationTasks.create(...)`
and assembling the migration infrastructure in business code.

Auto-configuration provides these beans by default:

- `MigrationTaskFactory`
- `MigrationStateStore`
  default implementation: `FileMigrationStateStore`
- `MigrationConfirmationPolicy`
  default implementation: `AllowAllMigrationConfirmationPolicy`
- `GlobalMigrationTaskFactory`
  routes migration tasks by datasource name in multi-datasource applications
- `MigrationSchemaSqlGenerator`
  emits DDL for the current default datasource
- `GlobalMigrationSchemaSqlGeneratorFactory`
  routes DDL generation by datasource name in multi-datasource applications

Bean quick guide:

| Bean | Problem it solves | When to use directly |
| --- | --- | --- |
| `MigrationTaskFactory` | create and run single-datasource migrations | most applications |
| `GlobalMigrationTaskFactory` | route migrations by datasource | multiple JDBC datasources |
| `MigrationSchemaSqlGenerator` | generate DDL for one datasource | DBA review before backfill |
| `GlobalMigrationSchemaSqlGeneratorFactory` | generate DDL for multiple datasources | multi-datasource rollout |
| `MigrationStateStore` | persist checkpoints | resumable execution |
| `MigrationConfirmationPolicy` | require human confirmation | production rollout / controlled change windows |

Minimal example:

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

Field-related builder selectors now accept either the encrypt property name or the main-table source column name.
For example, both `backupColumn("idCard", "id_card_backup")` and
`backupColumnByColumn("id_card", "id_card_backup")` are supported.

If the application has multiple JDBC datasources, prefer injecting `GlobalMigrationTaskFactory`:

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

If the goal is to prepare schema changes first and run the historical backfill later, inject the DDL generator directly:

```java
@Service
public class UserAccountSchemaRunner {

    private final MigrationSchemaSqlGenerator migrationSchemaSqlGenerator;

    public UserAccountSchemaRunner(MigrationSchemaSqlGenerator migrationSchemaSqlGenerator) {
        this.migrationSchemaSqlGenerator = migrationSchemaSqlGenerator;
    }

    public List<String> ddl() {
        return migrationSchemaSqlGenerator.generateForEntity(UserAccount.class);
    }
}
```

For multi-datasource applications:

```java
@Service
public class ArchiveSchemaRunner {

    private final GlobalMigrationSchemaSqlGeneratorFactory ddlFactory;

    public ArchiveSchemaRunner(GlobalMigrationSchemaSqlGeneratorFactory ddlFactory) {
        this.ddlFactory = ddlFactory;
    }

    public Map<String, List<String>> ddl() {
        return ddlFactory.generateAllRegisteredTablesGrouped("archiveDs");
    }
}
```

Migration defaults can also be declared once in configuration:

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

Rule notes:

- `exclude-tables` supports pipe-separated table names or wildcard patterns and fails fast with `TABLE_EXCLUDED`
- `backup-column-templates` apply only when a field overwrites the main-table source column and no explicit `backupColumn(...)` is present
- `default-cursor-columns` are reused automatically by `createForTable("user_account")`, `executeForEntity(UserAccount.class)`, and the one-click entry
- `cursor-rules` lets a few tables override the global default cursor columns by table name
- `checkpoint-directory` is the default persistent checkpoint directory; the starter now stores migration state on disk instead of in memory
- templates support `${table}`, `${property}`, and `${column}`

Migration configuration quick reference:

| Property | Purpose | When to configure |
| --- | --- | --- |
| `default-cursor-columns` | global default cursor columns | most tables share one cursor strategy |
| `cursor-rules` | per-table cursor overrides | a few tables do not use `id` |
| `checkpoint-directory` | checkpoint file location | almost always |
| `batch-size` | default batch size | consistent rollout tuning |
| `verify-after-write` | default post-write verification | correctness-first rollouts |
| `exclude-tables` | prevent accidental migration of protected/system tables | production safety |
| `backup-column-templates` | infer backup column names automatically | many overwrite-style fields |

Cursor constraints:

- cursor columns must be stable, sortable, and must not be updated by the migration itself
- if a cursor column matches one main-table write target, plan creation fails fast with `CURSOR_COLUMN_MUTABLE`
- if a single cursor column is not unique enough, prefer a composite cursor such as `record_no + id`

If you want the simplest one-click migration after rules are registered, call:

```java
List<MigrationReport> reports = migrationTaskFactory.executeAllRegisteredTables();
```

For multi-datasource applications:

```java
List<MigrationReport> reports = globalMigrationTaskFactory.executeAllRegisteredTables("archiveDs");
```

This entry deduplicates by physical table name, so the same table is migrated only once even when it comes from both annotation scanning and external table rules.
If a checkpoint falls behind a committed batch, the writer replays idempotently and skips rows that already match the target state instead of inserting duplicate external rows or overwriting the main table again.
When the same `dataSource + entity/table` task starts concurrently, instances first compete for a checkpoint lock; the losers fail fast with `CHECKPOINT_LOCKED`.
When troubleshooting cursor-related issues, enable `debug` logging. The migration module emits `migration-read-batch`, `migration-load-current-row`, `migration-update-main-row`, and `migration-verify-main-row`, including the SQL, cursor values, and Java types.

## Migration DDL Generation

The migration module also includes a standalone schema DDL generator for producing `CREATE TABLE` / `ALTER TABLE` SQL before the historical data backfill starts.

```java
MigrationSchemaSqlGenerator generator =
        new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, encryptionProperties);

List<String> ddl = generator.generateForEntity(UserAccount.class);
Map<String, List<String>> grouped = generator.generateAllRegisteredTablesGrouped();
```

Default sizing rules:

- `hash` / `assistedQueryColumn`: fixed length `128`
- `likeQueryColumn`: same length as the plaintext source column
- `storageColumn`: `64 + source_length * 4`
- when a separate table is missing, the generator emits `CREATE TABLE`
- when a separate table already exists, it does not auto-change `storageIdColumn`, which avoids mutating an existing external-table primary key unexpectedly

Dialect compatibility:

- the DDL generator reuses the same `sql-dialect` / `datasource-dialects` resolver as the runtime plugin
- `MYSQL` / `OCEANBASE`: emits MySQL-style `add column` / `modify column` / `varchar`
- `DM` / `ORACLE12`: emits `add (...)` / `modify (...)` / `varchar2`
- `CLICKHOUSE`: existing-table add/modify SQL is emitted in ClickHouse style, but auto-create is rejected because ClickHouse table creation still requires manual `ENGINE`, `ORDER BY`, and related clauses

Usage notes:

- the generator only returns SQL; it does not execute it
- review the generated SQL before running the migration task
- advanced objects such as indexes, constraints, comments, and ClickHouse engine clauses must still be added manually
- if your separate-table primary key is not the default string reference id but a numeric or custom strategy, keep the existing table definition instead of blindly replacing it with auto-created DDL

Typical rollout flow:

1. define `@EncryptField` rules on the entity
2. generate DDL for new columns / external tables
3. let DBAs review and apply the schema changes
4. run `MigrationTaskFactory`
5. sample-check query results and API behavior

If you want to build the task first and execute it later:

```java
MigrationTask task = migrationTaskFactory.createForTable(
        "user_account",
        "id",
        builder -> builder.batchSize(1000)
);

MigrationReport report = task.execute();
```

Notes:

- Prefer `MigrationTaskFactory` inside Spring applications
- Use `JdbcMigrationTasks.create(...)` in standalone scripts, non-Spring programs, or focused tests
- If a table has no single `id`, pass an ordered stable cursor set such as `List.of("tenant_id", "created_at", "biz_no")`

Recommended cursor design examples:

- Single-column primary-key tables: use `id`
- Single-column business-key tables: use one immutable and unique key such as `record_no`
- Multi-tenant business tables: prefer composite cursors such as `tenant_id + biz_no` or `tenant_id + created_at + id`
- If one timestamp column alone is not unique enough: do not use only `created_at`; use `created_at + id`

Fields that should not be used as cursors:

- source columns that will be overwritten during migration, such as `phone` or `id_card`
- derived write targets such as `phone_hash`, `phone_like`, or any `storageColumn`
- status, ordering, display-name, or other business fields that can still be updated
- plain string fields that are compared lexicographically while the business meaning is numeric, such as non-zero-padded `order_no`

If you want to change the default checkpoint directory or require mandatory operator confirmation, override the corresponding beans:

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

Then the auto-injected `MigrationTaskFactory` will reuse those beans automatically and business code does not need to pass them again.

## Risk confirmation

To reduce the chance of mutating unintended business fields, the task supports an explicit second confirmation step.

### 1. File-based confirmation

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

On the first run:

- a confirmation file is generated
- execution is blocked
- operators review the listed mutation scope

Sample confirmation file:

```properties
approved=true
entityName=com.example.UserAccount
tableName=user_account
entry.1=UPDATE|user_account|phone_cipher
entry.2=UPDATE|user_account|phone_hash
entry.3=UPDATE|user_account|phone_like
```

If the actual mutation scope differs from the file contents, execution fails and operators must review again.

### 2. Configuration-based allowlist confirmation

```java
MigrationTask task = JdbcMigrationTasks.create(
        dataSource,
        definition,
        metadataRegistry,
        algorithmRegistry,
        encryptionProperties,
        new FileMigrationStateStore(Paths.get("migration-state")),
        ExpectedRiskConfirmationPolicy.of(
                "UPDATE|user_account|phone_cipher",
                "UPDATE|user_account|phone_hash",
                "UPDATE|user_account|phone_like"
        )
);
```

This is useful when:

- the expected mutation scope is stored in configuration management
- pipelines must statically validate the allowed change set
- rule drift must not silently expand the affected columns

## Error types and codes

The migration module now exposes structured exception types so callers can classify failures reliably:

- `MigrationDefinitionException`
  the migration target, metadata, or requested field scope is invalid
- `MigrationFieldSelectorException`
  `includeField` or `backupColumn` did not match any registered encrypted field
- `MigrationConfirmationException`
  confirmation is missing, not approved, stale, or unreadable
- `MigrationCursorException`
  a cursor value is null or the stored checkpoint shape does not match cursor columns
- `MigrationStateStoreException`
  checkpoint state files cannot be loaded, saved, or parsed safely
- `MigrationExecutionException`
  JDBC range refresh or execution fails
- `MigrationVerificationException`
  post-write verification detects inconsistent main-table, external-table, or derived values

All of them extend `MigrationException`, and callers can read `getErrorCode()` for a stable machine-friendly code such as:

- `METADATA_RULE_MISSING`
- `FIELD_SELECTOR_UNRESOLVED`
- `CONFIRMATION_REQUIRED`
- `CONFIRMATION_SCOPE_MISMATCH`
- `CURSOR_CHECKPOINT_INVALID`
- `STATE_STORE_DATA_INVALID`
- `VERIFICATION_VALUE_MISMATCH`

## State files and resume behavior

State files store:

- `cursorColumns.*`
- `cursorJavaTypes.*`
- `status`
- `totalRows`
- `rangeStartValues.*`
- `rangeEndValues.*`
- `lastProcessedCursorValues.*`

When the task comes from `GlobalMigrationTaskFactory`, state and confirmation filenames are also prefixed with the datasource name so similarly named tasks from different datasources do not overwrite each other.
- `scannedRows`
- `migratedRows`
- `skippedRows`
- `verifiedRows`

When the task uses a single cursor column, the state file also writes compatibility aliases for easier manual inspection and backward compatibility:

- `cursorColumn` / `idColumn`
- `cursorJavaType` / `idJavaType`
- `rangeStart` / `rangeEnd`
- `lastProcessedCursor` / `lastProcessedId`

Sample:

```properties
entityName=com.example.UserAccount
tableName=user_account
cursorColumns.0=id
cursorJavaTypes.0=java.lang.Long
cursorColumn=id
idColumn=id
cursorJavaType=java.lang.Long
idJavaType=java.lang.Long
status=RUNNING
totalRows=200000
rangeStartValues.0=1
rangeEndValues.0=200000
lastProcessedCursorValues.0=10500
rangeStart=1
rangeEnd=200000
lastProcessedCursor=10500
lastProcessedId=10500
scannedRows=10500
migratedRows=10480
skippedRows=20
verifiedRows=10480
verificationEnabled=true
```

Resume behavior:

- checkpoints advance only after a batch commits successfully
- failed in-flight batches are not marked as completed
- rerunning the same task resumes from the committed `lastProcessedCursorValues` checkpoint

## Recommended operator workflow

1. Build migration tasks from entity classes instead of writing ad hoc table updates.
2. For first-time rollout, enable `FileMigrationConfirmationPolicy` to generate a mutation manifest.
3. Let operators review table names, column names, operation types, and expected impact.
4. Set `approved=true` and execute the task.
5. Keep state files intact during migration so resume remains available.
6. If entity rules or field scope changes, regenerate and review the confirmation file again.

## Test coverage

The migration test suite currently covers:

- execution-flow tests
  same-table migration, separate-table migration, table-name driven tasks, non-`id` cursors, and registry warm-up
- backup-behavior tests
  plaintext backup before source overwrite by hash, like, or separate-table references
- resume-behavior tests
  restart after failures with both single-column and composite cursors
- plan-factory tests
  DTO rejection, unresolved field selectors, backup conflicts, and missing metadata
- focused unit tests
  cursor codec invariants and state-file compatibility / malformed-state rejection
