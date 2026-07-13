# Logsafe Guide

[中文](logsafe-guide.zh-CN.md) | [English](logsafe-guide.en.md)

## Who this is for

Read this guide after you have wired encrypted persistence or response masking and now need to keep logs, exception messages, and third-party reporting output from exposing sensitive values.

Recommended reading order:

1. [Quick Start](quick-start.en.md)
2. [Persistence Encryption Guide](persistence-encryption-guide.en.md)
3. [Sensitive Response Guide](sensitive-response-guide.en.md) when HTTP responses need masking
4. this guide when log output needs masking

## What this layer solves

`logsafe` protects logging boundaries:

- application code can wrap objects, fields, or parameters with `SafeLog`
- text already produced by third-party logs, exception messages, gateways, or reporting SDKs can pass through `LogsafeTextMasker`
- Spring Boot starters reuse registered LIKE masking algorithms and attach a terminal Logback filter when Logback is present
- Spring MVC requests can write and clear `traceId` / `requestId`, and async tasks can propagate MDC

Design constraints:

- do not change SQL rewrite, result decryption, or controller response masking
- do not mutate caller-owned objects; return detached log-safe representations instead
- prefer explicit safe logging; the terminal Logback filter is a last safety net
- keep matching conservative so normal business text remains readable

## Core takeaways

1. The Spring Boot 2 and Spring Boot 3 starters already include `logsafe`.
2. Non-Spring applications can depend on `mybatis-like-sharephere-support-logsafe` directly.
3. Prefer `SafeLog.of(value)` or `SafeLog.kv(key, value)` in application logs.
4. `SafeLog.of(obj)` returns a detached masked representation and does not mutate the source object.
5. `@SensitiveField(likeAlgorithm = "...")` lets log masking reuse registered LIKE masking algorithms.
6. `LogsafeTextMasker` is the adapter for third-party logs, exception reporting, gateway logs, and other text boundaries.
7. When Logback is detected, the starters attach a terminal masking filter to existing appenders by default.
8. Disable the terminal filter with `mybatis.encrypt.logsafe.terminal.enabled=false` if it interferes with diagnostics.
9. logsafe MDC writes `traceId` and `requestId` by default; `tenantId`, `userId`, and `clientIp` are opt-in.
10. logsafe is a logging safety net. It does not replace authorization, audit controls, key management, or data classification.

## Dependency entry

### Spring Boot starters

If you already use the Spring Boot 2 or Spring Boot 3 starter, no extra dependency is needed:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>${mybatis-like-sharephere-support.version}</version>
</dependency>
```

For Spring Boot 2, replace the artifactId with `mybatis-like-sharephere-support-spring2-starter`.

Spring Boot auto-configuration creates these beans when their conditions match:

- `LogsafeMasker`
- `SafeLog`
- `LogsafeTextMasker`
- the Logback terminal filter installer
- the Spring MVC trace-context interceptor
- the MDC propagation `TaskDecorator`

### Non-Spring or standalone tools

If you only need log masking, depend on the standalone module:

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-logsafe</artifactId>
  <version>${mybatis-like-sharephere-support.version}</version>
</dependency>
```

Outside Spring, `SafeLog` uses built-in fallback rules. To reuse custom algorithms, create an `AlgorithmRegistry` and `LogsafeMasker`, then install it with `SafeLog.use(masker)`.

## Explicit safe logging

### 1. Key/value logs

For one field, prefer `SafeLog.kv`. It returns a `MaskedLogValue` whose `toString()` is already safe:

```java
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    void login(String phone, String token) {
        log.info("login request {} {}", SafeLog.kv("phone", phone), SafeLog.kv("token", token));
    }
}
```

Example output:

```text
login request phone=*******8000 token=*********
```

Common semantic keys:

| Type | Typical keys |
| --- | --- |
| password / credential | `password`, `passwd`, `pwd`, `token`, `secret`, `cookie`, `authorization` |
| phone | `phone`, `mobile`, `tel` |
| email | `email`, `mail` |
| ID card | `idCard`, `id_card`, `credential`, `cert` |
| bank card | `bankCard`, `bank_card`, `cardNo`, `card_no` |

### 2. Object logs

For DTOs, command objects, maps, or collections, use `SafeLog.of`:

```java
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;

class LoginCommand {

    @SensitiveField(likeAlgorithm = "phoneMaskLike")
    private String phone;

    private String password;
}

log.info("login command={}", SafeLog.of(command));
```

Processing rules:

- `@SensitiveField(likeAlgorithm = "...")` is preferred when present
- unannotated values fall back to field names, map keys, and high-confidence value patterns
- arrays, collections, and maps are processed recursively
- circular references render as `[Circular]`
- the original object is not rewritten

### 3. Explicit semantic hints

When a value has no obvious shape but the caller knows its meaning, pass a `SemanticHint`:

```java
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;
import io.github.jasper.mybatis.encrypt.logsafe.SemanticHint;

log.info("credential={}", SafeLog.of(rawCredential, SemanticHint.of("token")));
```

## Text and exception safety net

`LogsafeTextMasker` is for pre-rendered log text, third-party SDK messages, and exception messages. It handles:

- JSON-style fields such as `"authorization":"Bearer abc.def"`
- `key=value` or `key:value` fragments
- high-confidence phone, email, ID-card, and bank-card values
- exception messages, causes, and suppressed exceptions

```java
import io.github.jasper.mybatis.encrypt.logsafe.LogsafeTextMasker;

class ErrorReporter {

    private final LogsafeTextMasker textMasker;

    ErrorReporter(LogsafeTextMasker textMasker) {
        this.textMasker = textMasker;
    }

    void report(Throwable ex) {
        Throwable masked = textMasker.mask(ex);
        sendToVendor(masked);
    }
}
```

Text masking is a fallback. When application code knows field semantics, prefer `SafeLog.kv` or `SafeLog.of`.

## Logback terminal filter

When a Spring Boot starter detects Logback and `mybatis.encrypt.logsafe.terminal.enabled=true`, it adds `LogsafeLogbackEventFilter` to existing appenders.

The filter attempts to mask:

- the rendered message
- Logback structured key/value pairs
- throwable messages, causes, and suppressed exceptions

Disable it with:

```yaml
mybatis:
  encrypt:
    logsafe:
      terminal:
        enabled: false
```

Notes:

- the filter depends on Logback event internals; if a version is incompatible, it keeps logging alive instead of blocking the application
- the filter only covers content that reaches Logback appenders
- Log4j2, JUL, gateway logs, and external APM SDKs should call `LogsafeTextMasker` explicitly

## MDC request context

In Servlet web applications, the starter registers a trace-context interceptor by default:

- reads `X-Trace-Id` and `X-Request-Id`
- generates ids when headers are missing
- writes MDC keys `traceId` and `requestId`
- restores the previous MDC state when the request completes or async handling starts

Recommended log pattern fields:

```text
%X{traceId} %X{requestId}
```

Configure header names:

```yaml
mybatis:
  encrypt:
    logsafe:
      context:
        header:
          trace-id: X-Correlation-Id
          request-id: X-Request-No
```

Optional MDC keys are disabled by default:

```yaml
mybatis:
  encrypt:
    logsafe:
      context:
        keys:
          tenant-id-enabled: true
          user-id-enabled: true
          client-ip-enabled: true
```

The default contributor only writes values already present in `TraceContext`. If tenant, user, or client-IP values come from a gateway, authentication layer, or custom context, provide your own `TraceIdResolver` or `MdcContributor` bean.

## Async MDC propagation

The starter creates `MdcTaskDecorator` by default. When no other `TaskDecorator` bean exists, it is also exposed as Spring's general `TaskDecorator`.

It captures the current MDC at task submission time, restores it on the worker thread during execution, and then restores the worker thread's previous MDC state.

Disable it with:

```yaml
mybatis:
  encrypt:
    logsafe:
      context:
        propagation:
          async-enabled: false
```

If your application already has a custom `TaskDecorator`, compose `MdcTaskDecorator` into it instead of relying on two independent decorators.

## Configuration reference

| Property | Default | Meaning |
| --- | --- | --- |
| `mybatis.encrypt.logsafe.enabled` | `true` | Master switch. When disabled, `SafeLog`, `LogsafeMasker`, and `LogsafeTextMasker` beans are not created. |
| `mybatis.encrypt.logsafe.terminal.enabled` | `true` | Enables the Logback terminal appender filter. |
| `mybatis.encrypt.logsafe.context.enabled` | `true` | Enables MVC request MDC context. |
| `mybatis.encrypt.logsafe.context.header.trace-id` | `X-Trace-Id` | Incoming trace-id header. |
| `mybatis.encrypt.logsafe.context.header.request-id` | `X-Request-Id` | Incoming request-id header. |
| `mybatis.encrypt.logsafe.context.keys.tenant-id-enabled` | `false` | Writes `tenantId` to MDC. |
| `mybatis.encrypt.logsafe.context.keys.user-id-enabled` | `false` | Writes `userId` to MDC. |
| `mybatis.encrypt.logsafe.context.keys.client-ip-enabled` | `false` | Writes `clientIp` to MDC. |
| `mybatis.encrypt.logsafe.context.propagation.async-enabled` | `true` | Enables async MDC propagation. |

## Relationship to response masking

`logsafe` and controller-boundary response masking are separate runtime paths:

| Capability | Entry point | Target | Mutates business object |
| --- | --- | --- | --- |
| Response masking | `@SensitiveResponse` / `SensitiveResponseBodyAdvice` | HTTP response body | Replaces response fields before writing |
| Log masking | `SafeLog` / `LogsafeTextMasker` / Logback filter | Log arguments, log text, exception messages | `SafeLog` and `LogsafeTextMasker` return detached values or text |

They may reuse `@SensitiveField` and LIKE masking algorithms, but neither opens the other's context or changes the other's behavior.

## Recommendations

- Use `SafeLog.kv` or `SafeLog.of` in application logs.
- Wire `LogsafeTextMasker` into external libraries, gateways, and exception-reporting SDKs.
- Do not manually concatenate plaintext, ciphertext, or key material into exception messages.
- Keep `traceId` / `requestId` in log patterns so diagnostics do not depend on sensitive fields.
- Keep the terminal filter enabled in production; disable it temporarily only for compatibility diagnostics.
- For fields that need exact behavior, annotate them with `@SensitiveField` and set `likeAlgorithm`.

## Known boundaries

- logsafe is not a DLP system and cannot identify every sensitive value.
- Text fallback only handles conservative, high-confidence patterns.
- The Logback terminal filter does not cover outputs that do not reach Logback appenders.
- Simple non-`String` values remain readable by default.
- Custom objects are rendered through reflective field traversal, so avoid logging very large object graphs.
- Log masking does not replace authorization, audited approvals, key management, or log-platform access isolation.
