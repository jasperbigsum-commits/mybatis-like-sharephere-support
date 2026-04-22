# 开发与验收

## 这份文档适合谁

这份文档面向开发者、评审者和上线验收人员。

如果你是业务接入方，优先阅读：

- [快速使用指南](quick-start.zh-CN.md)
- [持久层加密指南](persistence-encryption-guide.zh-CN.md)
- [脱敏响应指南](sensitive-response-guide.zh-CN.md)
- [存量迁移指南](migration-guide.zh-CN.md)

## 工具链

当前仓库未内置 Maven Wrapper，请使用本机 Maven（`mvn`）执行构建。

常用命令（Windows / Linux / macOS 通用）：

```bash
mvn "-Dmaven.repo.local=.m2repo" test
```

只验证 Spring Boot 3 主模块（推荐）：

```bash
mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test
```

## 测试分层

当前测试分为三类：

- 算法测试：验证 SM4 / SM3 等默认算法的基本行为
- 核心单测：验证 SQL 改写、元数据解析、结果解密等纯逻辑组件
- 配置与验收测试：验证方言转义、日志脱敏、自动扫描等接入能力

## 测试分组执行

根 `pom.xml` 已接入 Maven Surefire 的 JUnit 5 `@Tag` 过滤能力，可通过以下参数按组执行：

- `-Dtest.groups=...`：只执行指定标签的测试
- `-Dtest.excludedGroups=...`：排除指定标签的测试

当前仓库主要标签如下：

- `unit`：纯单元测试
- `integration`：集成测试
- `algorithm`：算法与脱敏算法测试
- `rewrite`：SQL 改写与日志改写测试
- `parser`：JSqlParser 解析与预处理测试
- `metadata`：元数据解析与实体扫描测试
- `decrypt`：结果解密测试
- `mask`：存储态脱敏解析测试
- `plugin`：MyBatis 拦截器测试
- `migration`：迁移、断点恢复、DDL 生成与确认策略测试
- `config`：自动配置、方言与 Spring 配置测试
- `support`：存储支撑组件测试
- `web`：controller 边界脱敏测试

常用命令示例：

只跑 SQL 改写相关测试：

```bash
mvn "-Dmaven.repo.local=.m2repo" "-Dtest.groups=rewrite" test
```

只跑迁移相关测试：

```bash
mvn "-Dmaven.repo.local=.m2repo" "-Dtest.groups=migration" test
```

只跑纯单测，排除集成测试：

```bash
mvn "-Dmaven.repo.local=.m2repo" "-Dtest.groups=unit" "-Dtest.excludedGroups=integration" test
```

同时跑多组测试时，使用逗号分隔：

```bash
mvn "-Dmaven.repo.local=.m2repo" "-Dtest.groups=algorithm,metadata" test
```

## 最小验收清单

每次提交前至少确认：

1. `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test` 可以通过
2. 默认国密算法测试通过
3. SQL 改写测试通过
4. 日志脱敏测试通过
5. 自动扫描测试通过

## 驱动兼容验收

当前方言层重点覆盖：

- MySQL
- OceanBase
- 达梦
- Oracle12
- ClickHouse

验收重点：

- 插件内部生成的标识符是否按方言正确转义
- 独立加密表同步 SQL 是否可执行
- 查询改写日志中是否出现明文或真实密文
- 哈希辅助列日志是否保留哈希值

## 当前限制

- 当前仓库仍需补真实数据库驱动下的集成测试
- 初次构建可能需要下载依赖，需确保网络与 Maven 配置可用
- 生产级验收仍建议在目标数据库上补充回归用例
