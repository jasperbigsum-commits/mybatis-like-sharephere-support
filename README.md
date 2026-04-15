# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

- 中文迁移指南：[docs/migration-guide.zh-CN.md](docs/migration-guide.zh-CN.md)
- English migration guide: [docs/migration-guide.en.md](docs/migration-guide.en.md)

一个面向 MyBatis / MyBatis-Plus 的数据库字段加密插件，目标是在尽量不侵入业务代码的前提下，为敏感字段提供透明加密、辅助等值查询、LIKE 查询列改写以及结果自动解密能力。

默认算法已切换为国密组合：

- 主加密：`SM4`
- 辅助等值查询：`SM3`
- LIKE 查询辅助列：`normalizedLike`

这更贴近国内商用密码落地场景，但要明确区分：

- 使用国密算法有助于满足等保/商密建设要求
- 仅接入本组件并不等于已经完成等保或商用密码合规
- 真正的合规仍取决于整体系统建设、密钥管理、产品/服务检测认证、制度流程和测评结果

## 项目定位

本项目参考了 ShardingSphere 加密规则的思路，但当前实现更轻量，聚焦单体应用内的 MyBatis 拦截与字段级加密增强。

适用场景：

- 业务实体中存在手机号、身份证号、邮箱、姓名等敏感字段
- 需要数据库落密文，但业务代码仍使用明文字段
- 希望保留等值查询或部分模糊查询能力
- 已经使用 MyBatis 或 MyBatis-Plus，且希望通过 Spring Boot 自动装配接入

## 当前能力

- 支持基于注解和配置两种方式声明加密规则
- 支持 Spring Boot 自动配置注册
- 支持 `INSERT` / `UPDATE` / `DELETE` / `SELECT` 的 SQL 改写
- 支持主加密列写入
- 支持辅助查询列 `assistedQueryColumn` 的等值查询改写
- 支持模糊查询列 `likeQueryColumn` 的 `LIKE` 改写
- 支持查询结果按实体字段自动解密
- 支持算法 SPI 扩展
- 支持调试日志中输出脱敏参数，而非明文或真实密文

默认提供的算法：

- `sm4`：默认主加密算法
- `sm3`：默认辅助等值查询算法
- `normalizedLike`：标准化 LIKE 查询算法
- `aes`：兼容模式主加密算法
- `sha256`：兼容模式辅助等值查询算法

## 明确限制

当前版本是可用骨架，不是完整生产版。以下场景暂未覆盖或只做保守处理：

- 不支持加密字段上的 `ORDER BY`
- 不支持加密字段上的范围查询，如 `BETWEEN`、`>`、`<`
- 不支持复杂子查询、多层嵌套查询的深度改写
- 不支持数据库函数包裹后的复杂字段表达式改写
- 不负责统一接管第三方 SQL 日志框架

对上述不安全或语义不可靠的场景，当前实现倾向于直接失败，而不是“看起来执行成功但结果错误”。

## 运行时错误类型

运行时模块现在也提供统一异常体系，便于业务侧做精确分类：

- `EncryptionConfigurationException`
  用于算法缺失、规则配置无效、独立表依赖缺失、SQL 改写失败等配置/执行前置问题
- `UnsupportedEncryptedOperationException`
  用于已命中加密字段，但当前 SQL 语义明确不支持的场景

两类异常都继承自 `EncryptionException`，并可通过 `getErrorCode()` 获取结构化错误码，例如：

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

## 核心设计

项目主要由 5 个部分组成：

1. 元数据层：加载注解和配置，合并为统一规则模型
2. 算法层：定义主加密、辅助查询、LIKE 查询算法 SPI
3. SQL 改写层：解析并重写 MyBatis 待执行 SQL
4. 插件层：在 MyBatis 生命周期中接入 SQL 改写和结果解密
5. 自动配置层：通过 Spring Boot 自动注册算法、规则中心和拦截器

详细架构见 [docs/architecture.md](docs/architecture.md)。

## 注释规范

为了避免核心安全逻辑只靠代码猜测，本项目要求以下注释规范：

- 所有对外 SPI 接口必须有类级说明，说明职责、输入输出语义和适用范围
- 所有自动配置入口、核心拦截器、规则中心、SQL 改写器、结果解密器必须有类级说明
- 复杂核心方法必须有方法级说明，至少说明执行时机、关键输入和返回结果
- 关键设计决策处可以添加简短实现注释，但禁止用注释重复显而易见的代码动作
- 注释优先解释“为什么这样做”和“边界是什么”，而不是逐行翻译代码
- 当某个能力是保守失败策略时，必须在注释中明确说明失败原因和预期行为

推荐执行标准：

- 接口注释回答“这个扩展点解决什么问题”
- 核心类注释回答“这个类处在执行链路的哪个位置”
- 复杂方法注释回答“输入是什么、会改动什么、失败时为什么报错”
- 行内注释只放在状态同步、顺序依赖、兼容性处理和安全决策处
- 如果一段注释删掉后不影响理解，就说明这段注释不该存在

## 引入方式

### 1. Maven

以 `spring3-starter` 为主：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

如果你使用的是 `-SNAPSHOT` 版本（已发布到 `central.sonatype.com`），需要额外添加快照仓库：

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <name>Central Portal Snapshots</name>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases>
      <enabled>false</enabled>
    </releases>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>
```

如果后续切换到正式发布版本，可移除该快照仓库，直接使用 Maven Central。

如果你希望统一管理版本，推荐引入 BOM：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.jasperbigsum-commits</groupId>
      <artifactId>mybatis-like-sharephere-support-bom</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

然后在 `dependencies` 里不写版本：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
</dependency>
```

### 2. Gradle

```gradle
repositories {
    mavenCentral()
    maven {
        name = "Central Portal Snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
        content {
            includeGroup("io.github.jasperbigsum-commits")
        }
    }
}

dependencies {
    implementation "io.github.jasperbigsum-commits:mybatis-like-sharephere-support-spring3-starter:1.0.0-SNAPSHOT"
}
```

如果使用正式发布版本，可删除 `Central Portal Snapshots` 仓库配置，仅保留 `mavenCentral()`。

### 3. 多模块工程直接依赖

如果你的业务工程和当前项目在同一个多模块仓库中，可以直接依赖：

- `mybatis-like-sharephere-support-spring3-starter`（Spring Boot 3）
- `mybatis-like-sharephere-support-spring2-starter`（Spring Boot 2）

### 4. 接入前提

- Spring Boot 3.x
- MyBatis Spring Boot Starter 3.x
- JDK 17+
- 为生产环境提供安全的 `mybatis.encrypt.default-cipher-key`

## 自定义算法扩展

自定义算法不需要改框架代码，只需要实现对应接口并把实现注册为 Spring Bean。

### 1. 自定义主加密算法

```java
@Component("customCipher")
public class CustomCipherAlgorithm implements CipherAlgorithm {

    @Override
    public String encrypt(String plainText) {
        return plainText;
    }

    @Override
    public String decrypt(String cipherText) {
        return cipherText;
    }
}
```

### 2. 自定义辅助等值查询算法

```java
@Component("customAssist")
public class CustomAssistAlgorithm implements AssistedQueryAlgorithm {

    @Override
    public String transform(String plainText) {
        return plainText;
    }
}
```

### 3. 在规则里引用 Bean 名称

```yaml
mybatis:
  encrypt:
    tables:
      - table: user_account
        fields:
          - column: phone
            cipher-algorithm: customCipher
            assisted-query-column: phone_hash
            assisted-query-algorithm: customAssist
```

框架会自动把同类型 Bean 收拢到算法注册中心，不需要手工维护“算法注册类”。

## 配置方式

### 1. 注解方式

```java
@EncryptTable("user_account")
public class UserEntity {

    @EncryptField(
            column = "phone",
            assistedQueryColumn = "phone_hash",
            likeQueryColumn = "phone_like"
    )
    private String phone;
}
```

### 2. `application.yml` 方式

```yaml
mybatis:
  encrypt:
    enabled: true
    fail-on-missing-rule: true
    log-masked-sql: true
    default-cipher-key: change-me-before-production
    tables:
      - table: user_account
        fields:
          - column: phone
            cipher-algorithm: sm4
            assisted-query-column: phone_hash
            assisted-query-algorithm: sm3
            like-query-column: phone_like
            like-query-algorithm: normalizedLike
    scan-entity-annotations: true
    scan-packages:
      - com.example.domain
    sql-dialect: MYSQL
```

### 3. 兼容模式配置示例

如果需要兼容历史数据，也可以显式切回非国密算法：

```yaml
mybatis:
  encrypt:
    tables:
      - table: user_account
        fields:
          - column: phone
            cipher-algorithm: aes
            assisted-query-column: phone_hash
            assisted-query-algorithm: sha256
```

## 自动装配内容

引入本模块后，在 Spring Boot 环境中会自动注册：

- `AlgorithmRegistry`
- `EncryptMetadataRegistry`
- `SqlRewriteEngine`
- `DatabaseEncryptionInterceptor`

如果开启 `mybatis.encrypt.scan-entity-annotations=true`，框架会扫描自动配置包或 `scan-packages`
中声明了 `@EncryptField` 的实体。此时：

- 可以不写 `@EncryptTable`
- 会优先读取 `MyBatis-Plus @TableName`
- 如果也没有 `@TableName`，则回退到类名转下划线表名

## SQL 方言与日志脱敏

当前插件内部生成 SQL 时，已支持以下驱动对应的标识符转义风格：

- `MYSQL`
- `OCEANBASE`
- `DM`
- `ORACLE12`
- `CLICKHOUSE`

配置方式：

```yaml
mybatis:
  encrypt:
    sql-dialect: DM
```

日志策略：

- 主密文参数打印为 `***`
- 辅助等值查询列如果是哈希值，则日志打印哈希值
- LIKE 辅助列不打印原值
- 改写日志保持 `?` 占位符，不把真实密文直接拼回 SQL 文本

## 快速接入示例

### 1. 定义实体

```java
@EncryptTable("user_account")
public class UserAccount {

    private Long id;

    @EncryptField(
            column = "phone",
            assistedQueryColumn = "phone_hash",
            likeQueryColumn = "phone_like"
    )
    private String phone;
}
```

### 2. 定义表结构

```sql
create table user_account (
    id bigint primary key,
    phone varchar(512) not null,
    phone_hash char(64),
    phone_like varchar(255)
);
```

说明：

- `phone` 存储 `SM4` 密文
- `phone_hash` 存储 `SM3` 结果，用于等值查询
- `phone_like` 存储标准化查询值，用于 `LIKE`

### 3. 正常写 Mapper

```java
UserAccount selectByPhone(@Param("phone") String phone);
```

```xml
<select id="selectByPhone" resultType="com.example.UserAccount">
  select id, phone
  from user_account
  where phone = #{phone}
</select>
```

业务层仍然传明文手机号，插件会自动改写到辅助列查询并在结果返回时解密。

## 独立加密表示例

当不希望把敏感字段落在业务主表中时，可以把字段改为独立加密表存储。

```java
@TableName("user_account")
public class UserAccount {

    private Long id;

    @EncryptField(
            column = "phone",
            storageMode = FieldStorageMode.SEPARATE_TABLE,
            storageTable = "user_phone_encrypt",
            storageColumn = "phone_cipher",
            storageIdColumn = "encrypt_id",
            assistedQueryColumn = "phone_hash",
            likeQueryColumn = "phone_like"
    )
    private String phone;
}
```

对应独立加密表：

```sql
create table user_phone_encrypt (
    encrypt_id bigint primary key,
    phone_cipher varchar(512) not null,
    phone_hash char(64) not null,
    phone_like varchar(255)
);
```

注意：

- 独立表模式下必须定义 `assistedQueryColumn`
- 当前实现按“一个加密字段对应一张独立加密表”设计
- 主表实际保存的是 `assistedQueryColumn` 对应的 hash 值，而不是独立表内部主键
- 写入独立表时会优先复用当前 MyBatis Executor，使用 `Map` 参数执行外表 `INSERT`，这样可以复用同一事务并允许其他 MyBatis 拦截器继续扩展
- 查询结果返回后会按主表中保存的 hash 引用值回填并解密
- 如果使用 MyBatis-Plus 自动生成字段列表，建议让该字段不直接映射业务主表物理列

## 安全与合规说明

根据《商用密码管理条例》，自 2023 年 7 月 1 日起，网络运营者应当按照国家网络安全等级保护制度要求，使用商用密码保护网络安全。我据此做出的工程判断是：默认采用 `SM4/SM3` 比 `AES/SHA-256` 更贴近国内等保和商密建设预期，但“算法切换”只是其中一环，不足以单独证明系统已合规。

如果你的目标是实际通过测评，还需要同步落实：

- 密钥生命周期管理
- 商用密码产品/服务选型与认证要求
- 关键系统的应用安全性评估
- 制度、审计、运维和权限控制

## 典型执行过程

1. 应用启动时加载配置规则和实体注解规则
2. MyBatis 执行 SQL 前，拦截器解析 SQL 并识别命中的加密字段
3. 写入时主字段转成密文，并按规则补充辅助查询列或 LIKE 查询列
4. 查询时将明文条件改写为辅助列或 LIKE 列条件
5. 结果返回后，对声明过规则的实体字段自动解密

## 项目结构

```text
mybatis-like-sharephere-support
├─ bom                              统一版本管理（BOM）
├─ common                           核心能力（算法 SPI、元数据、SQL 改写、解密、拦截器）
│  └─ src/main/java/io/github/jasper/mybatis/encrypt
├─ spring-starter                   Starter 聚合模块
│  ├─ spring3-starter               Spring Boot 3.x 接入模块（主推荐）
│  └─ spring2-starter               Spring Boot 2.x 接入模块
└─ docs                             架构、开发与支持矩阵文档
```

## 测试与开发状态

当前仓库已补充多层测试覆盖，包括：

- 元数据推断与规则注册单元测试
- SQL 改写矩阵单元测试
- 结果自动解密主流程测试
- 纯 MyBatis + H2 集成测试
- Spring Boot 自动装配 + MyBatis + H2 集成测试

本仓库当前没有 `mvnw`，本地开发需自行提供 Maven 环境。

## 后续建议

- 增加 Maven Wrapper，降低接入和测试门槛
- 继续扩展复杂 SQL 场景测试矩阵
- 完善 SQL 日志脱敏策略与扩展点
- 补充生产可用配置示例与兼容性说明

## 存量迁移模块

`mybatis-like-sharephere-support-migration` 是独立的 JDBC 任务模块，专门用于历史数据迁移与校验，不依赖 Spring Boot 自动装配和 MyBatis 插件执行链。

约束与能力：

- 仅基于已注册的 MyBatis 实体类生成迁移计划，拒绝 DTO 和多表拼装元数据
- 同表模式下补齐 `storageColumn`、`assistedQueryColumn`、`likeQueryColumn`
- 独立表模式下按 `assistedQueryColumn` 的 hash 复用或新建外表记录，并把主表原字段回填为该 hash 引用值
- 按主键分页批量执行，支持中断后按最后一次已提交批次继续
- 通过文件状态记录迁移表范围、累计处理量、最后断点和完成状态
- 可选开启写后校验，密文字段按“解密后等于原文”验证，hash/like 按确定性值验证
- 统一使用 `MigrationException` 体系暴露结构化错误码，可通过 `getErrorCode()` 做自动分类处理

最小示例：

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

如果项目已经接入 Spring Boot starter，更推荐直接注入 `MigrationTaskFactory`，避免业务代码重复组装迁移基础设施依赖：

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

迁移 builder 中涉及字段选择的参数现在支持两种写法：加密属性名或主表源列名。
例如可以写 `backupColumn("idCard", "id_card_backup")`，也可以写 `backupColumnByColumn("id_card", "id_card_backup")`。

自动装配默认会提供：

- `MigrationTaskFactory`
- `MigrationStateStore`
  默认是 `InMemoryMigrationStateStore`
- `MigrationConfirmationPolicy`
  默认是 `AllowAllMigrationConfirmationPolicy`

如果你希望断点状态改为文件持久化，或要求上线前必须确认风险范围，只需覆写对应 Bean，`MigrationTaskFactory` 会自动复用：

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

状态文件包含：

- `cursorColumns.*` / `cursorJavaTypes.*`
- `status`
- `totalRows`
- `rangeStartValues.*` / `rangeEndValues.*`
- `lastProcessedCursorValues.*`
- `scannedRows` / `migratedRows` / `skippedRows` / `verifiedRows`

单列游标场景下还会额外保留 `idColumn`、`lastProcessedId` 等兼容别名，方便旧任务平滑迁移。

文件确认模式示例：

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

首次执行时会先生成确认文件并阻断，操作人员确认无误后将 `approved=true` 再重新执行。

确认文件样例：

```properties
approved=true
entityName=com.example.UserAccount
tableName=user_account
entry.1=UPDATE|user_account|phone_cipher
entry.2=UPDATE|user_account|phone_hash
entry.3=UPDATE|user_account|phone_like
```

配置白名单确认模式示例：

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

如果实际将要变更的表名或字段名和配置不一致，任务会直接失败，避免误改其他业务字段。

状态文件样例：

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

推荐操作流程：

1. 用实体类创建迁移任务，先启用 `FileMigrationConfirmationPolicy` 生成确认清单。
2. 由操作人员核对本次会变更的表名、字段名、操作类型，确认后将确认文件中的 `approved` 改为 `true`。
3. 执行迁移任务，按批次推进；任务每次成功提交后都会刷新状态文件。
4. 中断或失败后，不要删除状态文件，修复问题后直接重跑，任务会从 `lastProcessedId` 之后继续。
5. 若字段范围发生变化，确认文件或配置白名单会与真实风险清单不一致并直接阻断，需要重新确认。

迁移模块当前测试已按职责拆分为执行链路、备份行为、恢复行为、确认策略、计划工厂和底层编解码/状态文件单元测试，新增边界时建议按同样维度继续扩展。

## SQL Support Matrix

详细 SQL 支持矩阵见 [docs/sql-support-matrix.md](docs/sql-support-matrix.md)。

当前文档会明确区分：

- 已支持并经过验证的 SQL 形态
- 出于语义安全考虑而失败快的 SQL 形态
- 仍保持保守处理或尚未完整覆盖的高级场景

## Integration Coverage

当前仓库除了 SQL 改写单元测试外，还包含两类端到端验证：

- 纯 MyBatis + H2 集成测试
- Spring Boot 自动装配 + MyBatis + H2 集成测试

这些测试会验证：

- 同表密文字段写入、条件查询与结果解密
- 独立加密表同步、条件查询与回填解密
- Spring Boot 2 / 3 自动装配下拦截器只注册一次，避免重复改写或重复解密
