# Migration Cursor Design Guide

[中文](migration-cursor-design.zh-CN.md) | [English](migration-cursor-design.en.md)

## Goal

The migration module uses cursor-based seek pagination to scan source rows in batches. Cursor design directly affects:

- whether migration skips rows
- whether resume can continue safely
- whether post-write verification can locate the main-table row again
- whether non-`id` cursor bindings remain stable across JDBC drivers

This document explains cursor design principles, recommended patterns, anti-patterns, and troubleshooting.

## Required Properties

A cursor column or cursor set should be:

- stable: not updated by business logic, triggers, or the migration itself
- sortable: the database can apply a deterministic ascending seek order
- re-readable: the same cursor values can locate the row during verification
- unique enough: if one column is not unique, promote it to a composite cursor

If one cursor column matches any main-table write target during migration, plan creation now fails fast with:

- `CURSOR_COLUMN_MUTABLE`

## Recommended Order

Preferred cursor order:

1. primary key `id`
2. one immutable business unique key such as `record_no`
3. a composite cursor such as `created_at + id`
4. a multi-tenant composite cursor such as `tenant_id + biz_no`

## Recommended Examples

### 1. Single-column primary-key table

The safest case:

```yaml
mybatis:
  encrypt:
    migration:
      default-cursor-columns:
        - id
```

### 2. Single-column business unique key

If the table has no technical primary key but does have one immutable unique business key:

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "user_account"
          cursor-columns:
            - record_no
```

Use this only when:

- `record_no` is unique
- `record_no` is immutable
- migration never writes that column

### 3. Timestamp is not unique enough

Using only `created_at` is usually unsafe because multiple rows may share the same timestamp. Prefer:

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "order_account"
          cursor-columns:
            - created_at
            - id
```

### 4. Multi-tenant business table

If row identity depends on tenant scope, prefer:

```yaml
mybatis:
  encrypt:
    migration:
      cursor-rules:
        - table-pattern: "order_*"
          cursor-columns:
            - tenant_id
            - biz_no
```

If `biz_no` is still not unique enough, append one more column:

```yaml
cursor-columns:
  - tenant_id
  - biz_no
  - id
```

## Fields That Should Not Be Used As Cursors

These fields should not be used as cursors and may now fail fast:

- source columns that will be overwritten, such as `phone` or `id_card`
- derived write targets such as `phone_hash`, `phone_like`, or any `storageColumn`
- separate-table reference write-back columns
- mutable business fields such as status, ordering, ranking, or display-name columns
- plain string identifiers that are compared lexicographically while business meaning is numeric, such as non-zero-padded `order_no`

## Why A Non-Unique Single Cursor Can Skip Rows

The migration module uses seek pagination, not offset pagination. For a single cursor `record_no`, the next batch usually means:

```sql
where record_no > ?
order by record_no asc
limit ?
```

If the table contains:

- `record_no = 100, id = 1`
- `record_no = 100, id = 2`

After the first row is processed, the next query becomes `record_no > 100`, so the second row is skipped.

Therefore:

- if one cursor column is not unique enough, always promote it to a composite cursor

## JDBC Type Pitfalls For Non-`id` Cursors

Even if the same SQL with the same visible value works manually in the database, JDBC parameter binding may still behave differently.

High-risk cursor types include:

- `timestamp` / `datetime`
- `decimal` / `number`
- `char` / fixed-length strings
- business string identifiers

The migration module now uses type-aware binding instead of blindly relying on `setObject(...)`, but it is still recommended to:

- prefer `id`
- combine timestamps with one unique key
- confirm that business string identifiers sort and compare exactly as expected

## Debug Troubleshooting

Enable `debug` logging and focus on:

- `migration-read-batch`
- `migration-load-current-row`
- `migration-update-main-row`
- `migration-verify-main-row`

These log entries include:

- the SQL
- cursor values
- Java types for each cursor value

They help confirm:

- whether an old checkpoint has already moved past the row
- whether JDBC binding types match the database column semantics
- whether verification still uses the expected cursor values

## Recommended Configuration Template

One practical template:

```yaml
mybatis:
  encrypt:
    migration:
      default-cursor-columns:
        - id
      cursor-rules:
        - table-pattern: "user_account"
          cursor-columns:
            - record_no
        - table-pattern: "order_*"
          cursor-columns:
            - tenant_id
            - biz_no
            - id
      checkpoint-directory: migration-state
      batch-size: 500
      verify-after-write: true
```

## Summary

The cursor design rules are simple:

- use `id` whenever possible
- if one column is not unique enough, use a composite cursor
- do not use mutable columns
- timestamps almost always need one unique tie-breaker
- when in doubt, inspect checkpoints and debug logs first
