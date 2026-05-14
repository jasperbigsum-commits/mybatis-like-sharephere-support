# Quick Start

[中文](quick-start.zh-CN.md) | [English](quick-start.en.md)

## Who this is for

This guide is for first-time users who want the shortest path to:

- encrypted writes
- decrypted query results
- controller-boundary masked responses

For deeper topics, continue with:

- [Persistence Encryption Guide](persistence-encryption-guide.en.md)
- [Sensitive Response Guide](sensitive-response-guide.zh-CN.md)
- [Migration Guide](migration-guide.en.md)

## 1. Add the starter

Spring Boot 3:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring2-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 2. Add minimal configuration

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

Configuration quick reference:

| Property | Required | Purpose | Recommendation |
| --- | --- | --- | --- |
| `mybatis.configuration.map-underscore-to-camel-case` | No | auto-map snake_case columns to camelCase properties | set `true` for typical DTO/entity models |
| `mybatis.encrypt.enabled` | No | global feature switch | usually keep `true` |
| `mybatis.encrypt.default-cipher-key` | Yes | default primary cipher key | replace before production |
| `mybatis.encrypt.scan-entity-annotations` | No | scan `@EncryptField` / `@EncryptTable` metadata | `true` for annotation-driven setups |
| `mybatis.encrypt.scan-packages` | Recommended with scanning | limit entity scanning scope | point only to domain packages |
| `mybatis.encrypt.separate-table-hydration-batch-size` | No | max hash count per separate-table hydration query batch | default `200`; lower it when result sets are large or the database is sensitive to long `IN` lists |
| `mybatis.encrypt.sensitive-response.enabled` | No | enable controller-boundary masking | `true` for external APIs |

## 3. Annotate one entity field

```java
@EncryptTable("user_account")
public class UserAccount {

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

Common `@EncryptField` attributes:

| Attribute | Typical usage | Meaning | Example |
| --- | --- | --- | --- |
| `column` | required | logical source column | `phone` |
| `storageColumn` | strongly recommended | ciphertext column | `phone_cipher` |
| `assistedQueryColumn` | required for equality lookups | helper column for `=` / `IN` | `phone_hash` |
| `assistedQueryAlgorithm` | recommended with assisted column | equality helper algorithm bean name | `sm3` |
| `likeQueryColumn` | required for LIKE | helper column for fuzzy lookup | `phone_like` |
| `likeQueryAlgorithm` | required with LIKE column | LIKE preprocessing bean name | `phoneMaskLike` |
| `maskedColumn` | recommended for external APIs | stored masked output column | `phone_masked` |
| `maskedAlgorithm` | recommended with masked column | algorithm used to generate `maskedColumn` | `phoneMaskLike` |

Typical table example:

```sql
create table user_account (
    id bigint primary key,
    phone varchar(64),
    phone_cipher varchar(512),
    phone_hash varchar(128),
    phone_like varchar(255),
    phone_masked varchar(255),
    create_by varchar(50),
    create_time varchar(100)
);
```

## 4. Keep writing normal MyBatis SQL

```java
@Select("""
    select id, phone
    from user_account
    where phone = #{phone}
    """)
UserAccount selectByPhone(@Param("phone") String phone);
```

Pass plaintext from business code. The plugin handles:

- encryption on write
- assisted-column rewrite on query
- result decryption on read

## 5. Enable controller-boundary masking

```java
@SensitiveResponse
@GetMapping("/users/phone/{phone}")
public UserAccount detail(@PathVariable String phone) {
    return userMapper.selectByPhone(phone);
}
```

The runtime flow becomes:

1. query parameter stays plaintext in business code
2. SQL predicate is rewritten to `phone_hash`
3. result ciphertext is decrypted back into `UserAccount.phone`
4. response masking replaces the final value with `phone_masked`

## Three recommended return-model patterns

### Pattern A: return the entity directly

Use:

- `@SensitiveResponse`
- default `RECORDED_ONLY`

### Pattern B: flat DTO result

Use `@EncryptResultHint` when the DTO itself has no encryption annotations:

```java
@EncryptResultHint(tables = "user_account")
List<UserView> selectViews();
```

### Pattern C: manually assembled output DTO

```java
public class UserView {

    @SensitiveField(likeAlgorithm = "phoneMaskLike")
    private String phone;
}
```

```java
@SensitiveResponse(strategy = SensitiveResponseStrategy.ANNOTATED_FIELDS)
```

`@SensitiveField` quick choices:

| Style | When to use | Example |
| --- | --- | --- |
| built-in type masking | common phone/name/email output masking | `@SensitiveField(type = SensitiveMaskType.PHONE)` |
| reuse LIKE algorithm | output masking must match an existing masking algorithm exactly | `@SensitiveField(likeAlgorithm = "phoneMaskLike")` |
| custom masker bean | field display rules need custom parameters | `@SensitiveField(masker = "customerMasker", options = {"prefix=VIP-", "keepLast=3"})` |

Most teams can keep the rollout simple:

- if `maskedColumn` exists, prefer `@SensitiveResponse`
- if `maskedColumn` does not exist but a reusable LIKE masking algorithm does, prefer `likeAlgorithm`
- only build a custom `masker` when the output rule is truly special

Three minimal scenarios:

| Goal | Recommended path | Cost |
| --- | --- | --- |
| encrypted write + decrypted read | `@EncryptField` + normal mapper SQL | low |
| masked external API | add `@SensitiveResponse` + `maskedColumn` | low |
| custom output-only DTO masking | `@SensitiveField` + `ANNOTATED_FIELDS` | medium |

## Next documents

- field model and DTO inference: [Persistence Encryption Guide](persistence-encryption-guide.en.md)
- advanced masking and custom maskers: [Sensitive Response Guide](sensitive-response-guide.zh-CN.md)
- historical data backfill: [Migration Guide](migration-guide.en.md)
