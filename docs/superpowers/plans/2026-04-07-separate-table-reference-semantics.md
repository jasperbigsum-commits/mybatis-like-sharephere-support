# Separate Table Reference Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change separate-table mode so the main-table encrypted field stores the separate-table record id, and queries/hydration resolve ciphertext through that referenced id.

**Architecture:** Remove business-id linkage semantics from separate-table rules. The main SQL rewrite path must keep the logical field in the main table as a reference id, while separate-table persistence/hydration manages the external ciphertext row addressed by `storageIdColumn`. Query predicates on separate-table fields become `EXISTS` joins by referenced id instead of business-id correlation.

**Tech Stack:** Java 17, Maven, MyBatis, Spring Boot auto-configuration, JSqlParser, JUnit 5, H2

---

### Task 1: Extend separate-table metadata model

**Files:**
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/config/DatabaseEncryptionProperties.java`
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptColumnRule.java`
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistry.java`
- Test: `src/test/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistryTest.java`

- [ ] **Step 1: Write the failing metadata test**

```java
@Test
void shouldDefaultSeparateTableStorageIdColumnToIdWithoutBusinessSourceLink() {
    DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
    DatabaseEncryptionProperties.TableRuleProperties table = new DatabaseEncryptionProperties.TableRuleProperties();
    DatabaseEncryptionProperties.FieldRuleProperties field = new DatabaseEncryptionProperties.FieldRuleProperties();
    field.setColumn("id_card_ref");
    field.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
    field.setStorageTable("user_id_card_encrypt");
    field.setStorageColumn("id_card_cipher");
    field.setAssistedQueryColumn("id_card_hash");
    table.getFields().put("idCard", field);
    properties.getTables().put("userAccount", table);

    EncryptMetadataRegistry registry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
    EncryptColumnRule rule = registry.findByTable("userAccount").orElseThrow().findByProperty("idCard").orElseThrow();

    assertEquals("id", rule.storageIdColumn());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=EncryptMetadataRegistryTest#shouldDefaultSeparateTableStorageIdColumnToIdWithoutBusinessSourceLink test`
Expected: FAIL because current rule model still assumes `sourceIdColumn`/`sourceIdProperty` semantics.

- [ ] **Step 3: Implement minimal metadata change**

```java
private String storageIdColumn = "id";
```

```java
public record EncryptColumnRule(
        String property,
        String column,
        String cipherAlgorithm,
        String assistedQueryColumn,
        String assistedQueryAlgorithm,
        String likeQueryColumn,
        String likeQueryAlgorithm,
        FieldStorageMode storageMode,
        String storageTable,
        String storageColumn,
        String storageIdColumn
) {}
```

```java
EncryptColumnRule rule = new EncryptColumnRule(
        property,
        column,
        properties.getCipherAlgorithm(),
        properties.getAssistedQueryColumn(),
        properties.getAssistedQueryAlgorithm(),
        properties.getLikeQueryColumn(),
        properties.getLikeQueryAlgorithm(),
        properties.getStorageMode(),
        properties.getStorageTable(),
        properties.getStorageColumn() != null ? properties.getStorageColumn() : column,
        properties.getStorageIdColumn() != null ? properties.getStorageIdColumn() : "id"
);
```

- [ ] **Step 4: Run metadata tests**

Run: `./mvnw -Dtest=EncryptMetadataRegistryTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/jasper/mybatis/encrypt/config/DatabaseEncryptionProperties.java src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptColumnRule.java src/main/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistry.java src/test/java/io/github/jasper/mybatis/encrypt/core/metadata/EncryptMetadataRegistryTest.java
git commit -m "refactor: remove business id linkage from separate-table metadata"
```

### Task 2: Rewrite separate-table SQL predicates to join by referenced storage id

**Files:**
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java`
- Test: `src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java`

- [ ] **Step 1: Write the failing SQL rewrite tests**

```java
@Test
void shouldRewriteSeparateTableEqualityUsingReferencedStorageId() {
    BoundSql boundSql = new BoundSql(
            configuration,
            "SELECT id, id_card_ref AS id_card FROM user_account WHERE id_card = ?",
            List.of(new ParameterMapping.Builder(configuration, "idCard", String.class).build()),
            Map.of("idCard", "320101199001011234")
    );

    RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

    assertTrue(result.sql().contains("EXISTS"));
    assertTrue(result.sql().contains("id_card_encrypt"));
    assertTrue(result.sql().contains("id = id_card_ref") || result.sql().contains("`id` = `id_card_ref`"));
    assertTrue(result.sql().contains("id_card_hash = ?") || result.sql().contains("`id_card_hash` = ?"));
}
```

```java
@Test
void shouldRewriteSeparateTableIsNullUsingReferencedStorageId() {
    BoundSql boundSql = new BoundSql(
            configuration,
            "SELECT id FROM user_account WHERE id_card IS NULL",
            List.of(),
            Map.of()
    );

    RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

    assertTrue(result.sql().contains("NOT EXISTS") || result.sql().contains("NOT EXISTS ("));
}
```

- [ ] **Step 2: Run rewrite tests to verify they fail**

Run: `./mvnw -Dtest=SqlRewriteEngineTest#shouldRewriteSeparateTableEqualityUsingReferencedStorageId,SqlRewriteEngineTest#shouldRewriteSeparateTableIsNullUsingReferencedStorageId test`
Expected: FAIL because current rewrite still correlates by `sourceIdColumn`.

- [ ] **Step 3: Implement minimal rewrite change**

```java
private Expression buildExistsSubQuery(Column sourceColumn,
                                       EncryptColumnRule rule,
                                       String targetColumn,
                                       Expression valueExpression,
                                       boolean equality) {
    PlainSelect subQueryBody = new PlainSelect();
    subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
    subQueryBody.setFromItem(new Table(quote(rule.storageTable())));

    EqualsTo joinEquals = new EqualsTo();
    joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
    joinEquals.setRightExpression(buildColumn(sourceColumn, rule.column()));

    // existing valuePredicate construction remains
}
```

```java
private Expression buildExistsPresenceSubQuery(Column sourceColumn,
                                               EncryptColumnRule rule,
                                               boolean shouldExist) {
    PlainSelect subQueryBody = new PlainSelect();
    subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
    subQueryBody.setFromItem(new Table(quote(rule.storageTable())));

    EqualsTo joinEquals = new EqualsTo();
    joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
    joinEquals.setRightExpression(buildColumn(sourceColumn, rule.column()));
    subQueryBody.setWhere(joinEquals);

    ExistsExpression existsExpression = new ExistsExpression();
    existsExpression.setRightExpression(new ParenthesedSelect().withSelect(subQueryBody));
    existsExpression.setNot(!shouldExist);
    return existsExpression;
}
```

- [ ] **Step 4: Run focused rewrite tests**

Run: `./mvnw -Dtest=SqlRewriteEngineTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java
git commit -m "feat: rewrite separate-table predicates by referenced storage id"
```

### Task 3: Change separate-table write and hydration behavior to referenced id semantics

**Files:**
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java`
- Modify: `src/main/java/io/github/jasper/mybatis/encrypt/plugin/DatabaseEncryptionInterceptor.java`
- Test: `src/test/java/io/github/jasper/mybatis/encrypt/integration/MybatisEncryptionIntegrationTest.java`

- [ ] **Step 1: Write the failing integration tests**

```java
@Test
void shouldStoreSeparateTableIdInMainTableAndHydrateByReference() throws Exception {
    UserRecord user = new UserRecord();
    user.setId(2L);
    user.setName("Bob");
    user.setIdCard("320101199001011234");

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
        UserMapper mapper = session.getMapper(UserMapper.class);
        assertEquals(1, mapper.insertUser(user));
        UserRecord loaded = mapper.selectById(2L);
        assertEquals("320101199001011234", loaded.getIdCard());
    }

    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery("select id_card_ref from user_account where id = 2")) {
        resultSet.next();
        assertTrue(resultSet.getLong("id_card_ref") > 0L);
    }
}
```

```java
@Test
void shouldUpdateReferencedSeparateTableRowOnUpdate() throws Exception {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
        UserMapper mapper = session.getMapper(UserMapper.class);
        mapper.insertUser(new UserRecord(3L, "Carol", "13800138000", "320101199001011234"));
        mapper.updateIdCard(3L, "320101199001019999");
        UserRecord loaded = mapper.selectById(3L);
        assertEquals("320101199001019999", loaded.getIdCard());
    }
}
```

- [ ] **Step 2: Run integration tests to verify they fail**

Run: `./mvnw -Dtest=MybatisEncryptionIntegrationTest#shouldStoreSeparateTableIdInMainTableAndHydrateByReference,MybatisEncryptionIntegrationTest#shouldUpdateReferencedSeparateTableRowOnUpdate test`
Expected: FAIL because current implementation deletes/inserts by business id and hydrates by `sourceIdProperty`.

- [ ] **Step 3: Implement minimal write/hydration change**

```java
Object referenceId = metaObject.hasGetter(rule.property()) ? metaObject.getValue(rule.property()) : null;
```

```java
private Object insertExternalRow(EncryptColumnRule rule, Object plainValue) {
    String sql = "insert into " + quote(rule.storageTable()) + " (...) values (...)";
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        bind(statement, values);
        statement.executeUpdate();
        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
            if (generatedKeys.next()) {
                return generatedKeys.getObject(1);
            }
        }
        throw new EncryptionConfigurationException("Failed to obtain separate-table generated id.");
    }
}
```

```java
private void hydrateRule(List<?> candidates, EncryptColumnRule rule) {
    Map<Object, MetaObject> metaByReferenceId = new LinkedHashMap<>();
    for (Object candidate : candidates) {
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        Object referenceId = metaObject.getValue(rule.property());
        if (referenceId != null) {
            metaByReferenceId.put(referenceId, metaObject);
        }
    }
    Map<Object, String> cipherByReferenceId = loadCipherValues(rule, new ArrayList<>(metaByReferenceId.keySet()));
    cipherByReferenceId.forEach((referenceId, cipherText) -> {
        MetaObject metaObject = metaByReferenceId.get(referenceId);
        if (metaObject != null && cipherText != null) {
            metaObject.setValue(rule.property(), algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(cipherText));
        }
    });
}
```

- [ ] **Step 4: Run integration tests**

Run: `./mvnw -Dtest=MybatisEncryptionIntegrationTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/jasper/mybatis/encrypt/core/support/SeparateTableEncryptionManager.java src/main/java/io/github/jasper/mybatis/encrypt/plugin/DatabaseEncryptionInterceptor.java src/test/java/io/github/jasper/mybatis/encrypt/integration/MybatisEncryptionIntegrationTest.java
git commit -m "feat: persist separate-table fields as external row references"
```

### Task 4: Update docs for the new separate-table model

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/sql-support-matrix.md`

- [ ] **Step 1: Update README separate-table example**

```md
- `storageIdColumn` now identifies the external table primary key.
- The main-table encrypted field stores that external row id.
- Separate-table hydration resolves ciphertext by external row id instead of business row id.
```

- [ ] **Step 2: Update architecture doc**

```md
- For separate-table fields, write-path processing creates or updates the external ciphertext row and stores the external row id in the main table.
- Query predicates on separate-table fields join main-table reference ids to `storageIdColumn` in the external table.
- Result hydration loads external ciphertext by referenced row id and decrypts it back into the entity property.
```

- [ ] **Step 3: Update SQL support matrix wording**

```md
| `UPDATE` | Supported | Separate-table fields update the referenced external ciphertext row; when no reference exists, a new external row is created and its id is written back to the main table field. |
| Separate-table hydration | Supported | Main-table fields store external row ids; query results are hydrated by loading ciphertext from the external table via those ids. |
```

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add README.md docs/architecture.md docs/sql-support-matrix.md
git commit -m "docs: describe separate-table reference semantics"
```
