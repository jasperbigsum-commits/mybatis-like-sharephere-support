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
