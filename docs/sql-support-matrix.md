# SQL Support Matrix

## When to read this

Use this document as a boundary reference, not as a getting-started guide.

Recommended reading order:

1. [快速使用指南](quick-start.zh-CN.md) / [Quick Start](quick-start.en.md)
2. [持久层加密指南](persistence-encryption-guide.zh-CN.md) / [Persistence Encryption Guide](persistence-encryption-guide.en.md)
3. this SQL support matrix when you need to judge whether one SQL shape is safe

This matrix is especially useful during code review, SQL design review, and production rollout checks.

## Scope

This matrix describes the current runtime behavior of the encryption plugin after SQL rewrite,
parameter replacement, result decryption, and separate-table synchronization have all been wired
through real MyBatis execution tests.

## Supported

| Category | Status | Notes |
| --- | --- | --- |
| `INSERT` | Supported | Rewrites logical encrypted columns to `storageColumn`, appends assisted/like helper columns, and drops separate-table logical columns from the main table insert. |
| `UPDATE` | Supported | Rewrites same-table encrypted assignments to `storageColumn`; separate-table fields are removed from the main update and synchronized afterward. |
| `DELETE` | Supported | Supports encrypted predicates in `WHERE`; separate-table records are deleted after the main delete. |
| `SELECT` explicit columns | Supported | Rewrites encrypted logical columns to `storageColumn AS logicalColumn`. |
| `SELECT *` / `SELECT t.*` | Supported with caveats | Expands same-table encrypted fields by appending alias projections; complex multi-encrypted-table wildcard scenarios remain conservative. |
| Equality predicates `=` / `!=` | Supported | Uses assisted query columns when configured, otherwise falls back to `storageColumn`. |
| `LIKE` | Supported | Requires `likeQueryColumn`. |
| `IN (?, ?, ?)` | Supported | Uses assisted query column when available, otherwise `storageColumn`. |
| `IN (subquery)` | Supported | Rewrites the subquery projection into comparison mode. |
| `NOT IN` | Supported | Uses the same rewrite path as `IN`, preserving `NOT`. |
| `EXISTS (subquery)` | Supported | Recursively rewrites the nested select. |
| Nested / parenthesized conditions | Supported | `AND`, `OR`, `NOT (...)`, and nested parentheses are rewritten recursively. |
| `CASE WHEN` in predicates | Supported | Rewrites encrypted references inside `WHEN`, `THEN`, and `ELSE` expressions. |
| `HAVING` | Supported | Runs through the same predicate rewrite pipeline as `WHERE`. |
| `QUALIFY` | Supported | Rewrites predicates the same way as `WHERE` and `HAVING`. |
| Derived tables | Supported with helper aliases | Derived subqueries can project hidden assisted/like aliases so outer predicates on logical columns still work. |
| `UNION` / `UNION ALL` | Supported | Each branch is rewritten recursively. |
| Same-table decryption | Supported | Query results are decrypted back into entity properties. |
| Separate-table hydration | Supported | Separate-table ciphertext is synchronized on write and hydrated on read by entity id. |

## Common SQL Shapes

Use these examples as low-cost templates during mapper review.

| Goal | Recommended SQL shape | Why |
| --- | --- | --- |
| exact lookup by phone | `where phone = #{phone}` | rewritten to `assistedQueryColumn` when configured |
| lookup by multiple IDs / phones | `where phone in (...)` | each value can be transformed through the same helper path |
| fuzzy lookup | `where phone like concat('%', #{keyword}, '%')` | requires `likeQueryColumn` and `likeQueryAlgorithm` |
| return decrypted entity | `select id, phone from user_account` | logical projection can be mapped back to the entity property |
| return flat DTO | explicit aliases plus `@EncryptResultHint` | keeps projected source columns traceable |
| return only masked display value | select `maskedColumn` directly | avoids unnecessary decrypt-then-mask work |

Avoid these if the field is encrypted:

| Goal | Avoid | Safer alternative |
| --- | --- | --- |
| sort by encrypted field | `order by phone` | sort by a non-sensitive business column |
| range query | `where phone > ?` / `between` | redesign query or add a safe business index field |
| aggregate encrypted value | `max(phone)` / `count(distinct phone)` | aggregate on non-sensitive semantics |
| derive encrypted expression | `substr(phone, 1, 3)` | query plaintext in trusted code path or use stored masked values |
| ambiguous multi-table wildcard | `select * from a join b ...` | use explicit columns and aliases |

## Fail Fast By Design

| Category | Status | Reason |
| --- | --- | --- |
| `ORDER BY` encrypted fields | Rejected | Ordering on encrypted/hash values is semantically unsafe. |
| `GROUP BY` encrypted fields | Rejected | Grouping on encrypted/hash values is semantically unsafe. |
| Range predicates `>`, `>=`, `<`, `<=`, `BETWEEN` | Rejected | The current algorithms do not preserve order semantics. |
| `DISTINCT` on encrypted fields | Rejected | Distinctness would be applied to ciphertext/helper values rather than business semantics. |
| Aggregate functions on encrypted fields | Rejected | `COUNT(phone)`, `SUM(phone)`, `MAX(phone)` and similar expressions are not semantically reliable. |
| Window functions referencing encrypted fields | Rejected | `PARTITION BY`, analytic `ORDER BY`, filter expressions, and named windows fail fast when encrypted fields are involved. |
| Separate-table encrypted field in `IN` subquery projection | Rejected | The plugin currently only supports same-table encrypted field comparison subqueries. |

## Still Conservative / Not Fully Covered

| Category | Status | Notes |
| --- | --- | --- |
| Deep multi-level derived table chains | Partial | Core derived-table predicate rewriting is supported, but highly nested alias chains are still conservative. |
| Complex function-wrapped expressions | Partial | Simple recursive traversal exists, but not every database-specific function form is covered. |
| `CASE` used as a projected expression and then referenced again outside | Partial | Common predicate forms are supported; more exotic alias chaining remains conservative. |
| Vendor-specific clauses beyond current test matrix | Partial | The plugin has explicit dialect quoting support, but advanced clause coverage is still driven by tested AST paths. |

## Integration-Tested Execution Paths

The current repository now contains end-to-end MyBatis + H2 integration coverage for:

1. Same-table encrypted insert and lookup with decrypted entity return.
2. Separate-table encrypted field synchronization after insert.
3. Separate-table lookup with result hydration back into the entity property.

These tests complement the existing SQL rewrite unit matrix and are intended to catch
real execution bugs such as stale parameter mappings or invalid subquery rendering.
