# 持久层加密指南

[中文](persistence-encryption-guide.zh-CN.md) | [English](persistence-encryption-guide.en.md)

## 适合谁

这份文档面向已经跑通最小链路，但还想进一步理解下面问题的使用者：

- 各种列到底分别干什么
- 同表模式和独立表模式如何选
- DTO 无注解时如何解密
- 哪些 SQL 支持，哪些场景必须保守处理

如果你还没跑通最小链路，先看 [快速使用指南](quick-start.zh-CN.md)。

## 持久层加密的目标

这个项目在持久层解决的是四件事：

1. 明文字段如何落密文
2. 等值查询如何可用
3. LIKE 查询如何可用
4. 查询结果如何回到业务层明文

响应脱敏不属于持久层职责，它是 controller 边界能力，详见 [脱敏响应指南](sensitive-response-guide.zh-CN.md)。

## 字段模型

`@EncryptField` 中最常见的几个属性：

```java
@EncryptField(
        column = "phone",
        storageColumn = "phone_cipher",
        assistedQueryColumn = "phone_hash",
        assistedQueryAlgorithm = "sm3",
        likeQueryColumn = "phone_like",
        likeQueryAlgorithm = "phoneMaskLike",
        maskedColumn = "phone_masked",
        maskedAlgorithm = "phoneMaskLike"
)
private String phone;
```

`@EncryptField` 属性速查：

| 属性 | 是否常用 | 作用 | 不填会怎样 | 典型值 |
| --- | --- | --- | --- | --- |
| `column` | 必填 | 逻辑业务列 / 源列 | 无法建立字段规则 | `phone` |
| `cipherAlgorithm` | 可选 | 主加密算法 Bean 名 | 走默认主加密算法 | `sm4` / `aes` |
| `storageColumn` | 强烈推荐 | 密文列 | 同表模式下无法正确落密文 | `phone_cipher` |
| `assistedQueryColumn` | 等值查询常用 | `=` / `IN` 辅助列 | 等值查询只能退回密文列或直接失败 | `phone_hash` |
| `assistedQueryAlgorithm` | 配合辅助列时推荐 | 辅助等值查询算法 | 需要时会报缺算法 | `sm3` / `sha256` |
| `likeQueryColumn` | LIKE 查询时必填 | 模糊查询辅助列 | `LIKE` 查询不支持 | `phone_like` |
| `likeQueryAlgorithm` | 配合 LIKE 列时必填 | LIKE 预处理算法 | `LIKE` 查询不支持 | `normalizedLike` / `phoneMaskLike` |
| `maskedColumn` | 对外接口推荐 | 存储态脱敏列 | 响应层只能临时算法回退 | `phone_masked` |
| `maskedAlgorithm` | 配合脱敏列时推荐 | 生成 `maskedColumn` 的算法 | 迁移 / 写入期无法稳定生成脱敏列 | `phoneMaskLike` |
| `storageMode` | 按需 | 存储模式，默认同表 | 默认同表模式 | `SEPARATE_TABLE` |
| `storageTable` | 独立表模式必填 | 独立加密表表名 | 独立表模式无法建规则 | `user_phone_encrypt` |
| `storageIdColumn` | 独立表模式常用 | 独立表关联 id 列 | 回填 / 同步会失效 | `id` / `encrypt_id` |

推荐理解方式：

- `storageColumn` 解决“怎么存”
- `assistedQueryColumn` 解决“怎么等值查”
- `likeQueryColumn` 解决“怎么模糊查”
- `maskedColumn` 解决“怎么稳定返回脱敏值”

三种最常见字段组合：

| 场景 | 最小字段组合 | 说明 |
| --- | --- | --- |
| 只要写密文和自动解密 | `column + storageColumn` | 最低成本，但查询能力有限 |
| 还要支持等值查询 | 再加 `assistedQueryColumn + assistedQueryAlgorithm` | 推荐手机号、证件号等精确查场景 |
| 还要支持 LIKE 和稳定脱敏返回 | 再加 `likeQueryColumn + likeQueryAlgorithm + maskedColumn + maskedAlgorithm` | 最完整，也最适合对外接口 |

## 同表模式

### 何时使用

适合：

- 主表可以直接扩列
- 你希望查询和维护路径最简单

### 示例

```java
@EncryptTable("user_account")
public class UserAccount {

    @EncryptField(
            column = "phone",
            storageColumn = "phone_cipher",
            assistedQueryColumn = "phone_hash",
            likeQueryColumn = "phone_like",
            maskedColumn = "phone_masked",
            maskedAlgorithm = "phoneMaskLike"
    )
    private String phone;
}
```

运行时行为：

- 写入时补写 `phone_cipher / phone_hash / phone_like / phone_masked`
- 查询时把逻辑列查询改写到辅助列
- 返回对象时把 `phone_cipher` 解回 `phone`

适合的典型字段：

- 手机号
- 身份证号
- 银行卡号
- 邮箱
- 需要落在同一业务表中的敏感标识

## 独立表模式

### 何时使用

适合：

- 不希望密文和派生列落在业务主表
- 业务主表结构不方便扩列

### 示例

```java
@EncryptField(
        column = "id_card",
        storageMode = FieldStorageMode.SEPARATE_TABLE,
        storageTable = "user_id_card_encrypt",
        storageColumn = "id_card_cipher",
        storageIdColumn = "id",
        assistedQueryColumn = "id_card_hash",
        likeQueryColumn = "id_card_like",
        maskedColumn = "id_card_masked",
        maskedAlgorithm = "idCardMaskLike"
)
private String idCard;
```

运行时行为：

- 主表保存辅助引用值
- 独立表保存密文、辅助列、脱敏列
- 查询结果会先回填独立表字段，再对业务对象解密

适合的典型场景：

- 主表不能扩太多密文派生列
- 需要把敏感数据集中隔离
- 老系统表结构受限，但允许新增独立加密表

## DTO 结果推断

### 场景 1：实体本身有 `@EncryptField`

最稳定，推荐优先使用。

### 场景 2：DTO 本身没有加密注解

可使用 `@EncryptResultHint`：

```java
@EncryptResultHint(tables = "user_account")
List<UserView> selectViews();
```

作用：

- 预热来源实体或来源表规则
- 让 `resultType` DTO 也能根据投影列、别名和驼峰映射完成解密

### 场景 3：复杂 SQL

建议原则：

- 投影列来源越清晰，越适合自动推断
- 别名完全脱离来源列名时，优先补 `@EncryptResultHint`
- 返回对象如果是嵌套图，优先使用 `resultMap`

保守边界：

- `union` 多分支来源不一致
- 函数表达式列
- 多层派生表重复改名

这些场景不要依赖自动猜测，详见 [SQL Support Matrix](sql-support-matrix.md)。

DTO 选择建议：

| 返回模型 | 推荐程度 | 说明 |
| --- | --- | --- |
| 带 `@EncryptField` 的实体 / DTO | 最推荐 | 最稳定，维护成本最低 |
| 无注解扁平 DTO + `@EncryptResultHint` | 推荐 | 适合 `resultType`、join、别名投影 |
| 嵌套对象图 DTO + `resultMap` | 推荐 | 先让 MyBatis 装配对象，再让插件解密 |
| 手工复制后的输出 DTO | 不建议继续依赖持久层自动推断 | 应切到响应层 `@SensitiveField` |

一个常见 join 扁平 DTO 示例：

```java
@EncryptResultHint(tables = {"user_account", "archive_user"})
@Select("""
    select u.id as user_id,
           u.phone as primary_phone,
           a.phone as backup_phone
    from user_account u
    left join archive_user a on a.user_id = u.id
    where u.id = #{id}
    """)
UserPhoneView selectUserPhoneView(@Param("id") Long id);
```

这里的关键不是 SQL 越复杂越好，而是最终投影列仍然能回溯到来源表字段。

### 跳过特定方法的加密处理

当 Mapper 方法操作的 SQL 不涉及加密字段时，可使用 `@SkipSqlRewrite` 跳过 SQL 重写与结果解密：

```java
@SkipSqlRewrite
@Select("select id, name from user_account where id = #{id}")
UserAccount selectById(@Param("id") Long id);
```

适用场景：

- 表没有加密字段，不需要经过 SQL 重写引擎
- SQL 包含 JSqlParser 无法解析的数据库方言语法
- 查询视图、派生表或仅查询脱敏列的接口

`@SkipSqlRewrite` 会使整个方法绕过加密处理链路（包括 SQL 改写、独立表写前准备和结果解密），
标注该注解的方法应确保不涉及任何加密字段的读写。

## 推荐使用方式

### 标准查询接口

推荐：

- 直接返回实体或结构稳定的 DTO
- 字段声明 `maskedColumn`
- controller 使用 `@SensitiveResponse`

### 扁平 DTO 查询

推荐：

- Mapper 方法上补 `@EncryptResultHint`
- 保持别名和 DTO 属性稳定对应

### 通配符查询约束

建议直接记住下面这条规则：

- 单表查询可以使用裸 `*`
- 多表查询不要使用裸 `*`
- 多表查询无论是否声明了表别名，都应写成显式表通配符

可用写法：

- `select user_account.*, order_account.* from user_account join order_account ...`
- `select u.*, o.* from user_account u join order_account o ...`
- `select u.phone, u.*, o.* from user_account u join order_account o ...`

不要这样写：

- `select * from user_account join order_account ...`
- `select phone, * from user_account join order_account ...`
- `select u.phone, * from user_account u join order_account o ...`

原因不是语法偏好，而是结果集稳定性：

- 插件需要把同表加密字段改写成 `storageColumn as logicalColumn`
- 这些逻辑投影必须排在对应 `table.*` / `alias.*` 前面，避免 MyBatis/JDBC 优先读到 wildcard 中的旧列
- 多表场景下裸 `*` 无法安全收敛到某一张表，所以插件会直接拒绝这类 SQL，而不是放出可能错乱的结果集

### 手工组装输出 DTO

不要再要求持久层自动推断最终输出对象，应改用：

- `@SensitiveField`
- `@SensitiveResponse(strategy = ANNOTATED_FIELDS)`

### 只查询脱敏值，不需要明文业务处理

推荐：

- 直接查询 `maskedColumn`
- 不再要求结果自动解密
- 把它当成最终展示字段

适合：

- 报表
- 纯展示型列表接口
- 审计或客服只需看脱敏值的场景

## SQL 支持边界

当前重点支持：

- `INSERT`
- `UPDATE`
- `DELETE`
- `SELECT`
- 等值查询
- `IN`
- `LIKE`

当前明确 fail-fast：

- `ORDER BY` 加密字段
- 范围查询
- `GROUP BY` / `DISTINCT` / 聚合 / 窗口函数中的加密字段

完整矩阵见 [SQL Support Matrix](sql-support-matrix.md)。

## 最佳实践

1. 能用同表模式时，优先同表模式。
2. 能直接返回实体或稳定 DTO 时，不要先上复杂对象装配。
3. `maskedColumn` 尽量在写入和迁移阶段落库，不要在响应层临时推导。
4. 复杂 SQL 优先先保证投影来源清晰，再谈自动推断。
5. 发现某类 SQL 语义不稳定时，宁可拆查询，也不要依赖模糊支持。
