# Migration Guide

[中文](migration-guide.zh-CN.md) | [English](migration-guide.en.md)

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

## Spring auto-injection

If you already use `spring2-starter` or `spring3-starter`, prefer injecting
`MigrationTaskFactory` directly instead of repeatedly calling `JdbcMigrationTasks.create(...)`
and assembling the migration infrastructure in business code.

Auto-configuration provides these beans by default:

- `MigrationTaskFactory`
- `MigrationStateStore`
  default implementation: `InMemoryMigrationStateStore`
- `MigrationConfirmationPolicy`
  default implementation: `AllowAllMigrationConfirmationPolicy`

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

If you want file-backed checkpoints or mandatory operator confirmation, override the corresponding beans:

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

## State files and resume behavior

State files store:

- `status`
- `totalRows`
- `rangeStart`
- `rangeEnd`
- `lastProcessedId`
- `scannedRows`
- `migratedRows`
- `skippedRows`
- `verifiedRows`

Sample:

```properties
entityName=com.example.UserAccount
tableName=user_account
idColumn=id
idJavaType=java.lang.Long
status=RUNNING
totalRows=200000
rangeStart=1
rangeEnd=200000
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
- rerunning the same task resumes from `lastProcessedId`

## Recommended operator workflow

1. Build migration tasks from entity classes instead of writing ad hoc table updates.
2. For first-time rollout, enable `FileMigrationConfirmationPolicy` to generate a mutation manifest.
3. Let operators review table names, column names, operation types, and expected impact.
4. Set `approved=true` and execute the task.
5. Keep state files intact during migration so resume remains available.
6. If entity rules or field scope changes, regenerate and review the confirmation file again.

## Test coverage

The migration test suite currently covers:

- same-table migration
- separate-table migration
- DTO metadata rejection
- resume after interruption
- confirmation file generation and blocking
- execution after approval
- failure on confirmation mismatch
- failure on expected-scope mismatch
