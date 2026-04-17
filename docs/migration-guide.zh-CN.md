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
  默认实现是 `FileMigrationStateStore`
- `MigrationConfirmationPolicy`
  默认实现是 `AllowAllMigrationConfirmationPolicy`
- `GlobalMigrationTaskFactory`
  多数据源场景下按 datasource 名称路由迁移任务

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

如果应用里有多个 JDBC 数据源，推荐注入 `GlobalMigrationTaskFactory`：

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

迁移默认策略也可以走统一配置，减少每个任务重复写 builder：

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

规则说明：

- `exclude-tables` 支持 `|` 分隔多个表名或通配模式，命中后直接抛出 `TABLE_EXCLUDED`
- `backup-column-templates` 仅在字段会覆盖主表原列、且当前任务未显式指定 `backupColumn(...)` 时生效
- `default-cursor-columns` 会被 `createForTable("user_account")`、`executeForEntity(UserAccount.class)` 和一键迁移入口自动复用
- `cursor-rules` 允许少数表按表名覆盖默认游标列
- `checkpoint-directory` 是默认 checkpoint 持久化目录，starter 会直接落盘保存状态，不再使用内存状态
- 模板支持 `${table}`、`${property}`、`${column}`

游标约束：

- 游标列必须稳定、可排序、不会在迁移过程中被更新
- 如果游标列命中了迁移时会写入的主表列，计划构建阶段会直接抛出 `CURSOR_COLUMN_MUTABLE`
- 如果单列游标不能保证唯一性，建议改成复合游标，例如 `record_no + id`

如果想做到最简配置一键迁移，在规则已注册完成后可直接调用：

```java
List<MigrationReport> reports = migrationTaskFactory.executeAllRegisteredTables();
```

多数据源场景：

```java
List<MigrationReport> reports = globalMigrationTaskFactory.executeAllRegisteredTables("archiveDs");
```

该入口按物理表名去重，同一张表即使同时来自注解扫描和外部表规则，也只会迁移一次。
如果 checkpoint 回退到旧批次，writer 会按当前目标态做幂等判断，已完成记录会跳过而不会重复插入外表或再次覆盖主表。
同一 `dataSource + entity/table` 任务并发启动时，会先争抢 checkpoint lock；未拿到锁的实例会直接以 `CHECKPOINT_LOCKED` 失败。
排查游标相关问题时，可打开 `debug` 日志。迁移模块会输出 `migration-read-batch`、`migration-load-current-row`、`migration-update-main-row`、`migration-verify-main-row`，同时带上 SQL、游标值和 Java 类型。

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

推荐游标设计示例：

- 单列主键表：直接使用 `id`
- 单列业务主键表：使用不可变且唯一的业务键，例如 `record_no`
- 多租户业务表：优先使用复合游标，例如 `tenant_id + biz_no` 或 `tenant_id + created_at + id`
- 只有时间列不足以保证唯一时：不要只用 `created_at`，应改成 `created_at + id`

不推荐作为游标的字段：

- 会被迁移覆盖的源列，例如 `phone`、`id_card`
- 派生写入列，例如 `phone_hash`、`phone_like`、`storageColumn`
- 可能被业务更新的状态列、排序列、名称列
- 纯字符串但实际按数值语义递增的字段，例如未补零的 `order_no`

如果你希望修改默认 checkpoint 目录，或者要求上线前必须确认风险范围，只需覆写对应 Bean：

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

## 错误类型与错误码

迁移模块现在提供结构化异常类型，便于业务侧按类别处理失败原因：

- `MigrationDefinitionException`
  迁移目标、元数据或字段范围定义本身无效
- `MigrationFieldSelectorException`
  `includeField` / `backupColumn` 传入的选择器没有命中任何已注册加密字段
- `MigrationConfirmationException`
  风险确认文件缺失、未批准、内容漂移或文件读写失败
- `MigrationCursorException`
  游标值为空、检查点形状与游标列不一致
- `MigrationStateStoreException`
  状态文件无法读写或内容损坏
- `MigrationExecutionException`
  JDBC 范围读取或执行链路失败
- `MigrationVerificationException`
  写后校验发现主表、外表或派生字段结果不一致

所有上述异常都继承自 `MigrationException`，并可通过 `getErrorCode()` 获取结构化错误码，例如：

- `METADATA_RULE_MISSING`
- `FIELD_SELECTOR_UNRESOLVED`
- `CONFIRMATION_REQUIRED`
- `CONFIRMATION_SCOPE_MISMATCH`
- `CURSOR_CHECKPOINT_INVALID`
- `STATE_STORE_DATA_INVALID`
- `VERIFICATION_VALUE_MISMATCH`

## 状态文件与断点恢复

状态文件记录：

- `cursorColumns.*`
- `cursorJavaTypes.*`
- `status`
- `totalRows`
- `rangeStartValues.*`
- `rangeEndValues.*`
- `lastProcessedCursorValues.*`
- `scannedRows`
- `migratedRows`
- `skippedRows`
- `verifiedRows`

当游标只有一列时，状态文件还会额外写入兼容别名，方便人工排查和兼容旧文件：

- `cursorColumn` / `idColumn`
- `cursorJavaType` / `idJavaType`
- `rangeStart` / `rangeEnd`
- `lastProcessedCursor` / `lastProcessedId`

如果任务来自 `GlobalMigrationTaskFactory`，状态文件和确认文件还会自动带上 datasource 名称前缀，避免多数据源下同名任务互相覆盖。

样例：

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

恢复规则：

- 只在批次提交成功后推进断点
- 中途失败不会把未提交批次记为已完成
- 修复问题后直接重跑即可从 `lastProcessedCursorValues` 对应的已提交断点继续

## 推荐操作流程

1. 先按实体类配置迁移任务，不要直接按表名手写变更 SQL。
2. 首次上线建议启用 `FileMigrationConfirmationPolicy`，先生成风险清单。
3. 由操作人员核对表名、字段名、操作类型和影响范围。
4. 审核通过后设置 `approved=true`，正式执行迁移。
5. 迁移过程中保留状态文件，不要手动删除断点信息。
6. 如规则或字段范围发生变化，应重新生成并审核确认文件。

## 测试覆盖

当前迁移测试已覆盖：

- 执行链路测试
  覆盖同表迁移、独立表迁移、按表名创建任务、非 `id` 游标和注解预热场景
- 备份行为测试
  覆盖原字段被 hash / like / 独立表引用覆盖时的备份写入
- 恢复行为测试
  覆盖单列游标与复合游标下的失败恢复
- 计划工厂测试
  覆盖 DTO 元数据拒绝、未知字段选择器、备份列冲突和缺失元数据
- 底层单元测试
  覆盖游标编解码约束与状态文件兼容性/脏数据拦截
