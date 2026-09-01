# Logsafe 日志脱敏指南

[中文](logsafe-guide.zh-CN.md) | [English](logsafe-guide.en.md)

## 这份文档适合什么时候看

这份文档适合你已经接入字段加密或响应脱敏，现在需要控制日志、异常消息、第三方 SDK 输出里的敏感信息暴露。

建议阅读顺序：

1. 先看 [快速使用指南](quick-start.zh-CN.md)
2. 再看 [持久层加密指南](persistence-encryption-guide.zh-CN.md)
3. 需要接口返回脱敏值时，看 [脱敏响应指南](sensitive-response-guide.zh-CN.md)
4. 需要日志输出脱敏时，再看本文

## 目标

`logsafe` 解决的是日志边界的安全输出问题：

- 业务代码主动记录对象、字段或参数时，可以通过 `SafeLog` 生成可打印的脱敏副本
- 对第三方日志、异常消息、网关日志或异常上报 SDK，可以通过 `LogsafeTextMasker` 做文本级兜底
- Spring Boot starter 会复用已注册的 LIKE 脱敏算法，并在检测到 Logback 时挂载末端 filter
- Spring MVC 请求可以自动写入并清理 `traceId` / `requestId`，异步任务可传播 MDC

设计约束如下：

- 不改变 SQL 改写、结果解密或 controller 响应脱敏链路
- 不修改调用方原始对象，只返回适合写入日志的副本或包装值
- 主动脱敏优先，Logback 末端 filter 只是兜底
- 保持保守匹配，避免把普通业务文本过度替换

## 核心结论

1. Spring Boot 2 和 Spring Boot 3 starter 已经包含 `logsafe` 模块。
2. 非 Spring 场景可以单独引入 `mybatis-like-sharephere-support-logsafe`。
3. 业务日志优先写 `SafeLog.of(value)` 或 `SafeLog.kv(key, value)`。
4. `SafeLog.of(obj)` 返回脱敏后的独立表示，不会修改原对象。
5. `@SensitiveField(likeAlgorithm = "...")` 可以让日志脱敏复用已注册的 LIKE 脱敏算法。
6. `LogsafeTextMasker` 适合接在第三方日志、异常上报、网关日志等文本出口。
7. 检测到 Logback 时，starter 会默认为现有 appender 挂载末端脱敏 filter。
8. 如果末端 filter 影响排查，可用 `mybatis.encrypt.logsafe.terminal.enabled=false` 关闭。
9. `logsafe` MDC 默认写入 `traceId` 和 `requestId`，可按需开启 `tenantId`、`userId`、`clientIp`。
10. `logsafe` 是日志安全网，不替代权限控制、审计、密钥管理或数据分级治理。

## 引入方式

### Spring Boot starter

如果已经使用 Spring Boot 2 或 Spring Boot 3 starter，无需额外引入：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-spring3-starter</artifactId>
  <version>${mybatis-like-sharephere-support.version}</version>
</dependency>
```

Spring Boot 2 项目把 artifactId 换成 `mybatis-like-sharephere-support-spring2-starter`。

Spring Boot 自动装配会在条件满足时创建：

- `LogsafeMasker`
- `SafeLog`
- `LogsafeTextMasker`
- Logback 末端 filter 安装器
- Spring MVC trace context 拦截器
- MDC 异步传播 `TaskDecorator`

### 非 Spring 或独立工具

只需要日志脱敏能力时，可以单独引入：

```xml
<dependency>
  <groupId>io.github.jasperbigsum-commits</groupId>
  <artifactId>mybatis-like-sharephere-support-logsafe</artifactId>
  <version>${mybatis-like-sharephere-support.version}</version>
</dependency>
```

非 Spring 场景下，`SafeLog` 会使用内置兜底规则；如果你要复用自定义算法，需要自行创建 `AlgorithmRegistry` 和 `LogsafeMasker`，再调用 `SafeLog.use(masker)`。

## 主动脱敏

### 1. 记录键值

对单个字段，推荐使用 `SafeLog.kv`。它会返回一个 `MaskedLogValue`，`toString()` 已经是安全文本：

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

示例输出：

```text
login request phone=*******8000 token=*********
```

常见语义键包括：

| 类型 | 典型 key |
| --- | --- |
| 密码 / 凭证 | `password`、`passwd`、`pwd`、`token`、`secret`、`cookie`、`authorization` |
| 手机号 | `phone`、`mobile`、`tel` |
| 邮箱 | `email`、`mail` |
| 证件号 | `idCard`、`id_card`、`credential`、`cert` |
| 银行卡 | `bankCard`、`bank_card`、`cardNo`、`card_no` |

### 2. 记录对象

对 DTO、命令对象、Map 或集合，使用 `SafeLog.of`：

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

处理规则：

- `@SensitiveField(likeAlgorithm = "...")` 优先复用注册算法
- 未标注字段按字段名、Map key 和常见值形态兜底判断
- 数组、集合、Map 会递归处理
- 循环引用会输出 `[Circular]`
- 原对象不会被改写

### 3. 显式语义提示

当值本身没有明显特征，但调用方知道语义时，可以传入 `SemanticHint`：

```java
import io.github.jasper.mybatis.encrypt.logsafe.SafeLog;
import io.github.jasper.mybatis.encrypt.logsafe.SemanticHint;

log.info("credential={}", SafeLog.of(rawCredential, SemanticHint.of("token")));
```

## 文本和异常兜底

`LogsafeTextMasker` 面向已经拼好的日志文本、第三方 SDK 消息和异常消息。它会处理：

- JSON 风格字段，如 `"authorization":"Bearer abc.def"`
- `key=value` 或 `key:value` 风格片段
- 高置信度的手机号、邮箱、身份证号、银行卡号
- 异常消息、cause 和 suppressed exception

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

文本脱敏是兜底能力。业务代码能明确知道字段语义时，仍应优先使用 `SafeLog.kv` 或 `SafeLog.of`。

## Logback 末端 filter

当 Spring Boot starter 检测到 Logback 且 `mybatis.encrypt.logsafe.terminal.enabled=true` 时，会自动给现有 appender 添加 `LogsafeLogbackEventFilter`。

filter 会在输出前尝试脱敏：

- 渲染后的 message
- Logback 结构化 key/value pair
- throwable message、cause 和 suppressed exception

关闭方式：

```yaml
mybatis:
  encrypt:
    logsafe:
      terminal:
        enabled: false
```

注意：

- 末端 filter 依赖 Logback 内部事件结构，遇到不兼容版本时会保持日志继续输出，而不是阻断业务
- 末端 filter 只能处理到达 Logback appender 的内容，不能覆盖 Log4j2、JUL、网关日志或外部 APM SDK
- 非 Logback 出口应显式调用 `LogsafeTextMasker`

## MDC 请求上下文

Servlet Web 应用中，starter 会默认注册 trace context 拦截器：

- 优先读取请求头 `X-Trace-Id` 和 `X-Request-Id`
- 请求头缺失时生成新的 id
- 写入 MDC key：`traceId`、`requestId`
- 请求完成或异步处理开始时恢复原 MDC 状态

推荐在日志 pattern 中加入：

```text
%X{traceId} %X{requestId}
```

可配置请求头：

```yaml
mybatis:
  encrypt:
    logsafe:
      context:
        header:
          trace-id: X-Correlation-Id
          request-id: X-Request-No
```

可选 MDC key 默认关闭，需要显式开启：

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

默认实现只负责写入 `TraceContext` 中已有的值。若租户、用户或客户端 IP 的解析来自网关、认证系统或自定义上下文，可以提供自己的 `TraceIdResolver` 或 `MdcContributor` Bean。

## 异步 MDC 传播

默认会创建 `MdcTaskDecorator`，并在没有其它 `TaskDecorator` Bean 时作为 Spring 通用 `TaskDecorator` 暴露。

它会在提交异步任务时捕获当前 MDC，在任务执行时恢复到工作线程，并在任务结束后恢复工作线程原有状态。

关闭方式：

```yaml
mybatis:
  encrypt:
    logsafe:
      context:
        propagation:
          async-enabled: false
```

如果应用已经有自己的 `TaskDecorator`，可以把 `MdcTaskDecorator` 组合进已有装饰器，而不是让两个装饰器互相覆盖。

## 配置速查

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `mybatis.encrypt.logsafe.enabled` | `true` | 总开关，关闭后不创建 `SafeLog`、`LogsafeMasker`、`LogsafeTextMasker` 等 Bean |
| `mybatis.encrypt.logsafe.terminal.enabled` | `true` | 是否启用 Logback 末端 appender filter |
| `mybatis.encrypt.logsafe.context.enabled` | `true` | 是否启用 MVC 请求 MDC 上下文 |
| `mybatis.encrypt.logsafe.context.header.trace-id` | `X-Trace-Id` | trace id 请求头 |
| `mybatis.encrypt.logsafe.context.header.request-id` | `X-Request-Id` | request id 请求头 |
| `mybatis.encrypt.logsafe.context.keys.tenant-id-enabled` | `false` | 是否写入 `tenantId` |
| `mybatis.encrypt.logsafe.context.keys.user-id-enabled` | `false` | 是否写入 `userId` |
| `mybatis.encrypt.logsafe.context.keys.client-ip-enabled` | `false` | 是否写入 `clientIp` |
| `mybatis.encrypt.logsafe.context.propagation.async-enabled` | `true` | 是否启用异步 MDC 传播 |

## 与响应脱敏的关系

`logsafe` 和 controller 边界响应脱敏是两条独立链路：

| 能力 | 入口 | 作用对象 | 是否改变业务对象 |
| --- | --- | --- | --- |
| 响应脱敏 | `@SensitiveResponse` / `SensitiveResponseBodyAdvice` | HTTP response body | 会在响应写出前替换返回对象字段 |
| 日志脱敏 | `SafeLog` / `LogsafeTextMasker` / Logback filter | 日志参数、日志文本、异常消息 | `SafeLog` 和 `LogsafeTextMasker` 返回副本或文本，不修改源对象 |

两者可以复用 `@SensitiveField` 和 LIKE 脱敏算法，但互不打开上下文，也互不改变对方的行为。

## 使用建议

- 业务日志优先使用 `SafeLog.kv` 或 `SafeLog.of`
- 对外部库、网关、异常上报 SDK，显式接入 `LogsafeTextMasker`
- 不要在异常消息中主动拼接明文、密文或密钥材料
- 日志 pattern 中保留 `traceId` / `requestId`，方便排查问题时不依赖敏感字段
- 生产环境保留末端 filter，压测或排查兼容问题时可临时关闭
- 对需要精确策略的字段，优先加 `@SensitiveField` 并指定 `likeAlgorithm`

## 已知边界

- `logsafe` 不是 DLP 系统，不能保证识别所有敏感信息
- 文本兜底只处理保守、高置信度的模式
- 末端 Logback filter 不覆盖未进入 Logback appender 的日志出口
- 非 `String` 的简单值默认保持可读
- 自定义对象会通过字段反射生成脱敏表示，不建议把超大对象图直接写入日志
- 日志脱敏不能替代访问控制、审计审批、密钥管理和日志平台权限隔离
