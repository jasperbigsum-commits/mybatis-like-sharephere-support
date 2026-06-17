# Sensitive Request Plaintext Hydration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add request-side sensitive field hydration so Spring MVC controllers can keep existing DTO signatures while `sensitiveSubmitMeta` payloads and legacy sensitive-input objects are converted to plaintext before binding.

**Architecture:** Add a shared JSON payload rewriter that detects `sensitiveSubmitMeta` and legacy sensitive input object shapes, resolves unchanged masked fields through `SensitivePlaintextLookupService`, rewrites the request body JSON in place, and strips helper metadata before Jackson binds controller DTOs. Wire thin `RequestBodyAdvice` adapters in both Spring Boot 2 and Spring Boot 3 starters so servlet-platform differences stay localized.

**Tech Stack:** Java 8/17, Spring MVC `RequestBodyAdvice`, Jackson `ObjectMapper`, existing `SensitivePlaintextLookupService`, Spring Boot auto-configuration tests, Spring MVC integration tests.

---

### Task 1: Lock Down Request Rewriter Expectations

**Files:**
- Create: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring-support\src\test\java\io\github\jasper\mybatis\encrypt\web\SensitiveRequestPayloadResolverTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void shouldResolveSensitiveSubmitMetaIntoPlaintextFields() {
    String body = "{\"name\":\"Alice\",\"sensitiveSubmitMeta\":{\"phone\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-1\",\"hash\":\"HASH\",\"state\":\"unchangedMasked\"}}}";
    RecordingLookupService lookup = new RecordingLookupService("13800138000");

    String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup).rewrite(body, StandardCharsets.UTF_8);

    assertTrue(rewritten.contains("\"phone\":\"13800138000\""));
    assertFalse(rewritten.contains("sensitiveSubmitMeta"));
    assertEquals(1, lookup.invocations);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -am "-Dtest=SensitiveRequestPayloadResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because `SensitiveRequestPayloadResolver` does not exist.

- [ ] **Step 3: Add object-mode coverage before implementation**

```java
@Test
void shouldRewriteLegacySensitiveInputObjectToPlaintextValue() {
    String body = "{\"phone\":{\"value\":\"138****8000\",\"maskedValue\":\"138****8000\",\"lookupMeta\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-2\",\"hash\":\"HASH-2\"},\"state\":\"masked\"}}";
    RecordingLookupService lookup = new RecordingLookupService("13800138001");

    String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup).rewrite(body, StandardCharsets.UTF_8);

    assertTrue(rewritten.contains("\"phone\":\"13800138001\""));
    assertEquals(1, lookup.invocations);
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -am "-Dtest=SensitiveRequestPayloadResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because the request resolver is still missing.

### Task 2: Implement Shared Request Payload Rewriter

**Files:**
- Create: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring-support\src\main\java\io\github\jasper\mybatis\encrypt\web\SensitiveRequestPayloadResolver.java`
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\docs\architecture.md`

- [ ] **Step 1: Write minimal implementation**

```java
public final class SensitiveRequestPayloadResolver {

    private final ObjectMapper objectMapper;
    private final SensitivePlaintextLookupService lookupService;

    public String rewrite(String body, Charset charset) {
        JsonNode root = objectMapper.readTree(body);
        // resolve sensitiveSubmitMeta entries first
        // then rewrite legacy object-mode fields
        // remove helper metadata key
        return objectMapper.writeValueAsString(root);
    }
}
```

- [ ] **Step 2: Run shared resolver tests**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -am "-Dtest=SensitiveRequestPayloadResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 3: Document the request-side execution flow**

Update `docs/architecture.md` to mention request preprocessing before controller binding and the precedence order: `sensitiveSubmitMeta` first, legacy object mode second.

### Task 3: Wire Spring Boot 3 MVC Request Advice

**Files:**
- Create: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring3-starter\src\main\java\io\github\jasper\mybatis\encrypt\web\SensitiveRequestBodyAdvice.java`
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring3-starter\src\main\java\io\github\jasper\mybatis\encrypt\config\SensitiveResponseAutoConfiguration.java`
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring3-starter\src\test\java\io\github\jasper\mybatis\encrypt\web\SensitiveResponseWebTest.java`

- [ ] **Step 1: Write failing Spring Boot 3 web test**

```java
@Test
void shouldRewriteSensitiveSubmitMetaBeforeControllerBinding() {
    // build advice with resolver and mock lookup service
    // wrap JSON body that omits `phone` and includes `sensitiveSubmitMeta`
    // assert resulting request payload binds `phone` as plaintext string
}
```

- [ ] **Step 2: Run Spring Boot 3 web test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am "-Dtest=SensitiveResponseWebTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because request advice is not registered yet.

- [ ] **Step 3: Implement the Boot 3 advice and bean wiring**

```java
@ControllerAdvice
public class SensitiveRequestBodyAdvice extends RequestBodyAdviceAdapter {
    // supports JSON request bodies when lookup service exists
    // delegates payload rewrite to SensitiveRequestPayloadResolver
}
```

- [ ] **Step 4: Re-run Spring Boot 3 tests**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am "-Dtest=SensitiveResponseWebTest,SensitiveMaskingAutoConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

### Task 4: Mirror Spring Boot 2 MVC Request Advice

**Files:**
- Create: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring2-starter\src\main\java\io\github\jasper\mybatis\encrypt\web\SensitiveRequestBodyAdvice.java`
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring2-starter\src\main\java\io\github\jasper\mybatis\encrypt\config\SensitiveResponseAutoConfiguration.java`
- Create: `E:\IdeaProject\mybatis-like-sharephere-support\spring-starter\spring2-starter\src\test\java\io\github\jasper\mybatis\encrypt\web\SensitiveRequestBodyAdviceTest.java`

- [ ] **Step 1: Write the failing Boot 2 parity test**

```java
@Test
void shouldRewriteLegacySensitiveObjectBeforeSpring2Binding() {
    // create advice with resolver and lookup stub
    // feed object-mode JSON body
    // assert rewritten JSON contains plaintext string field
}
```

- [ ] **Step 2: Run Spring Boot 2 test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring2-starter -am "-Dtest=SensitiveRequestBodyAdviceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because the advice class is missing.

- [ ] **Step 3: Implement the Boot 2 advice and bean wiring**

```java
@ControllerAdvice
public class SensitiveRequestBodyAdvice extends RequestBodyAdviceAdapter {
    // same behavior as Boot 3, with javax imports
}
```

- [ ] **Step 4: Re-run Spring Boot 2 tests**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring2-starter -am "-Dtest=SensitiveRequestBodyAdviceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

### Task 5: Update User-Facing Docs And Final Verification

**Files:**
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\docs\sensitive-response-guide.zh-CN.md`
- Modify: `E:\IdeaProject\mybatis-like-sharephere-support\docs\architecture.md`

- [ ] **Step 1: Document request payload contract**

Add a short section describing:
- `sensitiveSubmitMeta` is the preferred submit shape
- unchanged masked fields are rehydrated to plaintext before controller binding
- legacy object payloads are still accepted as compatibility fallback

- [ ] **Step 2: Run focused acceptance verification**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am "-Dtest=SensitiveResponseWebTest,SensitiveMaskingAutoConfigurationTest,SensitiveLookupMetaAutoConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 3: Run Spring Boot 2 parity verification**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring2-starter -am "-Dtest=SensitiveRequestBodyAdviceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS
