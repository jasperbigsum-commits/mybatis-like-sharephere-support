# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

一个面向 MyBatis / MyBatis-Plus 的数据库字段加密插件，聚焦四件事：

- 持久层密文写入
- 等值查询与 LIKE 查询辅助列
- 查询结果自动解密
- controller 边界响应脱敏
- 基于 `SafeLog` 的日志脱敏扩展

默认算法使用国密组合：

- 主加密：`SM4`
- 辅助等值查询：`SM3`
- 默认 LIKE 查询辅助算法：`normalizedLike`

这更贴近国内商用密码落地场景，但仍要明确：

- 使用国密算法有助于满足等保/商密建设要求
- 接入本组件不等于已经完成合规
- 真正的合规仍依赖密钥管理、产品选型、运维制度和审计体系

## 文档导航

### 1. 快速使用

- [快速使用指南（中文）](docs/quick-start.zh-CN.md)
- [Quick Start (English)](docs/quick-start.en.md)

适合：

- 第一次接入
- 只想先跑通“写入加密 + 查询解密 + 响应脱敏”
- 需要最小配置项、注解属性和表结构示例

### 2. 持久层加密

- [持久层加密指南（中文）](docs/persistence-encryption-guide.zh-CN.md)
- [Persistence Encryption Guide (English)](docs/persistence-encryption-guide.en.md)
- [SQL Support Matrix](docs/sql-support-matrix.md)

适合：

- 想理解 `@EncryptField` 的各个列职责
- 想弄清楚同表模式、独立表模式、DTO 推断和 SQL 支持边界
- 需要 `@EncryptField` 全属性速查和常用字段组合建议

### 3. 响应脱敏

- [脱敏响应指南](docs/sensitive-response-guide.zh-CN.md)

适合：

- 想理解 `@SensitiveResponse`、`@SensitiveField`
- 想区分数据库存储态脱敏、DTO 输出脱敏、自定义脱敏器和复用 `likeAlgorithm`
- 需要注解属性说明、自定义脱敏器示例和策略选择表

### 4. 日志脱敏扩展

`logsafe` 是独立的日志安全扩展，不影响 SQL 改写、结果解密或 controller 脱敏。
业务代码可以直接通过静态门面调用；Spring Boot starter 会把已注册算法和末端兜底能力接进来：

- 主动脱敏入口：
  - `SafeLog.of(obj)`：对对象日志做脱敏副本输出，不修改原对象
  - `SafeLog.of(obj, hint)`：允许显式传入语义提示
  - `SafeLog.kv(key, value)`：对 `password`、`token`、`phone`、`email`、`idCard`、`bankCard` 等常见日志键做兜底脱敏
- 末端兜底 SPI：
  - `LogsafeTextMasker`：用于第三方日志、异常消息、网关日志或异常上报 SDK
  - Spring Boot 3 + Logback：检测到 Logback 时，会自动给现有 appender 挂载末端掩码 filter
- 运行时上下文：
  - `logsafe` MDC 上下文：对 Spring MVC 请求自动写入并清理 `traceId` / `requestId`
  - `logsafe` 异步传播：通过 `TaskDecorator` 传播 MDC 到异步任务并在执行后恢复线程原状态

该扩展复用现有 `@SensitiveField` 和已注册的 LIKE 脱敏算法，不改变原有 controller 边界响应脱敏行为。
在非 Spring 场景下，`SafeLog` 仍会使用内置兜底规则做常见字段脱敏。
Spring Boot 2 可以直接使用 `SafeLog` 和 `LogsafeTextMasker` 的通用 API；当前自动末端注入先支持 Spring Boot 3 的 Logback 场景，且可通过 `mybatis.encrypt.logsafe.terminal.enabled=false` 关闭。Log4j2、JUL、网关日志或异常上报 SDK 可在自定义适配器中显式调用 `LogsafeTextMasker`。

### 4. 存量迁移

- [存量迁移指南（中文）](docs/migration-guide.zh-CN.md)
- [Migration Guide (English)](docs/migration-guide.en.md)
- [迁移生产上线操作手册（中文）](docs/migration-production-runbook.zh-CN.md)
- [Migration Production Runbook (English)](docs/migration-production-runbook.en.md)
- [迁移游标设计指南（中文）](docs/migration-cursor-design.zh-CN.md)
- [Migration Cursor Design Guide (English)](docs/migration-cursor-design.en.md)

适合：

- 已有历史明文数据，需要补写密文列、辅助列、脱敏列
- 需要断点恢复、DDL 生成、风险确认
- 准备进入生产变更窗口，需要按 checklist 执行迁移
- 需要迁移 builder 参数、配置项和自动装配 Bean 的用途说明

### 5. 架构与维护

- [架构设计](docs/architecture.md)
- [开发与验收](docs/development.md)
- [项目理念与需求](docs/projects.md)
- [发布指南](RELEASE.md)

适合：

- 想理解模块边界、执行链路、失败策略
- 想参与开发、评审设计或做上线验收

## 你应该先看哪一份

如果你的目标是：

- 先跑通最小可用链路：看 [快速使用指南（中文）](docs/quick-start.zh-CN.md)
- 理解字段如何落密文、如何支持查询：看 [持久层加密指南（中文）](docs/persistence-encryption-guide.zh-CN.md)
- 让对外接口自动返回脱敏值：看 [脱敏响应指南](docs/sensitive-response-guide.zh-CN.md)
- 处理历史数据：看 [存量迁移指南（中文）](docs/migration-guide.zh-CN.md)
- 按生产窗口执行迁移：看 [迁移生产上线操作手册（中文）](docs/migration-production-runbook.zh-CN.md)
- 判断某种 SQL 是否支持：看 [SQL Support Matrix](docs/sql-support-matrix.md)

## 核心能力速览

- 支持注解和配置两种方式声明加密规则
- 支持 `INSERT` / `UPDATE` / `DELETE` / `SELECT` 改写
- 支持同表模式和独立表模式
- 支持 `assistedQueryColumn` 等值查询
- 支持 `likeQueryColumn` 模糊查询
- 支持结果自动解密
- 支持 `@EncryptResultHint` 推断无注解 DTO 的解密来源
- 支持 `@SensitiveResponse` 控制 controller 输出是否脱敏
- 支持 `@SensitiveField` 的三种写法：
  - 内置规则：`type + keepFirst + keepLast + maskChar`
  - 复用 LIKE 算法：`likeAlgorithm`
  - 自定义脱敏器 Bean：`masker + options`
- 支持 `SafeLog` 日志脱敏扩展：
  - 复用 `@SensitiveField`
  - 复用已注册 LIKE 脱敏算法
  - 对常见敏感日志键做字符串级兜底脱敏
  - 提供 `LogsafeTextMasker` 作为输出端兜底 SPI
  - 检测到 Logback 时自动注入 appender 末端 filter
- 支持迁移模块、DDL 生成、checkpoint 恢复和确认策略

## 已知边界

当前实现对以下场景保持保守策略：

- 不支持加密字段上的 `ORDER BY`
- 不支持加密字段上的范围查询，如 `BETWEEN`、`>`、`<`
- 不承诺任意复杂多层子查询都能正确重写
- 不承诺数据库函数包裹后的复杂表达式都能自动推断
- 对不安全或语义不可靠的场景优先 fail-fast，而不是静默放行

## 引入方式

Spring Boot 3 项目优先使用：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2 项目使用：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

如果你要统一版本，推荐配合 BOM。更完整的 Maven / Gradle / 多模块引入方式见
[快速使用指南（中文）](docs/quick-start.zh-CN.md)。

## 本地构建

```bash
mvn -Dmaven.repo.local=.m2repo install
```

仅验证 Spring Boot 3 主模块：

```bash
mvn -Dmaven.repo.local=.m2repo -pl spring-starter/spring3-starter -am test
```

更多开发和验收建议见 [开发与验收](docs/development.md)。
