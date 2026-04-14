# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

- Chinese migration guide: [docs/migration-guide.zh-CN.md](docs/migration-guide.zh-CN.md)
- English migration guide: [docs/migration-guide.en.md](docs/migration-guide.en.md)

`mybatis-like-sharephere-support` is a lightweight field-encryption extension for MyBatis and MyBatis-Plus. It focuses on transparent field encryption, assisted equality lookup, LIKE lookup support, SQL rewrite, and automatic result decryption with a conservative fail-fast strategy.

## Modules

- `common`: core metadata model, algorithm SPI, SQL rewrite, result decryption, and interceptor logic
- `migration`: standalone Java 8-17 compatible JDBC migration and verification module for historical data
- `spring-starter/spring2-starter`: Spring Boot 2 integration
- `spring-starter/spring3-starter`: Spring Boot 3 integration
- `bom`: version alignment for published modules

## Default algorithms

- Primary encryption: `SM4`
- Assisted equality lookup: `SM3`
- LIKE support: `normalizedLike`

This is closer to common domestic commercial-crypto expectations, but using these algorithms alone does not prove full compliance. Compliance still depends on end-to-end key management, product selection, operational controls, and auditability.

## Current capabilities

- Annotation-based and configuration-based encryption rules
- Automatic MyBatis SQL rewrite for `INSERT`, `UPDATE`, `DELETE`, and `SELECT`
- Same-table encrypted storage and separate-table encrypted storage
- Equality and LIKE rewrite through assisted columns
- Automatic result decryption for registered entity fields
- Algorithm SPI extension points
- Standalone migration and verification tasks for historical data

## Known limits

- No support for encrypted-field `ORDER BY`
- No support for range predicates on encrypted fields such as `BETWEEN`, `>`, `<`
- No promise of correct rewrite for arbitrary deeply nested or highly dynamic SQL
- Conservative failure is preferred over silent corruption

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
  default: `InMemoryMigrationStateStore`
- `MigrationConfirmationPolicy`
  default: `AllowAllMigrationConfirmationPolicy`

If you want file-backed checkpoints or explicit operator confirmation, override those beans and `MigrationTaskFactory` will reuse them automatically:

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

- same-table historical backfill
- separate-table historical migration
- DTO metadata rejection
- resume after interruption
- confirmation template generation and blocking
- approved confirmation execution
- confirmation mismatch failures

Example command used in this repository:

```powershell
& 'D:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd' -q -pl migration -am test
```

## Additional docs

- Chinese migration guide: [docs/migration-guide.zh-CN.md](docs/migration-guide.zh-CN.md)
- English migration guide: [docs/migration-guide.en.md](docs/migration-guide.en.md)
- Architecture notes: [docs/architecture.md](docs/architecture.md)
- SQL support matrix: [docs/sql-support-matrix.md](docs/sql-support-matrix.md)
