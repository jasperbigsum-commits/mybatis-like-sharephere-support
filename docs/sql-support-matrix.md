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
| `INSERT` | Supported | Rewrites logical encrypted columns to `storageColumn`, appends assisted/like helper columns, and drops separate-table logical columns from the main table insert. Supports both single-row and multi-row (batch) VALUES. |
| `UPDATE` | Supported | Rewrites same-table encrypted assignments to `storageColumn`; separate-table fields are removed from the main update and synchronized afterward. |
| `DELETE` | Supported | Supports encrypted predicates in `WHERE`; separate-table records are deleted after the main delete. |
| `SELECT` explicit columns | Supported | Rewrites encrypted logical columns to `storageColumn AS logicalColumn`. |
| `SELECT *` / `SELECT t.*` | Supported with caveats | Single-table queries may use bare `*`; multi-table queries must use explicit table wildcards such as `table.*` or `alias.*` so encrypted projections can stay ahead of the wildcard without being overwritten. |
| Equality predicates `=` / `!=` | Supported | Same-table fields use assisted query columns when configured, otherwise `storageColumn`; separate-table fields rewrite directly to the main-table reference/hash column. |
| Encrypted-column equality `a.phone = a.backup_phone` | Supported | Compares same-table assisted columns or separate-table main reference columns when both sides use the same assisted query algorithm, including aliased and parenthesized column references. |
| `JOIN ... ON` predicates | Supported | Runs encrypted predicates through the same rewrite pipeline as `WHERE`, including nested `EXISTS` subqueries and separate-table encrypted-column equality. |
| `LIKE` | Supported | Requires `likeQueryColumn`. |
| `LIKE` without `likeQueryColumn` | Supported as equality fallback | If `assistedQueryColumn` is configured, the condition is rewritten as assisted/hash equality. This is exact-match fallback only, not fuzzy matching. |
| `COUNT(encrypted_column)` | Supported with assisted/ref rewrite | Same-table fields rewrite to `assistedQueryColumn`; separate-table fields count the main-table reference column. |
| `COUNT(DISTINCT encrypted_column)` | Supported with assisted/ref rewrite | Same-table fields use `assistedQueryColumn`; separate-table fields use the main-table reference column. |
| Top-level `MAX(encrypted_column)` / `FIRST(encrypted_column)` | Supported with warning | Same-table fields require `assistedQueryColumn` as an explicit opt-in and aggregate the ciphertext column so the result can be decrypted; separate-table fields aggregate the main-table reference value and are hydrated after read. Results reflect technical values, not plaintext ordering semantics. |
| `IN (?, ?, ?)` | Supported | Same-table fields use assisted query column when available, otherwise `storageColumn`; separate-table fields rewrite directly to the main-table reference/hash column. |
| `IS NULL` / `IS NOT NULL` | Supported | Same-table fields check `storageColumn`; separate-table fields check the main-table reference/hash column directly. |
| `IN (subquery)` | Supported | Rewrites the subquery projection into comparison mode. |
| `NOT IN` | Supported | Uses the same rewrite path as `IN`, preserving `NOT`. |
| `EXISTS (subquery)` | Supported | Recursively rewrites the nested select. |
| Correlated subquery outer references | Supported | Qualified outer table names or aliases such as `outer_table.encrypted_col` are resolved in nested predicates, including separate-table encrypted-column equality. |
| Nested / parenthesized conditions | Supported | `AND`, `OR`, `NOT (...)`, and nested parentheses are rewritten recursively. |
| `CASE WHEN` in predicates | Supported | Rewrites encrypted references inside `WHEN`, `THEN`, and `ELSE` expressions. |
| `HAVING` | Supported | Runs through the same predicate rewrite pipeline as `WHERE`. |
| `QUALIFY` | Supported | Rewrites predicates the same way as `WHERE` and `HAVING`. |
| Derived tables | Supported with helper aliases | Derived subqueries can project hidden assisted/like aliases so outer predicates on logical columns still work. |
| `UNION` / `UNION ALL` | Supported | Each branch is rewritten recursively. |
| Same-table decryption | Supported | Query results are decrypted back into entity properties. |
| Separate-table hydration | Supported | Separate-table ciphertext is synchronized on write and hydrated on read by entity id. |
| `@SkipSqlRewrite` annotation | Supported | Method-level annotation that bypasses the entire SQL rewrite and result decryption pipeline for the annotated mapper method. |

## Common SQL Shapes

Use these examples as low-cost templates during mapper review.

| Goal | Recommended SQL shape | Why |
| --- | --- | --- |
| exact lookup by phone | `where phone = #{phone}` | rewritten to `assistedQueryColumn` when configured |
| compare two encrypted fields | `where phone = backup_phone` | rewritten to assisted columns or separate-table reference columns |
| lookup by multiple IDs / phones | `where phone in (...)` | each value can be transformed through the same helper path |
| fuzzy lookup | `where phone like concat('%', #{keyword}, '%')` | requires `likeQueryColumn` and `likeQueryAlgorithm`; without `likeQueryColumn`, only assisted/hash exact fallback is available |
| return decrypted entity | `select id, phone from user_account` | logical projection can be mapped back to the entity property |
| return flat DTO | explicit aliases plus `@EncryptResultHint` | keeps projected source columns traceable |
| return only masked display value | select `maskedColumn` directly | avoids unnecessary decrypt-then-mask work |

Avoid these if the field is encrypted:

| Goal | Avoid | Safer alternative |
| --- | --- | --- |
| sort by encrypted field | `order by phone` | sort by a non-sensitive business column |
| range query | `where phone > ?` / `between` | redesign query or add a safe business index field |
| aggregate encrypted value | `sum(phone)` / `avg(phone)` / expression aggregates | aggregate on non-sensitive semantics |
| derive encrypted expression | `substr(phone, 1, 3)` | query plaintext in trusted code path or use stored masked values |
| bare wildcard in multi-table query | `select * from a join b ...` / `select phone, * from a join b ...` | use `a.*`, `b.*`, or explicit columns instead of bare `*` |

## Fail Fast By Design

| Category | Status | Reason |
| --- | --- | --- |
| `ORDER BY` encrypted fields | Rejected | Ordering on encrypted/hash values is semantically unsafe. |
| `GROUP BY` encrypted fields | Rejected | Grouping on encrypted/hash values is semantically unsafe. |
| Range predicates `>`, `>=`, `<`, `<=`, `BETWEEN` | Rejected | The current algorithms do not preserve order semantics. |
| `SELECT DISTINCT encrypted_column` | Rejected | Returning distinct ciphertext/helper values is not equivalent to returning distinct business plaintext. |
| `ORDER BY encrypted_column` | Supported with assisted/hash column | Requires `assistedQueryColumn`; same-table fields sort by the assisted/hash column, separate-table fields sort by the main-table reference column, and a warning is logged because the order reflects technical values rather than plaintext semantics. |
| Aggregate functions on encrypted fields except supported `COUNT`, top-level `MAX`, and top-level `FIRST` variants | Rejected | `SUM(phone)`, `AVG(phone)`, `MIN(phone)`, `GROUP_CONCAT(phone)` and similar expressions are not semantically reliable. `MAX` and `FIRST` are only allowed in the outer select list with technical-value warning behavior. |
| Window functions referencing encrypted fields | Rejected | `PARTITION BY`, analytic `ORDER BY`, filter expressions, and named windows fail fast when encrypted fields are involved. |
| Separate-table encrypted field in `IN` subquery projection | Rejected | The plugin currently only supports same-table encrypted field comparison subqueries. |
| Bare `*` in multi-table / derived query that contains encrypted table rules | Rejected | The plugin cannot safely decide which table the wildcard should expand from; use explicit `table.*` or `alias.*` to prevent encrypted alias projections from being covered by later wildcard columns. |

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
