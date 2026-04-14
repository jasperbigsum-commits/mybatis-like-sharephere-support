# 存量迁移指南

[中文](migration-guide.zh-CN.md) | [English](migration-guide.en.md)

## 目标

`mybatis-like-sharephere-support-migration` 用于历史数据迁移与校验，适合把已有明文字段按现有加密规则补齐到目标结构中，同时提供断点恢复和变更风险确认能力。

该模块有几个明确边界：

- 只基于已注册的 MyBatis 实体类生成迁移计划
- 忽略 DTO 和多表拼装元数据，发现这类模型会直接失败
- 不依赖 Spring Boot 自动装配和 MyBatis 运行时拦截链
- 迁移前可要求操作人员确认将要变更的表和字段

## 支持的两种迁移模式

### 1. 同表模式

适用于业务主表中同时存在原字段和加密派生字段的场景。

迁移行为：

- 读取原字段明文
- 写入 `storageColumn`
- 写入 `assistedQueryColumn`
- 写入 `likeQueryColumn`
- 可选执行写后校验

### 2. 独立表模式

适用于密文和派生列位于外部加密表的场景。

迁移行为：

- 读取主表原字段明文
- 计算密文、hash、like
- 依据 hash 优先复用外表已存在记录，未命中则新建
- 将主表原字段更新为外表引用 id
- 可选校验主表引用和外表数据是否一致

## 核心入口

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

## Spring 自动注入用法

如果已经接入 `spring2-starter` 或 `spring3-starter`，更推荐直接注入
`MigrationTaskFactory`，而不是在业务代码里重复调用 `JdbcMigrationTasks.create(...)`
手动拼装迁移所需的基础依赖。

自动装配默认会提供：

- `MigrationTaskFactory`
- `MigrationStateStore`
  默认实现是 `InMemoryMigrationStateStore`
- `MigrationConfirmationPolicy`
  默认实现是 `AllowAllMigrationConfirmationPolicy`

最小使用示例：

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

`builder` 中和字段选择相关的参数现在既可以传加密属性名，也可以直接传主表源列名。
例如 `backupColumn("idCard", "id_card_backup")` 与 `backupColumnByColumn("id_card", "id_card_backup")` 都可用。

如果你希望先构建任务，再决定执行时机：

```java
MigrationTask task = migrationTaskFactory.createForTable(
        "user_account",
        "id",
        builder -> builder.batchSize(1000)
);

MigrationReport report = task.execute();
```

补充说明：

- Spring 应用内优先使用 `MigrationTaskFactory`
- 非 Spring 场景、独立脚本或测试桩场景再使用 `JdbcMigrationTasks.create(...)`
- 如果表没有单一 `id`，可以传入稳定有序的游标列组合，例如 `List.of("tenant_id", "created_at", "biz_no")`

如果你希望把默认内存状态改成文件持久化，或者要求上线前必须确认风险范围，只需覆写对应 Bean：

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

这样自动注入的 `MigrationTaskFactory` 会自动复用这些 Bean，业务代码无需重复传参。

## 风险确认机制

为了避免误改未预期字段，迁移任务支持二次确认。

### 1. 文件确认

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

首次执行时：

- 自动生成确认文件
- 任务直接阻断
- 操作人员审核文件中列出的变更范围

确认文件样例：

```properties
approved=true
entityName=com.example.UserAccount
tableName=user_account
entry.1=UPDATE|user_account|phone_cipher
entry.2=UPDATE|user_account|phone_hash
entry.3=UPDATE|user_account|phone_like
```

如果实际迁移范围和确认文件不一致，任务会失败并要求重新确认。

### 2. 配置白名单确认

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

适用场景：

- 把确认范围固化到配置中心
- 流水线执行前做静态变更范围校验
- 禁止因实体规则变化而悄悄扩大变更面

## 状态文件与断点恢复

状态文件记录：

- `status`
- `totalRows`
- `rangeStart`
- `rangeEnd`
- `lastProcessedId`
- `scannedRows`
- `migratedRows`
- `skippedRows`
- `verifiedRows`

样例：

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

恢复规则：

- 只在批次提交成功后推进断点
- 中途失败不会把未提交批次记为已完成
- 修复问题后直接重跑即可从 `lastProcessedId` 之后继续

## 推荐操作流程

1. 先按实体类配置迁移任务，不要直接按表名手写变更 SQL。
2. 首次上线建议启用 `FileMigrationConfirmationPolicy`，先生成风险清单。
3. 由操作人员核对表名、字段名、操作类型和影响范围。
4. 审核通过后设置 `approved=true`，正式执行迁移。
5. 迁移过程中保留状态文件，不要手动删除断点信息。
6. 如规则或字段范围发生变化，应重新生成并审核确认文件。

## 测试覆盖

当前迁移测试已覆盖：

- 同表迁移
- 独立表迁移
- DTO 元数据拒绝
- 中断后恢复
- 确认文件生成与阻断
- 确认文件批准后执行
- 确认范围不一致时失败
- 配置白名单范围不一致时失败
