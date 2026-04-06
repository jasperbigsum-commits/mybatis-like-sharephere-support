# 数据库加密插件架构设计

## 设计目标

1. 不侵入业务层 Mapper/Service 代码，对 MyBatis 或 MyBatis-Plus 生成的 SQL 做透明增强。
2. 支持字段加密、结果解密、等值查询、LIKE 查询、插入、更新、删除。
3. 提供类似 ShardingSphere 的规则配置方式，同时支持实体注解声明。
4. 对风险操作保持保守策略：对未声明规则的字段不做推断，对不支持的排序和范围查询直接报错。

## 核心架构

### 1. 元数据层

- `@EncryptField` / `@EncryptTable`：实体注解声明加密规则。
- `DatabaseEncryptionProperties`：支持 `mybatis.encrypt.tables.<table>.fields.<property>` 风格配置。
- `EncryptMetadataRegistry`：合并注解规则和配置规则，按表名和实体类型缓存。
- `EncryptEntityScanner`：可在启动期自动扫描带 `@EncryptField` 的实体，无需强制声明 `@EncryptTable`。

### 2. 算法 SPI

- `CipherAlgorithm`：负责密文写入与结果解密。
- `AssistedQueryAlgorithm`：负责等值查询的辅助列计算，适合哈希检索。
- `LikeQueryAlgorithm`：负责模糊查询列生成。
- `AlgorithmRegistry`：从 Spring Bean 容器按名称装配算法，允许用户扩展。

### 3. SQL 改写层

- `SqlRewriteEngine` 基于 JSqlParser 解析 `INSERT/UPDATE/DELETE/SELECT`。
- 对主加密列保持原列名，仅替换参数值为密文。
- 对 `assistedQueryColumn` / `likeQueryColumn` 自动补充插入列、更新列并改写 WHERE 条件。
- 对独立加密表字段，查询条件改写为 `EXISTS` 子查询，主表写入 SQL 中移除该字段。
- 对插件内部新生成的标识符，按配置的 `sqlDialect` 输出对应转义风格，当前支持 MySQL、OceanBase、达梦。
- 对排序、范围比较等不可安全支持的操作主动失败，避免出现“看似成功但结果错误”的情况。

### 4. MyBatis 插件层

- `DatabaseEncryptionInterceptor`
  - `StatementHandler.prepare`：执行 SQL 改写和参数替换。
  - `Executor.update`：同步独立加密表。
  - `ResultSetHandler.handleResultSets`：对结果实体进行字段解密。
- `ResultDecryptor`：只处理声明过规则的对象，避免误解密。
- `SeparateTableEncryptionManager`：处理独立加密表的回填和写后同步。

### 5. Spring Boot 自动配置

- `MybatisEncryptionAutoConfiguration`
  - 注册默认算法
  - 注册规则中心
  - 注册 `DatabaseEncryptionInterceptor` 并交由 MyBatis 自动装配链路接入

## 执行链路

1. 应用启动时读取 `application.yml` 和实体注解，注册加密规则。
2. SQL 执行前，插件解析 SQL 并定位命中的表与字段。
3. 写操作时主字段写入密文，同时追加辅助查询列或模糊查询列。
4. 查询条件遇到等值或 LIKE 时，改写到对应辅助列，并对参数做算法转换。
5. 查询结果返回后，按实体字段规则解密成业务可读值。

## 风险控制

1. 未找到字段规则时不做隐式处理。
2. 对加密字段的 `ORDER BY`、`BETWEEN`、`>`、`<` 等语义不可靠操作直接抛错。
3. 插件调试日志仅输出脱敏值，不输出明文和真实密文。
4. 辅助等值查询列如果本身为哈希值，允许在日志中输出哈希值以帮助定位问题，但仍不输出明文和真实密文。

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
- Spring Boot 自动装配场景下拦截器单次注册与执行链路正确性

能力边界的细化说明见 [sql-support-matrix.md](sql-support-matrix.md)。
