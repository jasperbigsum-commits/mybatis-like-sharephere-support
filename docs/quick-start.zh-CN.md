# 快速使用指南

[中文](quick-start.zh-CN.md) | [English](quick-start.en.md)

## 适合谁

这份文档适合第一次接入本项目的使用者，目标是用最少步骤跑通：

- 写入自动加密
- 查询自动解密
- controller 自动脱敏

如果你想深入理解字段模型、SQL 支持边界、DTO 推断或迁移策略，再继续阅读：

- [持久层加密指南](persistence-encryption-guide.zh-CN.md)
- [脱敏响应指南](sensitive-response-guide.zh-CN.md)
- [存量迁移指南](migration-guide.zh-CN.md)

## 第一步：引入依赖

Spring Boot 3：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

如果要统一多个 starter 版本，推荐引入 BOM。

## 第二步：写最小配置

```yaml
mybatis:
  configuration:
    map-underscore-to-camel-case: true
  encrypt:
    enabled: true
    default-cipher-key: change-me-before-production
    scan-entity-annotations: true
    scan-packages:
      - com.example.user
    sensitive-response:
      enabled: true
```

配置项速查：

| 配置项 | 是否必填 | 作用 | 推荐值 / 建议                           |
| --- | --- | --- |------------------------------------|
| `mybatis.configuration.map-underscore-to-camel-case` | 否 | 下划线列到驼峰属性自动映射 | DTO / 实体用驼峰时推荐 `true`              |
| `mybatis.encrypt.enabled` | 否 | 总开关 | 默认即可，通常保持 `true`                   |
| `mybatis.encrypt.default-cipher-key` | 是 | 默认主加密算法密钥 | 生产环境必须替换，不能用示例值                    |
| `mybatis.encrypt.scan-entity-annotations` | 否 | 扫描 `@EncryptField` / `@EncryptTable` | 注解模式推荐 `true`                      |
| `mybatis.encrypt.scan-packages` | 注解扫描时推荐 | 限定实体扫描包 | 只填业务实体所在包，避免全项目扫描                  |
| `mybatis.encrypt.separate-table-hydration-batch-size` | 否 | 独立表结果回填时单批查询的 hash 数量上限 | 默认 `2000`；结果量很大或数据库对 `IN` 项数敏感时可调小 |
| `mybatis.encrypt.sensitive-response.enabled` | 否 | controller 边界脱敏开关 | 对外接口要自动脱敏时设为 `true`                |

最少要点：

- `default-cipher-key` 必填
- `scan-entity-annotations=true` 后会扫描带 `@EncryptField` 的实体
- `sensitive-response.enabled=true` 后才会启用 controller 边界脱敏

## 第三步：给实体字段加规则

```java
@EncryptTable("user_account")
public class UserAccount {

    private Long id;
    private String name;

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
}
```

`@EncryptField` 常用属性速查：

| 属性 | 是否常用 | 作用 | 典型值 |
| --- | --- | --- | --- |
| `column` | 必填 | 逻辑业务字段对应的源列名 | `phone` |
| `storageColumn` | 强烈推荐 | 密文字段列名 | `phone_cipher` |
| `assistedQueryColumn` | 等值查询时必填 | `=` / `IN` 查询辅助列 | `phone_hash` |
| `assistedQueryAlgorithm` | 配置辅助列时推荐 | 等值查询算法 Bean 名 | `sm3` |
| `likeQueryColumn` | LIKE 查询时必填 | 模糊查询辅助列 | `phone_like` |
| `likeQueryAlgorithm` | 配置 LIKE 列时必填 | LIKE 查询算法 Bean 名 | `phoneMaskLike` / `normalizedLike` |
| `maskedColumn` | 对外接口推荐 | 存储态脱敏列，供响应层直接复用 | `phone_masked` |
| `maskedAlgorithm` | 配置脱敏列时推荐 | 写入 / 迁移时生成 `maskedColumn` 的算法 | `phoneMaskLike` |

最简单的理解方式：

- `storageColumn` 解决“怎么存密文”
- `assistedQueryColumn` 解决“怎么等值查”
- `likeQueryColumn` 解决“怎么模糊查”
- `maskedColumn` 解决“怎么稳定返回脱敏值”

典型表结构示例：

```sql
create table user_account (
    id bigint primary key,
    name varchar(64),
    phone varchar(64),
    phone_cipher varchar(512),
    phone_hash varchar(128),
    phone_like varchar(255),
    phone_masked varchar(255),
    create_by varchar(50),
    create_time varchar(100)
);
```

## 第四步：正常写 Mapper

```java
@Mapper
public interface UserMapper {

    @Insert("""
        insert into user_account (id, name, phone)
        values (#{id}, #{name}, #{phone})
        """)
    int insert(UserAccount user);

    @Select("""
        select id, name, phone
        from user_account
        where phone = #{phone}
        """)
    UserAccount selectByPhone(@Param("phone") String phone);
}
```

这里不要自己手工加密。业务层继续传明文即可。

运行时框架会自动完成：

- 写入时：明文转密文、补写 hash / like / masked 列
- 查询时：`where phone = ?` 改写到辅助列
- 返回时：把结果中的密文解回 DTO 明文

## 第五步：在 controller 开启响应脱敏

```java
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserMapper userMapper;

    public UserController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @SensitiveResponse
    @GetMapping("/phone/{phone}")
    public UserAccount detail(@PathVariable String phone) {
        return userMapper.selectByPhone(phone);
    }
}
```

调用链路会变成：

1. Mapper 查询仍然传明文手机号
2. SQL 被改写到 `phone_hash`
3. 结果里的 `phone_cipher` 被自动解密成明文
4. controller 返回前，`phone_masked` 会覆盖回 DTO 的 `phone`

## 最小返回模型选择

### 场景 A：直接返回实体

推荐：

- 用 `@SensitiveResponse`
- 保持默认 `RECORDED_ONLY`

### 场景 B：返回扁平 DTO

如果 DTO 本身没有 `@EncryptField`，但字段来自加密表：

```java
@EncryptResultHint(tables = "user_account")
List<UserView> selectViews();
```

适合：

- `resultType` DTO
- join / 别名投影

### 场景 C：手工组装输出 DTO

```java
public class UserView {

    @SensitiveField(likeAlgorithm = "phoneMaskLike")
    private String phone;
}
```

```java
@SensitiveResponse(strategy = SensitiveResponseStrategy.ANNOTATED_FIELDS)
```

适合：

- controller 返回的对象不是 MyBatis 原始结果对象
- 只想在最终输出层做脱敏

`@SensitiveField` 常用写法速查：

| 写法 | 适用场景 | 示例 |
| --- | --- | --- |
| 内置规则 | 常见手机号、姓名、邮箱等输出脱敏 | `@SensitiveField(type = SensitiveMaskType.PHONE)` |
| 复用 LIKE 算法 | 响应脱敏要和 `maskedAlgorithm` 保持一致 | `@SensitiveField(likeAlgorithm = "phoneMaskLike")` |
| 自定义脱敏器 | 展示规则特殊，且需要字段级额外参数 | `@SensitiveField(masker = "customerMasker", options = {"prefix=VIP-", "keepLast=3"})` |

如果你只想最省成本：

- 有 `maskedColumn` 时，优先走 `@SensitiveResponse`
- 没有 `maskedColumn` 但已有成熟 LIKE 脱敏算法时，优先走 `likeAlgorithm`
- 只有通用算法不够时，才写自定义 `masker`

## 常见踩坑

- 不要在 getter 里二次查库并覆盖字段值
- 不要把数据库里已经是脱敏值的字段再额外做一次 `@SensitiveField`
- 不支持加密字段上的 `ORDER BY` 和范围查询
- 遇到复杂 SQL、函数表达式、`union` 多分支时，不要依赖自动猜测

最常见的三种最小案例：

| 目标 | 推荐写法 | 成本 |
| --- | --- | --- |
| 最简单的实体读写加密 | 实体 `@EncryptField` + 普通 Mapper SQL | 最低 |
| 对外接口自动脱敏 | 再加 `@SensitiveResponse` + `maskedColumn` | 低 |
| 手工 DTO 输出脱敏 | DTO 上 `@SensitiveField` + `ANNOTATED_FIELDS` | 中 |

## 下一步读什么

- 想理解字段模型和 DTO 推断：看 [持久层加密指南](persistence-encryption-guide.zh-CN.md)
- 想深入用响应脱敏和自定义脱敏器：看 [脱敏响应指南](sensitive-response-guide.zh-CN.md)
- 想处理历史明文数据：看 [存量迁移指南](migration-guide.zh-CN.md)
