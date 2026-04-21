# Persistence Encryption Guide

[中文](persistence-encryption-guide.zh-CN.md) | [English](persistence-encryption-guide.en.md)

## Who this is for

This guide is for users who already understand the minimal happy path and now want to understand:

- what each encrypted column role means
- when to choose same-table vs separate-table storage
- how DTO inference works
- where SQL support becomes conservative

If you have not completed the first integration yet, start with [Quick Start](quick-start.en.md).

## What persistence encryption is responsible for

The persistence layer in this project solves four problems:

1. how plaintext fields are stored as ciphertext
2. how equality lookup stays usable
3. how LIKE lookup stays usable
4. how query results return to business code as plaintext

Controller-boundary masking is a separate concern. See [Sensitive Response Guide](sensitive-response-guide.zh-CN.md).

## Field model

Typical `@EncryptField` example:

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

`@EncryptField` quick reference:

| Attribute | Typical usage | Meaning | If omitted | Example |
| --- | --- | --- | --- | --- |
| `column` | required | logical source column | rule cannot be built | `phone` |
| `cipherAlgorithm` | optional | primary cipher bean name | falls back to default cipher | `sm4` / `aes` |
| `storageColumn` | strongly recommended | ciphertext column | same-table encryption cannot persist correctly | `phone_cipher` |
| `assistedQueryColumn` | common for equality lookup | helper column for `=` / `IN` | equality lookup becomes limited or fails fast | `phone_hash` |
| `assistedQueryAlgorithm` | recommended with assisted column | equality helper bean name | fails when equality helper is required | `sm3` / `sha256` |
| `likeQueryColumn` | required for LIKE | helper column for fuzzy lookup | `LIKE` is unsupported | `phone_like` |
| `likeQueryAlgorithm` | required with LIKE column | LIKE preprocessing bean name | `LIKE` is unsupported | `normalizedLike` / `phoneMaskLike` |
| `maskedColumn` | recommended for external APIs | stored masked output column | response masking falls back to runtime generation only | `phone_masked` |
| `maskedAlgorithm` | recommended with masked column | algorithm used to generate `maskedColumn` | stable masked persistence cannot be generated | `phoneMaskLike` |
| `storageMode` | optional | storage mode, defaults to same-table | same-table mode is used | `SEPARATE_TABLE` |
| `storageTable` | required for separate-table | external encryption table | separate-table rules cannot be built | `user_phone_encrypt` |
| `storageIdColumn` | common for separate-table | external id / link column | hydration and sync cannot work correctly | `id` / `encrypt_id` |

Three common field combinations:

| Goal | Minimum combination | Notes |
| --- | --- | --- |
| encrypted write + decrypted read | `column + storageColumn` | lowest cost, limited query power |
| add equality lookup | plus `assistedQueryColumn + assistedQueryAlgorithm` | recommended for phone / id-card exact lookups |
| add LIKE + stable masked output | plus `likeQueryColumn + likeQueryAlgorithm + maskedColumn + maskedAlgorithm` | full external API path |

## Same-table mode

Use this when:

- the main table can be extended directly
- you want the simplest operational model

Runtime behavior:

- writes ciphertext and helper columns into the same row
- rewrites query predicates to helper columns
- decrypts `storageColumn` back into the logical property on read

Typical fields:

- phone numbers
- ID cards
- bank cards
- email addresses
- sensitive identifiers that can stay in the same business table

## Separate-table mode

Use this when:

- ciphertext and derived columns should not live in the main business table
- main-table schema changes are constrained

Runtime behavior:

- the main table keeps the logical reference value
- the external table stores ciphertext, helper columns, and masked values
- reads hydrate the external data first, then decrypt back into the business property

Typical scenarios:

- the main table cannot absorb many derived columns
- sensitive data should be isolated physically
- a legacy main-table schema is constrained but adding an external table is acceptable

## DTO inference

### Case 1: entity or DTO declares encryption metadata

This is the most stable path.

### Case 2: DTO has no encryption annotations

Use `@EncryptResultHint`:

```java
@EncryptResultHint(tables = "user_account")
List<UserView> selectViews();
```

This preloads source metadata so flat `resultType` DTOs can still be decrypted through projection and alias mapping.

### Case 3: complex SQL

Recommended rules:

- the clearer the projected source column, the safer the inference
- if aliases drift away from original column names, add `@EncryptResultHint`
- if the return model is a nested object graph, prefer `resultMap`

Still conservative:

- `union` branches with inconsistent sources
- function-based projection expressions
- multi-level derived-table alias chains

For exact boundaries, see [SQL Support Matrix](sql-support-matrix.md).

DTO choice guide:

| Return model | Recommendation | Notes |
| --- | --- | --- |
| entity / DTO with `@EncryptField` | best | most stable, lowest maintenance |
| flat DTO without annotations + `@EncryptResultHint` | recommended | good for `resultType`, joins, and aliases |
| nested DTO + `resultMap` | recommended | let MyBatis build the object graph first |
| manually copied output DTO | not recommended for persistence inference | switch to response-layer masking instead |

Example join projection:

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

## Recommended usage patterns

### Standard query endpoint

Prefer:

- entity or stable DTO
- `maskedColumn`
- controller-boundary `@SensitiveResponse`

### Flat DTO query

Prefer:

- `@EncryptResultHint`
- clear and stable aliases

### Manually assembled output DTO

Do not force persistence inference to guess the final model. Switch to:

- `@SensitiveField`
- `@SensitiveResponse(strategy = ANNOTATED_FIELDS)`

### Query only the masked value

Prefer this when the business path never needs plaintext:

- project `maskedColumn` directly
- do not rely on result decryption for that field
- treat it as the final display field

## SQL support boundaries

Main supported categories:

- `INSERT`
- `UPDATE`
- `DELETE`
- `SELECT`
- equality lookup
- `IN`
- `LIKE`

Intentional fail-fast categories:

- encrypted-field `ORDER BY`
- range predicates
- `GROUP BY`, `DISTINCT`, aggregates, and windows on encrypted fields

See [SQL Support Matrix](sql-support-matrix.md) for the full matrix.
