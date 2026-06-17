# Sensitive Response Guide

[中文](sensitive-response-guide.zh-CN.md) | [English](sensitive-response-guide.en.md)

## Who this is for

Read this guide when you already understand encrypted writes and decrypted reads, and now need to decide what the final HTTP response is allowed to expose.

Recommended reading order:

1. [Quick Start](quick-start.en.md)
2. [Persistence Encryption Guide](persistence-encryption-guide.en.md)
3. this guide when you need controller-boundary masking
4. [Architecture](architecture.md) when you want the full runtime layering

## What this layer solves

The persistence layer decrypts ciphertext back into plaintext objects for trusted business code.

The sensitive-response layer answers a different question:

- should the caller receive plaintext or a masked value?
- should the response also carry controlled lookup metadata for later explicit plaintext retrieval?

Design constraints:

- do not change the existing SQL rewrite or decryption behavior
- keep masking at the controller boundary, not inside services or mappers
- prefer stored `maskedColumn` values over rebuilding display strings at response time
- support the same behavior for same-table and separate-table encryption
- keep lookup metadata best-effort so metadata misses never break normal masking

## Core takeaways

1. Query results are still decrypted to plaintext first.
2. `@SensitiveResponse` decides whether the final response should remain plaintext or be masked.
3. `@SensitiveResponseTrigger` only consumes an already-open masking context; it never opens one by itself.
4. Stored `maskedColumn` values are preferred, then `maskedAlgorithm`, then `@SensitiveField`.
5. If the response DTO extends `SensitiveExtraInfoSupport`, masked fields may also return `sensitiveLookupMeta`.
6. `sensitiveLookupMeta` is a map keyed by property name, and each value contains `sid`, `pid`, `vid`, and `hash`.
7. Explicit plaintext retrieval is provided by `SensitivePlaintextLookupService.lookup(...)`.
8. Plaintext lookup auditing is delegated to `SensitivePlaintextAuditRecorder`, whose default implementation is a no-op.
9. For edit-form submission, the recommended frontend mode is `sensitiveSubmitMode=meta`; save endpoints must opt in with `@SensitiveRequestHydration` on the controller method or class before the backend restores unchanged masked fields to plaintext before controller argument binding.
10. Request-side hydration uses the internal `lookupInternal(...)` path, which is framework-only and does not trigger audit events.

## Runtime layers

### 1. Metadata layer

- `@EncryptField`
  declares `column`, `storageColumn`, `assistedQueryColumn`, `likeQueryColumn`, `maskedColumn`, and lookup-meta attributes
- `EncryptMetadataRegistry`
  merges annotation and property-driven rules and resolves lookup-meta defaults

### 2. Decryption layer

- `DatabaseEncryptionInterceptor`
- `ResultDecryptor`
- `QueryResultPlanFactory`
- `SeparateTableEncryptionManager`

Responsibilities:

- rewrite supported encrypted SQL
- decrypt mapped fields back into the DTO
- record decrypted object references and best-effort lookup metadata in `SensitiveDataContext`

### 3. Response masking layer

- `SensitiveRequestBodyAdvice`
  consumes frontend sensitive-submit metadata before controller argument binding, only when the controller method or class is annotated with `@SensitiveRequestHydration`
- `SensitiveResponseContextInterceptor`
- `SensitiveResponseTriggerAspect`
- `SensitiveResponseBodyAdvice`
- `SensitiveDataMasker`
- `SensitiveExtraInfoSupport`
- `StoredSensitiveValueResolver`
- `JdbcStoredSensitiveValueResolver`

Responsibilities:

- restore sensitive request fields from JSON or `application/x-www-form-urlencoded` `sensitiveSubmitMeta` payloads, or legacy sensitive-input objects, before controller binding
- open one request-scoped masking context at the controller boundary
- reuse that context inside `@SensitiveResponseTrigger`
- replace the final response value before it is written
- attach response lookup metadata to DTOs that opt in through `SensitiveExtraInfoSupport`
- provide a no-audit plaintext conversion path for internal request hydration

### 4. Explicit plaintext lookup layer

- `SensitivePlaintextLookupService`
- `SensitivePlaintextAuditRecorder`

Responsibilities:

- resolve plaintext from response lookup metadata
- support both same-table and separate-table encrypted fields
- provide an audit hook for success and failure events

## Response lookup metadata

## Frontend Submit Contract And Request Hydration

Request hydration is an explicit opt-in capability, not a global controller behavior. Add `@SensitiveRequestHydration` to the save method or controller class before relying on automatic request rewriting:

```java
@SensitiveRequestHydration
@PostMapping("/users")
public void save(@RequestBody UserSaveRequest request) {
    userService.save(request);
}
```

The built-in request advice supports `application/json` and `application/x-www-form-urlencoded`. Multipart forms are outside the current automatic hydration scope.

### Preferred shape: `sensitiveSubmitMeta`

When an edit form is hydrated from a masked response, the frontend may initialize `JSensitiveInput` from `sensitiveLookupMeta`. On submit, unchanged sensitive fields should be removed from the normal payload and sent under `sensitiveSubmitMeta`:

```json
{
  "name": "Alice",
  "sensitiveSubmitMeta": {
    "phone": {
      "sid": "user_account",
      "pid": "phone",
      "vid": "U-100",
      "hash": "7f8c...",
      "state": "unchangedMasked"
    }
  }
}
```

After the endpoint opts in with `@SensitiveRequestHydration`, before Spring MVC binds the controller argument, the backend:

1. validates that `sid`, `pid`, `vid`, and `hash` are present
2. calls `SensitivePlaintextLookupService.lookupInternal(...)`
3. writes the resolved plaintext back to the original field such as `phone`
4. removes `sensitiveSubmitMeta` from the JSON body

The controller DTO can keep its original `String` field:

```java
public class UserSaveRequest {

    private String phone;
}
```

For `application/x-www-form-urlencoded`, use the same bracketed field structure:

```text
name=Alice&sensitiveSubmitMeta[phone][sid]=user_account&sensitiveSubmitMeta[phone][pid]=phone&sensitiveSubmitMeta[phone][vid]=U-100&sensitiveSubmitMeta[phone][hash]=7f8c...&sensitiveSubmitMeta[phone][state]=unchangedMasked
```

After internal hydration, controller binding sees the equivalent of `name=Alice&phone=<plaintext>`.

### Compatibility shape: field object mode

If a non-standard path submits the `JSensitiveInput` object directly, the backend accepts it as a fallback:

```json
{
  "phone": {
    "value": "138****8000",
    "maskedValue": "138****8000",
    "lookupMeta": {
      "sid": "user_account",
      "pid": "phone",
      "vid": "U-100",
      "hash": "7f8c..."
    },
    "state": "masked"
  }
}
```

Rules:

| Frontend state | Backend behavior |
| --- | --- |
| `changed` | uses the current `value` as new plaintext and does not use the old `lookupMeta` |
| `masked` / `revealed` | uses `lookupMeta` to resolve plaintext and replace the original field |
| both `sensitiveSubmitMeta` and object mode appear for the same field | `sensitiveSubmitMeta` wins |

### Shape not recommended for automatic hydration: `omit`

Some historical `BasicForm` configurations may default to `sensitiveSubmitMode=omit`. In that mode, unchanged sensitive fields are removed and no lookup metadata is submitted:

```json
{
  "name": "Alice"
}
```

That shape is only suitable for endpoints that do not need the backend to restore unchanged sensitive fields. For edit-save flows, overwrite-style updates, or controllers that expect complete plaintext DTO fields, use `sensitiveSubmitMode=meta`.

### Frontend rules

- Standard `BasicForm` / `JSensitiveInput` submissions should set `sensitiveSubmitMode="meta"`.
- If the user changes a sensitive field, submit the field itself as a plaintext string.
- If the user does not change a sensitive field, remove the field itself and submit `sensitiveSubmitMeta[field]`.
- Do not send response-side `sensitiveLookupMeta` back to save endpoints as-is; save endpoints use `sensitiveSubmitMeta`.
- Direct field-object submission is accepted only as a compatibility fallback, not the preferred protocol.

### Vue / JEECG `JSensitiveInput` Integration Example

The `crm-vue`-style frontend implementation now lives in [Vue `JSensitiveInput` Example](examples/sensitive-vue-jsensitive-input.en.md). The copyable source is kept in the Chinese example and includes `JSensitiveInput.vue`, `transform.ts`, plain form usage, JEECG `BasicForm` integration points, and `application/x-www-form-urlencoded` submit handling.

The core contract remains:

- The reveal icon is an explicit plaintext-view action. It should call a controlled endpoint such as `/sensitive/plaintext/lookup`, where the application can perform authorization and audit.
- After the save endpoint is annotated with `@SensitiveRequestHydration`, requests that contain `sensitiveSubmitMeta` are hydrated internally by `SensitiveRequestBodyAdvice`. This conversion does not return plaintext to the caller and does not trigger plaintext-view auditing.
- `sensitiveLookupMeta` is response-side metadata for component hydration. Do not send it back to save endpoints as-is.
- If a page does not use centralized submit preprocessing, directly submitting the `JSensitiveInput` object is still accepted as a compatibility fallback. New pages should prefer `sensitiveSubmitMode='meta'`.

### DTO opt-in

To return response lookup metadata, the response DTO must extend `SensitiveExtraInfoSupport`:

```java
public class UserView extends SensitiveExtraInfoSupport {

    private String phone;
    private String idCard;
}
```

The runtime writes a property-keyed map into `getSensitiveLookupMeta()`:

```json
{
  "phone": "138****8000",
  "sensitiveLookupMeta": {
    "phone": {
      "sid": "sid_xxx",
      "pid": "pid_xxx",
      "vid": "U-100",
      "hash": "7f8c..."
    }
  }
}
```

Field meanings:

- `sid`: source identifier
- `pid`: property identifier
- `vid`: business-key value used to locate the owning record
- `hash`: assisted-query hash for the sensitive field

### When metadata is attached

Metadata is attached only when all of these are true:

1. the response object extends `SensitiveExtraInfoSupport`
2. the field was actually replaced during masking
3. the response field did not explicitly opt out
4. `sid`, `pid`, `vid`, and `hash` were all resolved successfully

Best-effort rule:

- if lookup metadata cannot be resolved, decryption and masking still succeed
- `getSensitiveLookupMeta()` returns `null` when the map is empty

## `@EncryptField` attributes for lookup metadata resolution

The response-layer lookup metadata feature uses these persistence-side attributes for resolution:

| Attribute | Default behavior | Purpose |
| --- | --- | --- |
| `sidCode` | stable table-level default | custom source identifier |
| `pidCode` | stable table + property default | custom property identifier |
| `lookupBusinessKey` | inferred from entity id metadata or `id` | business-key property used to resolve `vid` |

Example:

```java
@EncryptField(
        column = "phone",
        storageColumn = "phone_cipher",
        assistedQueryColumn = "phone_hash",
        maskedColumn = "phone_masked",
        sidCode = "user_phone_sid",
        pidCode = "user_phone_pid",
        lookupBusinessKey = "tenantId"
)
private String phone;
```

Resolution rules:

- explicit `lookupBusinessKey` wins
- otherwise the registry tries `@TableId`, JPA `@Id`, then a field named `id`
- for config-only table rules without entity preloading, the fallback stays conservative and uses `id`

## `@SensitiveField` controls whether metadata is returned

Whether lookup metadata is returned to the caller is now a response-side decision:

| Attribute | Default behavior | Purpose |
| --- | --- | --- |
| `returnLookupMeta` | `true` | allow or suppress lookup metadata for this response field |

Rules:

- once a field is actually masked, metadata is attached by default
- `@SensitiveField(returnLookupMeta = false)` suppresses metadata for that response field
- if a field has no `@SensitiveField` annotation but was still masked through the recorded/stored-value path, metadata is still attached by default
- this keeps the “return metadata or not” choice inside the concrete response DTO instead of the persistence rule

## Explicit plaintext lookup

The explicit lookup contract is:

```java
public interface SensitivePlaintextLookupService {

    String lookup(SensitiveLookupMeta lookupMeta);

    String lookupInternal(SensitiveLookupMeta lookupMeta);
}
```

Typical usage:

```java
String plaintext = sensitivePlaintextLookupService.lookup(meta);
```

Current behavior:

- supports same-table encrypted fields
- supports separate-table encrypted fields
- validates the main-table row by `vid + hash` before decrypting
- decrypts the resolved ciphertext with the field's configured cipher algorithm

In the built-in default implementation, `lookupInternal(...)` uses the same query logic but skips audit recording. It is intended for request-body hydration only. If a custom `SensitivePlaintextLookupService` records audit events inside `lookup(...)`, it should override `lookupInternal(...)` as well so internal request hydration stays non-audited.

Current boundaries:

- incomplete `sid / pid / vid / hash` fails fast
- unmatched rules fail fast
- multiple datasource routing is not supported yet
- the intended scope is a single-entity, one-to-one lookup path

## Audit hook

The starter exposes this interface:

```java
public interface SensitivePlaintextAuditRecorder {

    void record(SensitivePlaintextAuditEvent event);
}
```

Default behavior:

- the auto-configuration registers a no-op implementation

Built-in event fields:

- `tableName`: normalized physical table name resolved from `sid`
- `propertyName`: entity property name resolved from `pid`
- `columnName`: business column name resolved from `pid`
- `lookupMeta`: original `sid / pid / vid / hash`
- `success`: success or failure status
- `plaintext`: plaintext returned by a successful lookup
- `errorCode`: stable error code for failed lookup
- `attributes`: custom application attributes passed to `lookup(meta, attributes)`

Pass caller, tenant, ticket, permission source, trace id, or other application context from the business code that triggers plaintext lookup by calling `lookup(meta, attributes)`. `SensitivePlaintextAuditRecorder` only receives the final event and persists or reports it.

## `@SensitiveResponse` strategies

`@SensitiveResponse.strategy()` supports three modes:

- `RECORDED_ONLY`
  only mask fields that were actually decrypted and recorded
- `ANNOTATED_FIELDS`
  walk the returned object graph and only use `@SensitiveField`
- `RECORDED_THEN_ANNOTATED`
  process recorded fields first, then fill remaining annotated fields

Recommended defaults:

- normal query endpoints: `RECORDED_ONLY`
- manually assembled DTOs: `ANNOTATED_FIELDS`
- mixed return graphs: `RECORDED_THEN_ANNOTATED`

## Usage patterns

### Pattern A: standard query endpoint

Use when:

- the response object is the original MyBatis result object
- SQL projection is straightforward

Do this:

1. configure `maskedColumn`
2. annotate the controller with `@SensitiveResponse`
3. keep the default `RECORDED_ONLY` strategy

### Pattern B: manually assembled DTO with masking only

Use when:

- the DTO is not the original decrypted result object
- you only need final display masking

Do this:

1. add `@SensitiveField` to output fields
2. use `@SensitiveResponse(strategy = ANNOTATED_FIELDS)` or `RECORDED_THEN_ANNOTATED`

### Pattern C: masked response plus lookup metadata

Use when:

- the caller must receive masked data
- a downstream controlled system also needs a lookup token for later authorized plaintext retrieval

Do this:

1. extend `SensitiveExtraInfoSupport`
2. keep response masking enabled
3. leave `returnLookupMeta = true` only on fields that are allowed to emit metadata
   Or set `@SensitiveField(returnLookupMeta = false)` on the fields that must opt out.

### Pattern D: explicit plaintext retrieval

Use when:

- the application has already received `sensitiveLookupMeta`
- authorization and audit checks happen before real plaintext is revealed

Do this:

1. inject `SensitivePlaintextLookupService`
2. call `lookup(meta)` explicitly
3. override `SensitivePlaintextAuditRecorder` if your environment requires persistent audit trails

## Boundaries and recommendations

- treat `sensitiveLookupMeta` as an index payload, not as sensitive plaintext
- keep `lookup(...)` explicit and audited
- use `lookupInternal(...)` only for framework-side request hydration
- do not rely on lookup metadata for unsupported many-to-one or many-to-many retrievals
- if you need multi-datasource lookup routing, extend the rule model first instead of guessing at runtime
- if a field is display-only, set `@SensitiveField(returnLookupMeta = false)` on the response DTO field
