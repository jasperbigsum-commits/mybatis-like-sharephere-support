# SQL Support Matrix

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
