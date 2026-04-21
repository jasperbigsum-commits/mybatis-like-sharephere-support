# Migration Production Runbook

[中文](migration-production-runbook.zh-CN.md) | [English](migration-production-runbook.en.md)

## When to read this

Read this document when the migration design, DDL, and confirmation strategy are already prepared and the team is entering a production change window.

If you are still designing the migration itself, start with:

1. [Migration Guide](migration-guide.en.md)
2. [Migration Cursor Design Guide](migration-cursor-design.en.md)
3. then return here and execute the checklist

## How to use this document

This document is written as an operations runbook, not a concept guide.

Recommended usage:

- review it jointly with engineering, DBA, and operations before rollout
- execute it as a step-by-step checklist
- jump directly to failure handling when the task stops or reports an error

## Role ownership

### Application engineer

Owns:

- the final entity rules, cursor definition, and backup strategy
- generated DDL, mutation manifests, and migration commands
- explanation of error codes and post-failure recovery steps

Must confirm before rollout:

- every overwrite-style field was reviewed for `backupColumn(...)`
- `MigrationSchemaSqlGenerator` output was reviewed manually
- canary `batchSize`, `verifyAfterWrite`, and checkpoint directory are fixed
- it is clear whether resume is allowed and who may keep or remove state files

### DBA

Owns:

- applying or reviewing DDL
- checking schema shape, indexes, lock risk, and maintenance window impact
- validating main-table and separate-table consistency after failures

Must confirm before rollout:

- all add-column / create-table / modify-column SQL has already been applied
- indexes, unique constraints, and external-table key strategy match the design
- the batch update will not collide with peak business traffic
- verification SQL is ready for before/after sampling

### Release or operations owner

Owns:

- the change window
- single-instance execution control
- retention of logs, checkpoints, confirmation files, and execution reports

Must confirm before rollout:

- commands, environment variables, and datasource targets are correct
- no concurrent instance of the same migration task exists
- the checkpoint directory is persistent and will not be cleaned automatically
- pause / recovery ownership is already agreed on

## Pre-execution checklist

Verify all of the following:

- the target environment is the correct datasource, not a test or shadow database
- the entity / table / field scope for this rollout is frozen
- DDL has already been applied and checked
- the confirmation file or expected mutation scope has been reviewed
- the checkpoint directory is empty, or an existing state file was explicitly approved for reuse
- all overwrite-style fields still have recoverable plaintext
- the first production batch uses a canary size, not a full-size batch
- `verifyAfterWrite` is enabled
- escalation contacts, fallback data sources, and the operator channel are ready

## First execution checklist

Recommended order:

1. start with a canary batch, not the full rollout
2. watch batch reads, updates, verification, and commit behavior in the logs
3. review `MigrationReport.migratedRows`, `skippedRows`, and `verifiedRows`
4. sample-check main-table and separate-table results
5. scale up only after the first run is clean

During the canary batch, at minimum sample-check:

- same-table `storageColumn`, `assistedQueryColumn`, and `likeQueryColumn`
- separate-table main-row reference values plus external hash/cipher/like values
- backup-column writes for overwrite-style fields
- checkpoint advancement to the latest committed cursor

## Mandatory pause conditions

Pause the rollout immediately if any of the following happens:

- `PLAINTEXT_UNRECOVERABLE` appears
- repeated `VERIFICATION_VALUE_MISMATCH` errors appear
- the separate table starts showing duplicate references, orphan rows, or main/external inconsistency
- the checkpoint stops advancing while the task appears to keep retrying
- business monitoring shows lock amplification, slow SQL spikes, or main-path alarms

After a pause, do not:

- delete checkpoint files first
- start multiple replacement instances to "push through"
- keep treating already-overwritten source values as if they were still plaintext

## Failure-handling procedure

### General recovery principles

After an interrupted migration, prefer fixing the root cause and rerunning the same task. Do not delete checkpoints first.

Resume requires:

- the same datasource
- the same entity/table and cursor columns
- the same checkpoint directory
- the same field scope and backup strategy

If committed batches already exist, the next run continues after the committed checkpoint.
If the failure happened inside a transaction, the uncommitted batch rolls back and is processed again.

The same applies to one-click batch migration. `executeAllRegisteredTables()` keeps state per table; on a second run, completed tables whose target state is still valid are not rewritten, while incomplete tables are compensated idempotently.

### `CHECKPOINT_LOCKED`

Meaning:

- another instance of the same migration task is already running

Action:

1. confirm whether another instance is active
2. keep the current state files untouched
3. stop duplicate instances and keep exactly one execution path

### `VERIFICATION_VALUE_MISMATCH`

Meaning:

- post-write verification found that the stored target state does not match the expected derived values

Action:

1. pause the task
2. sample-check failed rows in the main table, separate table, and backup columns
3. inspect algorithm configuration, field mapping, and concurrent business writes
4. resume from the existing checkpoint only after the inconsistency is understood and fixed

### `PLAINTEXT_UNRECOVERABLE`

Meaning:

- the source column was already partially overwritten by a previous migration attempt and the original plaintext can no longer be reconstructed from backup data

Action:

1. stop reruns immediately
2. restore original plaintext from backups, history tables, or audit data
3. repair incomplete derived columns or separate-table rows
4. add or fix backup strategy before trying again

This is a hard-stop error. Repeating the same run is not a valid recovery method.

### Recovery from backup columns

When an overwrite-style field is configured with `backupColumn(...)`, the migrator automatically reads original plaintext from the backup column first.

This applies when:

- the source column was already replaced by hash, like, cipher, or a separate-table reference value
- the backup column still contains the original plaintext
- ciphertext, like/hash columns, or separate-table rows are missing and need compensation

Procedure:

1. verify that the backup column still contains original plaintext
2. keep `backupColumn(...)` in the migration definition
3. rerun the same migration task

No separate recovery API is required.

## Post-rollout verification checklist

After completion, verify at least:

- `MigrationReport.status == COMPLETED`
- `verifiedRows` matches expectations
- key field samples pass
- API smoke checks show no plaintext/ciphertext mismatch
- checkpoints, confirmation files, and logs are archived
- failure batches and manual repairs are documented

## Explicitly forbidden actions

The following actions are high-risk and should be treated as operational errors:

- running the production migration before DDL review
- repeatedly rerunning overwrite-style fields without a backup strategy
- manually editing checkpoint cursor values to skip bad rows
- running the same migration task concurrently in multiple instances
- treating `verifyAfterWrite(true)` as a dry run
- deleting state files before the root cause is understood

## Related documents

- [Migration Guide](migration-guide.en.md)
- [Migration Cursor Design Guide](migration-cursor-design.en.md)
