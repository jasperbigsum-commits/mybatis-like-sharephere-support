# 数据库加密插件架构设计

## 这份文档解决什么问题

这份文档回答的是“系统为什么这么分层、边界在哪里、执行链路如何串起来”。

如果你的目标是：

- 先快速接入：看 [快速使用指南](quick-start.zh-CN.md)
- 理解字段和持久层规则：看 [持久层加密指南](persistence-encryption-guide.zh-CN.md)
- 理解 controller 脱敏：看 [脱敏响应指南](sensitive-response-guide.zh-CN.md)
- 理解历史数据迁移：看 [存量迁移指南](migration-guide.zh-CN.md)

## 设计目标

1. 不侵入业务层 Mapper/Service 代码，对 MyBatis 或 MyBatis-Plus 生成的 SQL 做透明增强。
2. 支持字段加密、结果解密、等值查询、LIKE 查询、插入、更新、删除。
3. 提供类似 ShardingSphere 的规则配置方式，同时支持实体注解声明。
4. 对风险操作保持保守策略：对未声明规则的字段不做推断，对不支持的排序和范围查询直接报错。

## 核心架构

### 1. 元数据层

- `@EncryptField` / `@EncryptTable`：实体注解声明加密规则。
- `DatabaseEncryptionProperties`：支持 `mybatis.encrypt.tables[]` / `fields[]` 列表式配置，并与注解规则统一收敛。
- `EncryptMetadataRegistry`：合并注解规则和配置规则，按表名和实体类型缓存。
- `EncryptEntityScanner`：可在启动期自动扫描带 `@EncryptField` 的实体，无需强制声明 `@EncryptTable`。

### 2. 算法 SPI

- `CipherAlgorithm`：负责密文写入与结果解密。
- `AssistedQueryAlgorithm`：负责等值查询的辅助列计算，适合哈希检索。
- `LikeQueryAlgorithm`：负责模糊查询列生成。
- `AlgorithmRegistry`：从 Spring Bean 容器按名称装配算法，允许用户扩展。
- `SensitiveFieldMasker`：负责 controller 边界输出 DTO 的自定义脱敏。

### 3. SQL 改写层

- `SqlRewriteEngine` 基于 JSqlParser 解析 `INSERT/UPDATE/DELETE/SELECT`。
- 对主加密列保持原列名，仅替换参数值为密文。
- 对 `assistedQueryColumn` / `likeQueryColumn` 自动补充插入列、更新列并改写 WHERE 条件。
- 对独立加密表字段，等值、非等值、`IN`、`IS NULL`、`IS NOT NULL` 以及无 `likeQueryColumn` 时退化出的等值 `LIKE`，都优先改写为主表逻辑列上的 hash/ref 直接比较；只有真正需要模糊匹配的 `LIKE` 才保留 `EXISTS` 子查询语义，主表逻辑列写入 `assistedQueryColumn` 对应的 hash 引用值。
- 对插件内部新生成的标识符，按配置的 `sqlDialect` 输出对应转义风格，当前支持 MySQL、OceanBase、达梦、Oracle12、ClickHouse。
- 对排序、范围比较等不可安全支持的操作主动失败，避免出现“看似成功但结果错误”的情况。

### 4. MyBatis 插件层

- `DatabaseEncryptionInterceptor`
  - `Executor.update`：先执行可选的写前参数预处理，再完成独立表引用准备、SQL 改写与独立加密表同步。
  - `ResultSetHandler.handleResultSets`：对结果实体进行字段解密。
  - `@SkipSqlRewrite`：标注在 Mapper 方法上可跳过该方法的 SQL 重写与结果解密，适用于不涉及加密字段的查询/更新。
- `ResultDecryptor`：优先依据查询结果计划与元数据只处理命中的返回对象，避免对无关入参或未映射对象误解密。
- `SeparateTableEncryptionManager`：处理独立加密表的回填和写后同步。

写前参数预处理补充：

- `WriteParameterPreprocessor`：在主业务 `INSERT/UPDATE` 的 `BoundSql` 生成前原地调整参数对象。
- Spring starter 会自动聚合所有 `WriteParameterPreprocessor` Bean。
- 在检测到 JEECG 的 `org.jeecg.config.mybatis.MybatisInterceptor` /
  `MybatisSensitiveUpdateInterceptor` Bean 时，starter 会以内置反射适配方式预执行其原有
  `Executor.update(...)` 参数填充逻辑。
- 如果业务侧已经有其他自定义 MyBatis 拦截器会在 `Executor.update(...)` 阶段原地修改参数对象，
  仍然可以显式提供对应的 `WriteParameterPreprocessor`，让这些变更在主表 `BoundSql`
  定型前生效。
- 这些兼容都只影响主业务写入参数预处理，不重排 MyBatis 插件顺序，也不会对库内部 managed
  statement 再次触发这层预处理。

边界补充：

- 解密边界与 MyBatis 结果装配边界一致，只处理已经映射到返回对象上的属性。
- 对 `resultType` 无注解 DTO、列别名、派生表投影等场景，优先依赖 `QueryResultPlanFactory` 的保守推断与 `@EncryptResultHint` 预热来源元数据。
- 对复杂表达式列、不可唯一判定来源的多表投影、业务代码二次查询覆盖字段等情况，不承诺自动纠偏，应由业务显式建模或直接返回脱敏列。

### 5. 控制器边界脱敏层

- `SensitiveResponseContextInterceptor`
  - 在命中 `@SensitiveResponse` 的 controller 方法前打开请求级别 `SensitiveDataContext`
- `SensitiveResponseTriggerAspect`
  - 对 service、装配器等 Spring Bean 方法上的 `@SensitiveResponseTrigger` 消费当前线程里已经打开的 `SensitiveDataContext`
  - 没有 controller 先打开上下文时，不做任何操作
- `SensitiveResponseBodyAdvice`
  - 在响应写回前触发 `SensitiveDataMasker`
- `SensitiveDataMasker`
  - 优先使用数据库 `maskedColumn` 的存储态脱敏值替换已解密字段
  - 再按 `maskedAlgorithm` / `@SensitiveField` 作为回退策略
  - `@SensitiveField` 支持内置规则、复用 `LikeQueryAlgorithm` 和自定义 `SensitiveFieldMasker`
- `JdbcStoredSensitiveValueResolver`
  - 按数据源、表、规则批量查询 `maskedColumn`

边界补充：

- 脱敏是 controller 边界的最终输出决策，不回写数据库，也不反向影响 SQL 改写与结果解密。
- `RECORDED_ONLY` 是标准查询接口的首选策略，因为它只处理真正被解密过的对象引用。
- `ANNOTATED_FIELDS` / `RECORDED_THEN_ANNOTATED` 仅作为手工组装 DTO 的补充，不替代 MyBatis 结果映射。
- 同一请求线程内允许多个嵌套 scope；异步 continuation 默认不传播当前 scope。

详细设计见 [sensitive-response-guide.zh-CN.md](sensitive-response-guide.zh-CN.md)。

### 6. Spring Boot 自动配置

- `MybatisEncryptionAutoConfiguration`
  - 注册默认算法
  - 注册规则中心
  - 注册 `DatabaseEncryptionInterceptor` 并交由 MyBatis 自动装配链路接入
- `LogsafeAutoConfiguration`
  - 注册 `LogsafeMasker`
  - 注册 `SafeLog`
  - 将 Spring 容器中的 `LogsafeMasker` 安装到 `SafeLog` 静态门面，业务代码可直接调用 `SafeLog.of(...)`
  - 注册 `LogsafeTextMasker` 作为日志输出端文本兜底 SPI，可由自定义日志框架适配器或异常上报适配器显式调用
  - 复用 `AlgorithmRegistry` 与 `@SensitiveField` 元数据做日志脱敏副本输出
  - 当前先只在 `spring3-starter` 自动装配，不改变 controller 边界响应脱敏链路
- `LogsafeLogbackAutoConfiguration`
  - 检测到 Logback 时注册 `LogsafeLogbackAppenderInstaller`
  - 在 Spring 单例初始化完成后遍历当前 Logback appenders，挂载命名末端 filter
  - filter 在 layout/encoder 输出前调用 `LogsafeTextMasker` 处理格式化消息、结构化 key/value 和异常消息
  - 只修改当前日志事件的输出态，不修改业务对象、原始异常对象或用户 appender 配置文件
  - 可通过 `mybatis.encrypt.logsafe.terminal.enabled=false` 关闭自动末端注入
- `SensitiveResponseAutoConfiguration`
  - 注册 `SensitiveDataMasker`
  - 注册 `SensitiveResponseContextInterceptor`
  - 注册 `SensitiveResponseBodyAdvice`
  - 在存在 `DataSource` 时注册 `JdbcStoredSensitiveValueResolver`
  - 自动收集 `SensitiveFieldMasker` Bean 供 `@SensitiveField(masker=...)` 使用
- `UserDatabaseEncryptionProperties`
  - starter 只负责外部配置绑定
  - 具体规则模型直接复用 `common` 模块中的 `DatabaseEncryptionProperties`，避免 Spring 2/3 各维护一套重复配置结构

## 执行链路

1. 应用启动时读取 `application.yml` 和实体注解，注册加密规则。
2. SQL 执行前，插件解析 SQL 并定位命中的表与字段。
3. 对主业务 `INSERT/UPDATE`，若存在写前参数预处理器，先原地补齐审计字段、租户字段或敏感更新保护后的参数值。
4. 写操作时主字段写入密文，同时追加辅助查询列或模糊查询列。
5. 查询条件遇到等值或 LIKE 时，改写到对应辅助列，并对参数做算法转换。
6. 查询结果返回后，按实体字段规则解密成业务可读值。
7. 如果 controller 开启了 `@SensitiveResponse`，则在响应写回前基于上下文和存储态脱敏值做最终替换。
8. 如果 service、装配器或导出构建方法标注了 `@SensitiveResponseTrigger`，则只会在 controller 已经打开上下文的前提下，对该方法返回值额外做一次脱敏；否则保持透传。

## 风险控制

1. 未找到字段规则时不做隐式处理。
2. 对加密字段的 `ORDER BY`、`BETWEEN`、`>`、`<` 等语义不可靠操作直接抛错。
3. 插件调试日志仅输出脱敏值，不输出明文和真实密文。
4. 辅助等值查询列如果本身为哈希值，允许在日志中输出哈希值以帮助定位问题，但仍不输出明文和真实密文。
5. 运行时和迁移模块都提供结构化异常体系，可通过 `EncryptionException` / `MigrationException` 的 `getErrorCode()` 做稳定分类。

## 当前实现范围

已实现：

- 注解与配置双规则模型
- 默认 SM4 主加密算法、SM3 辅助查询算法、标准化 LIKE 算法
- AES / SHA-256 兼容算法
- `INSERT/UPDATE/DELETE/SELECT` 的等值查询、LIKE 查询、写入列扩展
- 实体结果自动解密
- 独立加密表模式的字段同步与结果回填
- 启动期实体注解自动扫描
- Spring Boot 自动装配

当前未覆盖：

- 多表复杂子查询的深度重写
- 自定义 SQL 日志框架的完全接管
- 数据库函数中包裹加密字段的复杂表达式重写

## 验证状态

当前实现已经具备三层验证：

1. 元数据与算法层单元测试
2. SQL 改写矩阵单元测试
3. 真实 MyBatis / Spring Boot 自动装配集成测试

其中集成测试已经覆盖：

- 同表加密字段的真实写入和解密读取
- 独立加密表的真实同步、查询改写与结果回填
- Spring Boot 2 / 3 自动装配场景下拦截器单次注册与执行链路正确性

能力边界的细化说明见 [sql-support-matrix.md](sql-support-matrix.md)。
