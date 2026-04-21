# mybatis-like-sharephere-support

[中文](README.md) | [English](README.en.md)

一个面向 MyBatis / MyBatis-Plus 的数据库字段加密插件，聚焦四件事：

- 持久层密文写入
- 等值查询与 LIKE 查询辅助列
- 查询结果自动解密
- controller 边界响应脱敏

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

### 4. 存量迁移

- [存量迁移指南（中文）](docs/migration-guide.zh-CN.md)
- [Migration Guide (English)](docs/migration-guide.en.md)
- [迁移游标设计指南（中文）](docs/migration-cursor-design.zh-CN.md)
- [Migration Cursor Design Guide (English)](docs/migration-cursor-design.en.md)

适合：

- 已有历史明文数据，需要补写密文列、辅助列、脱敏列
- 需要断点恢复、DDL 生成、风险确认
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
