# 存量迁移指南

[中文](migration-guide.zh-CN.md) | [English](migration-guide.en.md)

## 这份文档适合什么时候看

当你已经准备好了实体规则，但数据库里还存在历史明文数据时，再看这份文档。

建议阅读顺序：

1. 先看 [快速使用指南](quick-start.zh-CN.md)
2. 再看 [持久层加密指南](persistence-encryption-guide.zh-CN.md)
3. 需要历史回填时，看本文
4. 准备进生产窗口时，看 [迁移生产上线操作手册](migration-production-runbook.zh-CN.md)
5. 需要设计游标时，继续看 [迁移游标设计指南](migration-cursor-design.zh-CN.md)

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
- 如配置了 `backupColumn(...)`，先在当前事务内把主表备份列落库
- 依据 hash 优先复用外表已存在记录，未命中则新建
- 最后再将主表原字段更新为外表引用 id
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

`EntityMigrationDefinition.builder(...)` 常用参数速查：

| 参数 | 是否常用 | 作用 | 典型值 |
| --- | --- | --- | --- |
| 实体类型 | 必填 | 指定迁移规则来源实体 | `UserAccount.class` |
| 游标列 | 必填 | 指定批处理推进依据 | `"id"` / `"record_no"` |
| `batchSize` | 强烈推荐 | 每批处理行数 | `200` / `500` / `1000` |
| `verifyAfterWrite` | 推荐 | 写后校验结果是否符合目标态 | `true` |
| `backupColumn(...)` | 覆盖原列时常用 | 迁移前备份原字段 | `"phone_backup"` |
| `excludeFields(...)` | 按需 | 排除部分字段不迁移 | 只迁移部分敏感字段时使用 |

经验建议：

- 首次上线用小批量，例如 `200` 或 `500`
- 主表会被覆盖时优先显式配置备份列
- 大表先生成 DDL、再分批迁移、最后再打开严格校验

## 先校验后迁移：推荐操作规范

这里说的“先校验”有两个不同层次，必须区分清楚：

- 写前校验
  指真正开始写库前，对规则、DDL、变更范围、备份策略、断点状态做预检查
- 写后校验
  指任务执行时的 `verifyAfterWrite(true)`，只在本批数据已经写入后校验目标态是否正确

不要把 `verifyAfterWrite(true)` 误当成“只校验不迁移”开关。它不是 dry-run，也不会阻止写入。

### 推荐的标准流程

1. 先构建任务定义，不要立即执行
2. 生成 DDL 并让 DBA 审核、执行
3. 启用风险确认策略，先确认变更范围
4. 做写前数据检查，确认源字段仍可恢复原始明文
5. 小批量灰度执行，保留 `verifyAfterWrite(true)`
6. 观察成功率、错误码、checkpoint 状态后再放大批量

### 写前校验清单

#### 1. 规则与表结构校验

上线前至少确认以下几点：

- 实体规则已经注册，且来源是实体而不是 DTO
- 游标列稳定、可排序、不会在迁移过程中被覆盖
- `MigrationSchemaSqlGenerator` 生成的补列 SQL 已执行完成
- 独立表模式下，外表主键、hash 列、cipher 列、like/masked 列都与规则一致

推荐做法：

- 先调用 `MigrationSchemaSqlGenerator.generateForEntity(...)`
- 审核输出 SQL 后再执行正式迁移
- 不要跳过 DDL 审核直接让任务边报错边补结构

#### 2. 变更范围校验

推荐在生产环境强制启用：

- `FileMigrationConfirmationPolicy`
- 或 `ExpectedRiskConfirmationPolicy`

这样可以在真正写库前，先把会变更的表和列固定下来，避免因为规则漂移导致误扩散。

重点核对：

- 是否只覆盖预期表
- 是否只包含预期敏感字段
- 是否误把测试字段、历史废弃字段也纳入迁移
- 是否有会覆盖源列但未配置备份的字段

#### 3. 明文可恢复性校验

这是最容易误操作的部分。

对于任何会覆盖源列的字段，例如：

- 同表覆盖 `sourceColumn -> hash / like / cipher`
- 独立表模式下主表源列被改写为引用 hash

都应在迁移前确认：

- 当前源列里仍然是原始明文
- 或者已经配置了 `backupColumn(...)`
- 或者已通过 `backup-column-templates` 自动生成备份列

如果某字段曾经执行过半次迁移，已经把源列改写了，但备份列又不存在，那么后续重跑时可能直接触发 `PLAINTEXT_UNRECOVERABLE`。这不是框架“太严格”，而是系统已经无法安全判断当前值到底是明文还是派生值。

现场处理原则：

1. 不要强行重跑
2. 先从业务备份、历史表、审计日志或人工导出中恢复原始明文
3. 再补齐备份列或修正外表残缺数据
4. 确认后重新执行迁移

#### 4. checkpoint 与恢复状态校验

正式执行前请检查：

- 是否存在旧的状态文件
- 状态文件是否对应当前实体、表、游标和数据源
- 是否刚修改过迁移规则、字段范围、游标列，但仍沿用旧 checkpoint

规范建议：

- 同一个迁移任务不要并发启动多个实例
- 不要手动编辑 `lastProcessedCursorValues.*`
- 如果迁移定义已经变化，应重新生成确认文件并重新评估是否复用旧 checkpoint

### 推荐执行姿势

#### 阶段一：只做预检查，不执行迁移

```java
MigrationTask task = migrationTaskFactory.createForEntity(
        UserAccount.class,
        "id",
        builder -> builder
                .batchSize(200)
                .verifyAfterWrite(true)
                .backupColumn("phone", "phone_backup")
);

List<String> ddl = migrationSchemaSqlGenerator.generateForEntity(UserAccount.class);
```

这一阶段建议完成：

- 看 DDL
- 看风险确认文件
- 看备份列是否齐全
- 看源表里待迁移字段是否仍是原始明文

#### 阶段二：小批量灰度

推荐参数：

- `batchSize=50~200`
- `verifyAfterWrite=true`

目的：

- 先验证 SQL、索引、锁冲突、外表写入、校验链路是否正常
- 先观察是否会出现 `CHECKPOINT_LOCKED`、`VERIFICATION_VALUE_MISMATCH`、`PLAINTEXT_UNRECOVERABLE`

#### 阶段三：放量执行

只有在灰度批确认稳定后，再逐步扩大到 `500`、`1000` 或更大批量。

不要做的事：

- 直接拿全量大批次首跑
- 迁移失败后不分析错误码就删 checkpoint 重跑
- 源列已被覆盖但没有备份时继续尝试补偿
- 未做 DDL 审核就让生产任务自动探路

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
- `MigrationSchemaSqlGenerator`
  单数据源场景下输出 DDL
- `GlobalMigrationSchemaSqlGeneratorFactory`
  多数据源场景下按 datasource 名称路由 DDL 生成

这些 Bean 各自解决什么问题：

| Bean | 解决的问题 | 什么时候直接用 |
| --- | --- | --- |
| `MigrationTaskFactory` | 单数据源迁移任务创建与执行 | 大多数单库应用 |
| `GlobalMigrationTaskFactory` | 多数据源路由迁移 | 有多个业务数据源 |
| `MigrationSchemaSqlGenerator` | 单数据源 DDL 生成 | 先让 DBA 审核建表 / 补列 SQL |
| `GlobalMigrationSchemaSqlGeneratorFactory` | 多数据源 DDL 生成 | 多库分批生成 DDL |
| `MigrationStateStore` | checkpoint 持久化 | 要断点恢复时一定要关注 |
| `MigrationConfirmationPolicy` | 风险确认 | 上线前要人工确认变更范围 |

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

如果你的目标是先补齐表结构，再执行历史数据回填，也可以直接注入 DDL 生成器：

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

多数据源场景对应：

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

迁移配置项速查：

| 配置项 | 作用 | 什么时候配 |
| --- | --- | --- |
| `default-cursor-columns` | 全局默认游标列 | 大多数表都按同一游标推进 |
| `cursor-rules` | 按表覆盖游标列 | 少数表不用 `id` 做游标 |
| `checkpoint-directory` | checkpoint 文件目录 | 几乎总是要配，便于恢复 |
| `batch-size` | 默认批大小 | 想统一所有任务的吞吐策略 |
| `verify-after-write` | 默认写后校验 | 对结果正确性要求高 |
| `exclude-tables` | 排除敏感或系统表 | 防止误迁系统表 |
| `backup-column-templates` | 自动推导备份列名 | 字段多，不想逐个写 `backupColumn(...)` |

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

## 迁移 DDL 生成

迁移模块还提供了独立的 schema DDL 生成器，用于在数据回填前先批量生成 `CREATE TABLE` / `ALTER TABLE` 语句。

```java
MigrationSchemaSqlGenerator generator =
        new MigrationSchemaSqlGenerator(dataSource, metadataRegistry, encryptionProperties);

List<String> ddl = generator.generateForEntity(UserAccount.class);
Map<String, List<String>> grouped = generator.generateAllRegisteredTablesGrouped();
```

默认长度策略：

- `hash` / `assistedQueryColumn` 固定长度 `128`
- `likeQueryColumn` 跟主表源字段等长
- `storageColumn` 默认长度为 `64 + 原字段长度 * 4`
- 独立表不存在时会输出 `CREATE TABLE`
- 独立表已存在时不会自动修改 `storageIdColumn`，避免误改外表主键

方言兼容性说明：

- DDL 生成器和运行时插件共用同一套 `sql-dialect` / `datasource-dialects` 解析逻辑
- `MYSQL` / `OCEANBASE`：输出 MySQL 风格 `add column` / `modify column` / `varchar`
- `DM` / `ORACLE12`：输出 `add (...)` / `modify (...)` / `varchar2`
- `CLICKHOUSE`：已存在表的补列 / 扩容 SQL 会按 ClickHouse 风格输出；如果需要自动建表，生成器会直接拒绝，因为 ClickHouse 建表还必须手工补 `ENGINE`、`ORDER BY` 等语义

使用建议：

- 生成器只返回 SQL，不会自动执行
- 推荐先生成并人工审核，再执行迁移任务
- 索引、约束、注释、ClickHouse engine 子句等复杂对象仍需手工补充
- 如果你的独立表主键不是默认字符串引用，而是数值或其他自定义规则，建议沿用现有外表定义，不要直接覆盖为自动建表 SQL

典型流程：

1. 先写好实体上的 `@EncryptField`
2. 调用 DDL 生成器生成补列 / 建表 SQL
3. 让 DBA 审核并执行
4. 再运行 `MigrationTaskFactory`
5. 最后抽样校验查询和接口返回

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

文件生成规则补充说明：

- `FileMigrationConfirmationPolicy` 是按“单个迁移任务”生成确认文件，不是按整个策略实例生成一个总文件
- 如果一次批量执行多张表，会生成多份确认文件，通常一张主表对应一份
- 如果任务来自多数据源工厂，确认文件名会带上 `dataSourceName` 前缀，避免同名任务互相覆盖
- 如果字段使用独立表模式，仍然只生成当前主表任务的一份文件，但文件中的 `entry.*` 会同时包含主表更新项和外表插入项

可以这样理解：

- 同表模式：一张主表任务，一份确认文件
- 独立表模式：一张主表任务，一份确认文件，文件内同时列出主表和外表风险项
- 多表批量迁移：多张主表任务，多份确认文件

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

如果你的目标是“在配置里一次维护多张表确认范围，而不是落多个文件”，应优先使用
`ExpectedRiskConfirmationPolicy.builder()`，而不是 `FileMigrationConfirmationPolicy`。

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

默认的 `ExpectedRiskConfirmationPolicy.of(...)` 仍然适合单个迁移任务的精确确认。
如果一个应用会复用同一个 policy 连续执行多张表，推荐改用 builder：

```java
ExpectedRiskConfirmationPolicy policy = ExpectedRiskConfirmationPolicy.builder()
        .expectEntityTable(
                "com.example.UserAccount",
                "user_account",
                "UPDATE|user_account|phone_cipher",
                "UPDATE|user_account|phone_hash",
                "UPDATE|user_account|phone_like"
        )
        .expectEntityTable(
                "com.example.UserArchive",
                "user_archive",
                "UPDATE|user_archive|archive_phone_cipher",
                "UPDATE|user_archive|archive_phone_hash",
                "UPDATE|user_archive|archive_phone_like"
        )
        .build();
```

builder 方式的匹配顺序：

- `dataSource + entity + table`
- `entity + table`
- `dataSource + table`
- `table`

也就是说，既可以做精确任务级配置，也可以做按表的批量配置。

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
- `STATE_INCOMPATIBLE`
- `PLAINTEXT_UNRECOVERABLE`
- `BACKUP_VALUE_INCONSISTENT`
- `VERIFICATION_VALUE_MISMATCH`

### `PLAINTEXT_UNRECOVERABLE` 代表什么

这个错误码用于“已经能证明原字段明文丢失，系统不能再安全补偿”的场景。

典型触发条件：

- 字段采用覆盖式迁移，且没有配置 `backupColumn(...)`
- 上一次迁移已经把主表源列改写成 hash / 引用值 / 其他派生值
- 但同表派生列或独立表记录只写成功了一部分，当前状态不是完整目标态

为什么此时必须失败：

- 迁移器已经无法从当前行恢复原始明文
- 如果继续把“已改写值”当成明文再次派生，会生成错误密文或错误 like/hash
- 这类问题不能靠自动重试修复，必须先人工恢复原始明文或回填备份

推荐处理方式：

1. 从业务备份、审计表或旧备份列恢复原始明文
2. 校正不完整的同表派生列或独立表记录
3. 对未来所有会覆盖源列的字段补充 `backupColumn(...)` 或统一 `backup-column-templates`

### `BACKUP_VALUE_INCONSISTENT` 代表什么

这个错误码用于“备份列已存在，但它既不等于当前源列明文，也无法解释当前源列为什么已经处于合法覆盖目标态”的场景。

典型触发条件：

- 已配置 `backupColumn(...)`
- 备份列里有值
- 主表源列里也有值
- 但源列值既不等于备份明文，也不等于基于该备份明文推导出的 hash / like / 密文 / 独立表引用 hash

为什么此时必须失败：

- 迁移器无法再安全判断到底应该信任源列还是备份列
- 如果继续无条件信任备份列，可能把错误备份扩散成新的密文和派生列
- 如果继续无条件信任源列，又会破坏“备份优先恢复”的断点续跑语义

推荐处理方式：

1. 人工核对源列与备份列哪个才是真实明文
2. 修正错误值后重新执行迁移
3. 不要直接删除 checkpoint 规避该错误

### `STATE_INCOMPATIBLE` 代表什么

这个错误码用于“已经存在 checkpoint，但它不属于当前迁移任务”的场景。迁移器会在写库和保存新状态之前失败，旧状态文件不会被覆盖。

典型触发条件：

- 修改了实体/表、字段范围、游标列、备份列、`verifyAfterWrite` 等会影响计划签名的配置
- 更换了数据源、连接 URL、数据库用户，导致数据源指纹不同
- 同一目录里混用了实体入口和表名入口，产生了不同任务标识

处理方式：

1. 如果目标是继续上一次迁移，恢复与上次完全一致的配置后重跑
2. 如果目标是启动一个新的迁移任务，先归档或移动旧 checkpoint，再执行新任务
3. 不要手工编辑 `planSignature`、`dataSourceFingerprint` 或游标值来绕过校验

## 状态文件与断点恢复

状态文件记录：

- `dataSourceName`
- `dataSourceFingerprint`
- `planSignature`
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
- 已存在 checkpoint 但 `planSignature` 或 `dataSourceFingerprint` 与当前任务不一致时，会以 `STATE_INCOMPATIBLE` 失败且不会覆盖旧状态文件
- 如果发现源列已经被部分迁移改写、且当前任务又无法从备份恢复明文，任务会直接以 `PLAINTEXT_UNRECOVERABLE` 失败，而不是继续错误补偿

### 中断后如何继续迁移

迁移中断后不要手工改状态文件。推荐做法是：

1. 保留原来的 `MigrationStateStore` 目录或存储
2. 保持同一个实体/表、游标列、字段范围、数据源和确认策略
3. 修复导致失败的问题
4. 重新执行同一个迁移任务

示例：

```java
MigrationTask task = migrationTaskFactory.createForTable(
        "user_account",
        "id",
        builder -> builder
                .batchSize(500)
                .verifyAfterWrite(true)
);

MigrationReport report = task.execute();
```

如果上一次失败前已有批次提交成功，下一次会从 `lastProcessedCursorValues.*` 后继续读取。
如果上一次失败发生在批次事务内部，该批次会回滚，下次会重新处理该批次。

不要为了“继续执行”删除 checkpoint。删除状态文件等价于从头扫描，只有在确认目标态幂等、且明确需要重建状态时才应这么做。

### 一键批量迁移二次执行

`executeAllRegisteredTables()` 和 `globalMigrationTaskFactory.executeAllRegisteredTables(...)`
也是逐表创建迁移任务，每张表各自使用自己的 checkpoint。

二次执行时预期行为：

- 如果状态文件显示 `COMPLETED`，且数据库当前记录数、rangeStart、rangeEnd、lastProcessedCursor 与 checkpoint 一致，任务会直接返回已完成报告，不会重复改写已完成字段，也不会逐行扫描全表
- 如果状态文件显示 `COMPLETED`，但数据库记录数或游标范围发生变化，任务会重建进度并按幂等规则补偿
- 如果数据库字段被原地回滚或派生列缺失，但记录数和游标范围没有变化，二次执行会信任 `COMPLETED` checkpoint；这类修复应先归档或移动旧 checkpoint，再按修复流程重跑
- 如果某字段源列已被覆盖、派生列又不完整，但没有备份列可恢复明文，会失败并返回 `PLAINTEXT_UNRECOVERABLE`

覆盖式字段使用随机 IV 密文时，迁移判断以“密文能否解密回原始明文”为准，不会因为同一明文二次加密得到不同密文就误判为必须重迁。

### 如何从备份字段恢复

迁移模块没有单独的“从备份恢复 API”。只要迁移计划中配置了 `backupColumn(...)`，恢复是自动发生的。

自动恢复条件：

- 字段是覆盖式迁移，例如源列被 hash、like、cipher 或独立表引用值覆盖
- `backupColumn(...)` 或 `backup-column-templates` 已配置
- 备份列里仍然保存原始明文
- 当前目标态不完整，需要补偿写入

示例：

```java
MigrationReport report = migrationTaskFactory.executeForEntity(
        UserAccount.class,
        "id",
        builder -> builder
                .backupColumn("phone", "phone_backup")
                .verifyAfterWrite(true)
);
```

如果当前行的 `phone` 已经被改写成 hash，但 `phone_backup` 仍然保存原始手机号，迁移器会优先使用 `phone_backup` 重新生成缺失的密文、like、hash 或独立表记录。

如果没有备份列，且源列已经不是原始明文，框架不会猜测或二次派生，会直接抛出 `PLAINTEXT_UNRECOVERABLE`。

## 推荐操作流程

1. 先按实体类配置迁移任务，不要直接按表名手写变更 SQL。
2. 首次上线建议启用 `FileMigrationConfirmationPolicy`，先生成风险清单。
3. 由操作人员核对表名、字段名、操作类型和影响范围。
4. 审核通过后设置 `approved=true`，正式执行迁移。
5. 迁移过程中保留状态文件，不要手动删除断点信息。
6. 如规则或字段范围发生变化，应重新生成并审核确认文件。

## 生产上线操作手册

真正进入生产变更窗口时，建议直接切换到独立手册执行：

- [迁移生产上线操作手册](migration-production-runbook.zh-CN.md)

该手册包含：

- 研发、DBA、运维三方角色分工
- 执行前 checklist
- 首次灰度与放量规则
- 失败后处置规范
- 明确禁止的高风险操作

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
