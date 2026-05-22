# EncryptJsonField Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JSON string sub-path encryption with `@EncryptJsonField` / `@EncryptJsonPath`, including metadata loading, write-time hash replacement, precise `json_extract` query rewrite, result decryption, migration, and documentation.

**Architecture:** Keep the feature in `common` as a framework-agnostic runtime capability, modeled as JSON-field metadata plus path-level separate-table bindings. Reuse the existing assisted-hash, rewrite, result-decrypt, and migration pipelines where possible, but add fail-fast handling for unsupported JSON update/query shapes.

**Tech Stack:** Java 8, MyBatis, JSqlParser, existing assisted/cipher algorithm SPI, migration JDBC pipeline, JUnit 5, H2, repository-local Maven cache.

---

### Task 1: Write the spec-backed failing metadata tests

**Files:**
- Create: `common/src/test/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonMetadataTest.java`
- Modify: `common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriterTest.java`

- [ ] **Step 1: Write the failing metadata tests**

```java
@Test
void shouldLoadEncryptJsonFieldWithMultiplePaths() {
    EncryptMetadataRegistry registry = new EncryptMetadataRegistry(
            new DatabaseEncryptionProperties(),
            new AnnotationEncryptMetadataLoader()
    );

    EncryptTableRule rule = registry.findByEntity(JsonUserEntity.class).orElseThrow(AssertionError::new);

    EncryptJsonFieldRule jsonRule = rule.findJsonFieldByProperty("profileJson").orElseThrow(AssertionError::new);
    assertEquals("user_account", jsonRule.table());
    assertEquals("profile_json", jsonRule.column());
    assertEquals(2, jsonRule.pathRules().size());
    assertEquals("$.phone", jsonRule.pathRules().get(0).path());
    assertEquals("phone_encrypt", jsonRule.pathRules().get(0).storageTable());
}

@Test
void shouldRejectEncryptJsonFieldOnNonStringProperty() {
    assertThrows(EncryptionConfigurationException.class, () ->
            new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader())
                    .findByEntity(InvalidJsonFieldEntity.class));
}

@Test
void shouldRejectNonExactJsonPath() {
    assertThrows(EncryptionConfigurationException.class, () ->
            new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader())
                    .findByEntity(InvalidJsonPathEntity.class));
}
```

```java
@Test
void shouldRewriteJsonExtractEqualityOperandToHash() throws Exception {
    SqlConditionRewriter rewriter = newRewriter(new ArrayList<ProjectionMode>());
    SqlTableContext tableContext = tableContext(jsonFieldRule());
    SqlRewriteContext context = rewriteContext(
            "SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') = ?",
            Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
            Collections.<String, Object>singletonMap("phone", "13800138000")
    );

    Expression rewritten = rewriter.rewrite(
            parseWhere("SELECT id FROM user_account WHERE JSON_EXTRACT(profile_json, '$.phone') = ?"),
            tableContext,
            context
    );

    assertTrue(rewritten.toString().contains("JSON_EXTRACT"));
    assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=EncryptJsonMetadataTest,SqlConditionRewriterTest test`
Expected: FAIL because JSON annotations, metadata, and query rewrite support do not exist yet.

- [ ] **Step 3: Commit after the failing tests are in place**

```bash
git add common/src/test/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonMetadataTest.java common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriterTest.java
git commit -m "test: capture encrypt json field metadata expectations"
```

### Task 2: Implement annotation and metadata registration

**Files:**
- Create: `common/src/main/java/io/github/jasper/mybatis/encrypt/annotation/EncryptJsonField.java`
- Create: `common/src/main/java/io/github/jasper/mybatis/encrypt/annotation/EncryptJsonPath.java`
- Create: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonFieldRule.java`
- Create: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonPathRule.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptTableRule.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/AnnotationEncryptMetadataLoader.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistry.java`

- [ ] **Step 1: Implement the minimal metadata model**

```java
public @interface EncryptJsonField {
    String table() default "";
    String column() default "";
    String cipherAlgorithm() default "sm4";
    String assistedQueryAlgorithm() default "sm3";
    EncryptJsonPath[] paths();
}
```

```java
public @interface EncryptJsonPath {
    String path();
    String storageTable();
    String storageIdColumn() default "id";
    String hashColumn();
    String cipherColumn();
    String cipherAlgorithm() default "";
    String assistedQueryAlgorithm() default "";
}
```

```java
public final class EncryptJsonPathRule {
    // property-free path-level resolved rule:
    // path, storageTable, storageIdColumn, hashColumn, cipherColumn,
    // cipherAlgorithm, assistedQueryAlgorithm
}
```

```java
public final class EncryptJsonFieldRule {
    // property, table, column, field default algorithms, List<EncryptJsonPathRule>
}
```

- [ ] **Step 2: Load and validate JSON annotations**

```java
EncryptJsonField encryptJsonField = field.getAnnotation(EncryptJsonField.class);
if (encryptJsonField != null) {
    if (!String.class.equals(field.getType())) {
        throw new EncryptionConfigurationException(
                EncryptionErrorCode.INVALID_FIELD_RULE,
                "@EncryptJsonField only supports String properties. property=" + field.getName()
        );
    }
    // resolve table/column, resolve path-level algorithms, validate exact path syntax,
    // validate storageTable/hashColumn/cipherColumn presence, then register the json field rule
}
```

- [ ] **Step 3: Extend table-level metadata lookup**

```java
public class EncryptTableRule {
    private final Map<String, EncryptJsonFieldRule> jsonFieldRules = new LinkedHashMap<String, EncryptJsonFieldRule>();
    private final Map<String, EncryptJsonPathRule> jsonPathRulesByColumnAndPath = new LinkedHashMap<String, EncryptJsonPathRule>();

    public void addJsonFieldRule(EncryptJsonFieldRule rule) { ... }
    public Optional<EncryptJsonFieldRule> findJsonFieldByProperty(String property) { ... }
    public Optional<EncryptJsonPathRule> findJsonPathRule(String column, String path) { ... }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=EncryptJsonMetadataTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/jasper/mybatis/encrypt/annotation/EncryptJsonField.java common/src/main/java/io/github/jasper/mybatis/encrypt/annotation/EncryptJsonPath.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonFieldRule.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptJsonPathRule.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptTableRule.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/AnnotationEncryptMetadataLoader.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistry.java
git commit -m "feat: register encrypt json field metadata"
```

### Task 3: Add failing write-path, query-rewrite, and decrypt tests

**Files:**
- Create: `common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/EncryptJsonRuntimeSupportTest.java`
- Modify: `spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java`

- [ ] **Step 1: Write failing runtime-focused tests**

```java
@Test
void shouldReplaceJsonSensitivePathWithHashOnWrite() {
    String json = "{\"phone\":\"13800138000\",\"name\":\"Aster\"}";
    EncryptJsonFieldRule rule = sampleJsonFieldRule();

    EncryptJsonWriteResult result = EncryptJsonRuntimeSupport.rewriteJsonForWrite(json, rule, sampleAlgorithms());

    assertTrue(result.rewrittenJson().contains(new Sm3AssistedQueryAlgorithm().transform("13800138000")));
    assertEquals(1, result.pathWrites().size());
    assertEquals("phone_encrypt", result.pathWrites().get(0).storageTable());
}

@Test
void shouldRestoreJsonHashBackToPlaintext() {
    String hash = new Sm3AssistedQueryAlgorithm().transform("13800138000");
    String json = "{\"phone\":\"" + hash + "\",\"name\":\"Aster\"}";
    EncryptJsonFieldRule rule = sampleJsonFieldRule();

    String restored = EncryptJsonRuntimeSupport.restoreJsonFromHashes(
            json, rule, lookup(hash, sampleCipher("13800138000")), sampleAlgorithms());

    assertTrue(restored.contains("\"phone\":\"13800138000\""));
}
```

```java
@Test
void shouldRewriteJsonExtractInListToHashOperands() {
    // assert JSON_EXTRACT operand list gets hash-transformed
}

@Test
void shouldRejectJsonSetOnEncryptJsonField() {
    // assert UnsupportedEncryptedOperationException
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=EncryptJsonRuntimeSupportTest,SqlRewriteEngineTest test`
Expected: FAIL because the runtime helper and JSON query handling do not exist yet.

- [ ] **Step 3: Commit**

```bash
git add common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/EncryptJsonRuntimeSupportTest.java spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java
git commit -m "test: capture encrypt json runtime behavior"
```

### Task 4: Implement JSON write/query/decrypt runtime

**Files:**
- Create: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/EncryptJsonRuntimeSupport.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlWriteExpressionRewriter.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionOperandSupport.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriter.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidator.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/decrypt/ResultDecryptor.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java`

- [ ] **Step 1: Implement a focused JSON runtime helper**

```java
final class EncryptJsonRuntimeSupport {

    EncryptJsonWriteResult rewriteJsonForWrite(String json,
                                               EncryptJsonFieldRule rule,
                                               AlgorithmRegistry algorithmRegistry) {
        // parse JSON, walk exact paths, compute hash/cipher, replace path value with hash,
        // return rewritten JSON plus external-table write descriptors
    }

    String restoreJsonFromHashes(String json,
                                 EncryptJsonFieldRule rule,
                                 CipherLookup lookup,
                                 AlgorithmRegistry algorithmRegistry) {
        // parse JSON, find configured paths, use current hash value to resolve cipher,
        // decrypt and replace back into JSON
    }
}
```

- [ ] **Step 2: Integrate whole-column JSON writes**

```java
if (rule is EncryptJsonFieldRule and expression is JdbcParameter/StringValue) {
    EncryptJsonWriteResult result = encryptJsonRuntimeSupport.rewriteJsonForWrite(...);
    context.replaceLastConsumed(result.rewrittenJson(), MaskingMode.HASH);
    // register external-table writes for later persistence
}
```

- [ ] **Step 3: Integrate precise JSON query rewrite**

```java
if (expression instanceof Function && isJsonExtractFunction((Function) expression)) {
    ResolvedJsonPathCondition jsonPathCondition = resolveJsonExtractCondition((Function) expression, tableContext);
    // allow only =, !=, IN with static path literal
    // hash-transform the right-side operand(s)
}
```

- [ ] **Step 4: Reject unsupported JSON updates and conditions**

```java
if (isJsonMutationFunction(expression) && touchesEncryptJsonColumn(...)) {
    throw new UnsupportedEncryptedOperationException(
            EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION,
            "JSON_SET/JSON_REPLACE/JSON_MERGE is not supported on EncryptJsonField."
    );
}
```

- [ ] **Step 5: Restore JSON on result decryption**

```java
EncryptJsonFieldRule jsonRule = tableRule.findJsonFieldByProperty(propertyPath).orElse(null);
if (jsonRule != null) {
    String restored = encryptJsonRuntimeSupport.restoreJsonFromHashes(
            (String) value,
            jsonRule,
            separateTableEncryptionManager.jsonCipherLookup(),
            algorithmRegistry
    );
    propertyReference.setValue(restored);
    continue;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=EncryptJsonRuntimeSupportTest,SqlConditionRewriterTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=SqlRewriteEngineTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/EncryptJsonRuntimeSupport.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlWriteExpressionRewriter.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionOperandSupport.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriter.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteValidator.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/decrypt/ResultDecryptor.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java
git commit -m "feat: support encrypt json field runtime flow"
```

### Task 5: Add failing migration and schema-generation coverage

**Files:**
- Modify: `migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationPlanFactoryTest.java`
- Modify: `migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationSchemaSqlGeneratorTest.java`
- Modify: `migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationExecutionFlowTest.java`

- [ ] **Step 1: Write failing migration tests**

```java
@Test
void shouldBuildMigrationPlanForEncryptJsonField() {
    EntityMigrationPlan plan = new EntityMigrationPlanFactory(metadataRegistry()).create(
            EntityMigrationDefinition.builder(JsonUserEntity.class, "id").build());

    assertEquals(1, plan.getJsonColumnPlans().size());
    assertEquals("profile_json", plan.getJsonColumnPlans().get(0).getSourceColumn());
    assertEquals(2, plan.getJsonColumnPlans().get(0).getPathPlans().size());
}

@Test
void shouldGenerateSeparateTableSchemaForEncryptJsonPaths() {
    List<String> ddl = schemaGenerator().generateForEntity(JsonUserEntity.class);
    assertTrue(ddl.stream().anyMatch(sql -> sql.contains("phone_encrypt")));
    assertTrue(ddl.stream().anyMatch(sql -> sql.contains("phone_hash")));
    assertTrue(ddl.stream().anyMatch(sql -> sql.contains("phone_cipher")));
}
```

```java
@Test
void shouldMigratePlainJsonIntoHashJsonAndSeparateCipherRows() throws Exception {
    // seed plaintext JSON row
    // run migration
    // assert main table JSON path is replaced with hash
    // assert external table has cipher row
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl migration -Dtest=MigrationPlanFactoryTest,MigrationSchemaSqlGeneratorTest,MigrationExecutionFlowTest test`
Expected: FAIL because migration has no JSON path support.

- [ ] **Step 3: Commit**

```bash
git add migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationPlanFactoryTest.java migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationSchemaSqlGeneratorTest.java migration/src/test/java/io/github/jasper/mybatis/encrypt/migration/MigrationExecutionFlowTest.java
git commit -m "test: cover encrypt json field migration behavior"
```

### Task 6: Implement migration and schema generation

**Files:**
- Modify: `migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/EntityMigrationColumnPlan.java`
- Modify: `migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/plan/EntityMigrationPlanFactory.java`
- Modify: `migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/jdbc/MigrationValueResolver.java`
- Modify: `migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/jdbc/JdbcMigrationRecordWriter.java`
- Modify: `migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/MigrationSchemaSqlGenerator.java`

- [ ] **Step 1: Extend migration planning to include JSON path plans**

```java
// add immutable JSON column/path plan models
// EntityMigrationPlanFactory should collect them from EncryptTableRule JSON metadata
```

- [ ] **Step 2: Reuse JSON runtime helper during migration**

```java
// derive rewritten JSON + external writes from plaintext JSON
// write main table JSON column back
// insert external table cipher rows by hash de-dup
```

- [ ] **Step 3: Extend schema generation**

```java
// register storageIdColumn/hashColumn/cipherColumn requirements for every EncryptJsonPath
// merge same-table requirements when multiple paths share one storageTable
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl migration -Dtest=MigrationPlanFactoryTest,MigrationSchemaSqlGeneratorTest,MigrationExecutionFlowTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/EntityMigrationColumnPlan.java migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/plan/EntityMigrationPlanFactory.java migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/jdbc/MigrationValueResolver.java migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/jdbc/JdbcMigrationRecordWriter.java migration/src/main/java/io/github/jasper/mybatis/encrypt/migration/MigrationSchemaSqlGenerator.java
git commit -m "feat: add encrypt json field migration support"
```

### Task 7: Update docs and run focused verification

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/sql-support-matrix.md`
- Modify: `docs/persistence-encryption-guide.zh-CN.md`
- Modify: `docs/persistence-encryption-guide.en.md`
- Modify: `docs/migration-guide.zh-CN.md`
- Modify: `docs/migration-guide.en.md`

- [ ] **Step 1: Document the runtime architecture**

```md
- `@EncryptJsonField` / `@EncryptJsonPath`: declare exact JSON paths that should be stored as main-table hash values plus separate-table ciphertext.
- JSON path queries only support exact static `json_extract(..., '$.path')` equality / inequality / `IN` shapes.
- JSON mutation functions such as `JSON_SET` remain fail-fast.
```

- [ ] **Step 2: Update the SQL support matrix**

```md
| `json_extract(encrypted_json_column, '$.path') = ?` | Supported | Exact static path only; parameter is rewritten to assisted hash value. |
| `json_extract(... ) IN (...)` | Supported | Exact static path only; each operand is transformed to hash. |
| JSON mutation functions on `@EncryptJsonField` | Rejected | Partial JSON updates are not supported for encrypted JSON fields. |
```

- [ ] **Step 3: Run focused verification**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=EncryptJsonMetadataTest,EncryptJsonRuntimeSupportTest,SqlConditionRewriterTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl migration -Dtest=MigrationPlanFactoryTest,MigrationSchemaSqlGeneratorTest,MigrationExecutionFlowTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=SqlRewriteEngineTest,DatabaseEncryptionInterceptorTest test`
Expected: PASS.

- [ ] **Step 4: Run the recommended acceptance command**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture.md docs/sql-support-matrix.md docs/persistence-encryption-guide.zh-CN.md docs/persistence-encryption-guide.en.md docs/migration-guide.zh-CN.md docs/migration-guide.en.md
git commit -m "docs: describe encrypt json field support"
```

---

### Self-Review Checklist

- [ ] `@EncryptJsonField` only applies to `String` JSON properties.
- [ ] JSON paths are exact static paths only; unsupported shapes fail fast.
- [ ] Main table JSON stores hash values, not plaintext.
- [ ] Separate tables store deduplicated `hash + cipher` rows per `EncryptJsonPath`.
- [ ] `json_extract(...)=? / !=? / IN (...)` rewrite is covered with focused tests.
- [ ] Result decryption restores hash JSON back to plaintext JSON strings.
- [ ] Migration writes main-table hash JSON and separate-table ciphertext rows idempotently.
- [ ] DDL generation merges shared storage table requirements without duplicate output.
