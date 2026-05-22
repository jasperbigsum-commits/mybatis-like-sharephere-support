# Encrypted Single-Sided Range Technical Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow encrypted-field single-sided range predicates (`>`, `>=`, `<`, `<=`) to rewrite onto technical hash/reference columns with a warning, while keeping `BETWEEN` fail-fast.

**Architecture:** Keep the production change in `common` so all adapters inherit the same behavior. Reuse the existing assisted/reference operand transformation path in `SqlConditionRewriter`, add a narrow warning callback that `SqlRewriteEngine` can route to its logger, and prove the contract with focused rewrite tests plus one Spring Boot 3 MyBatis integration path.

**Tech Stack:** Java 8 core runtime in `common`, existing JSqlParser rewrite pipeline, Spring Boot 3 test harness, JUnit 5, H2, repository-local Maven cache.

---

### Task 1: Add failing rewrite coverage for single-sided range comparisons

**Files:**
- Modify: `common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriterTest.java`
- Modify: `spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void shouldRewriteGreaterThanToAssistedColumnAndReplaceParameter() throws Exception {
    List<String> warnings = new ArrayList<>();
    SqlConditionRewriter rewriter = newRewriter(new ArrayList<>(), warnings);
    SqlTableContext tableContext = tableContext(sameTableRule());
    SqlRewriteContext context = rewriteContext(
            "SELECT id FROM user_account WHERE phone > ?",
            Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
            Collections.<String, Object>singletonMap("phone", "13800138000")
    );

    Expression rewritten = rewriter.rewrite(
            parseWhere("SELECT id FROM user_account WHERE phone > ?"),
            tableContext,
            context
    );

    assertTrue(rewritten.toString().contains("`phone_hash` > ?"));
    assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    assertEquals(Collections.singletonList("phone"), warnings);
}

@Test
void shouldRewriteSeparateTableMinorThanEqualsToMainReferenceHashPredicate() throws Exception {
    List<String> warnings = new ArrayList<>();
    SqlConditionRewriter rewriter = newRewriter(new ArrayList<>(), warnings);
    SqlTableContext tableContext = tableContext(separateTableRule());
    SqlRewriteContext context = rewriteContext(
            "SELECT id FROM user_account WHERE phone <= ?",
            Collections.singletonList(new ParameterMapping.Builder(new Configuration(), "phone", String.class).build()),
            Collections.<String, Object>singletonMap("phone", "13800138000")
    );

    Expression rewritten = rewriter.rewrite(
            parseWhere("SELECT id FROM user_account WHERE phone <= ?"),
            tableContext,
            context
    );

    assertTrue(rewritten.toString().contains("`phone` <= ?"));
    assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"), context.originalValue(0));
    assertEquals(Collections.singletonList("phone"), warnings);
}

@Test
void shouldKeepRejectingBetweenOnEncryptedField() {
    SqlConditionRewriter rewriter = newRewriter(new ArrayList<>(), new ArrayList<>());
    SqlTableContext tableContext = tableContext(sameTableRule());

    UnsupportedEncryptedOperationException exception = assertThrows(
            UnsupportedEncryptedOperationException.class,
            () -> rewriter.rewrite(
                    parseWhere("SELECT id FROM user_account WHERE phone BETWEEN ? AND ?"),
                    tableContext,
                    rewriteContext(
                            "SELECT id FROM user_account WHERE phone BETWEEN ? AND ?",
                            List.of(
                                    new ParameterMapping.Builder(new Configuration(), "start", String.class).build(),
                                    new ParameterMapping.Builder(new Configuration(), "end", String.class).build()
                            ),
                            Map.of("start", "13800138000", "end", "13800139000")
                    )
            )
    );

    assertEquals(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_RANGE, exception.getErrorCode());
}
```

```java
@Test
void shouldRewriteEncryptedRangePredicateToTechnicalColumn() {
    Configuration configuration = new Configuration();
    DatabaseEncryptionProperties properties = sampleProperties();
    SqlRewriteEngine engine = new SqlRewriteEngine(
            new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
            sampleAlgorithms(),
            properties
    );

    BoundSql boundSql = new BoundSql(
            configuration,
            "SELECT id FROM user_account WHERE phone > ?",
            List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
            Map.of("phone", "13800138000")
    );

    RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

    assertTrue(result.changed());
    assertTrue(result.sql().contains("`phone_hash` > ?"));
    assertEquals(new Sm3AssistedQueryAlgorithm().transform("13800138000"),
            result.maskedParameters().values().iterator().next().value());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=SqlConditionRewriterTest test`
Expected: FAIL with `UnsupportedEncryptedOperationException: Range comparison is not supported on encrypted fields.`

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=SqlRewriteEngineTest test`
Expected: FAIL because the engine still rejects `phone > ?` with `UNSUPPORTED_ENCRYPTED_RANGE`.

- [ ] **Step 3: Commit the failing test additions**

```bash
git add common/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriterTest.java spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngineTest.java
git commit -m "test: capture encrypted single-sided range rewrite expectations"
```

### Task 2: Add a failing Spring Boot 3 MyBatis integration path for cursor-style range queries

**Files:**
- Modify: `spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/integration/MybatisEncryptionIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test and mapper method**

```java
@Test
void shouldFilterSameTableCursorQueryByTechnicalHashRange() throws Exception {
    List<UserRecord> users = List.of(
            user(301L, "Aster", "13100131001", null),
            user(302L, "Beryl", "13100131002", null),
            user(303L, "Cedar", "13100131003", null)
    );

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
        UserMapper mapper = session.getMapper(UserMapper.class);
        for (UserRecord user : users) {
            assertEquals(1, mapper.insertUser(user));
        }
    }

    String cursor = "13100131002";
    Sm3AssistedQueryAlgorithm algorithm = new Sm3AssistedQueryAlgorithm();
    String cursorHash = algorithm.transform(cursor);

    List<Long> expectedIds = users.stream()
            .filter(user -> algorithm.transform(user.getPhone()).compareTo(cursorHash) > 0)
            .map(UserRecord::getId)
            .toList();

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
        UserMapper mapper = session.getMapper(UserMapper.class);
        List<UserRecord> loaded = mapper.selectAfterPhoneCursor(cursor);
        assertEquals(expectedIds, loaded.stream().map(UserRecord::getId).toList());
    }
}
```

```java
@Select("""
        select id, name, phone, id_card
        from user_account
        where phone > #{cursor}
        order by id
        """)
List<UserRecord> selectAfterPhoneCursor(@Param("cursor") String cursor);
```

- [ ] **Step 2: Run the integration test to verify it fails first**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=MybatisEncryptionIntegrationTest#shouldFilterSameTableCursorQueryByTechnicalHashRange test`
Expected: FAIL on the current branch before Task 3 lands, because `phone > #{cursor}` is still rejected as `UNSUPPORTED_ENCRYPTED_RANGE`.

- [ ] **Step 3: Commit the failing integration coverage**

```bash
git add spring-starter/spring3-starter/src/test/java/io/github/jasper/mybatis/encrypt/integration/MybatisEncryptionIntegrationTest.java
git commit -m "test: cover encrypted cursor-style range query integration"
```

### Task 3: Implement single-sided range rewrite and warning plumbing in `common`

**Files:**
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriter.java`
- Modify: `common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java`

- [ ] **Step 1: Write the minimal implementation**

```java
final class SqlConditionRewriter {

    private final java.util.function.Consumer<EncryptColumnRule> rangeWarningConsumer;

    SqlConditionRewriter(EncryptionValueTransformer valueTransformer,
                         BiFunction<Column, String, Column> columnBuilder,
                         BiFunction<EncryptColumnRule, String, String> assistedQueryColumnProvider,
                         BiFunction<EncryptColumnRule, String, String> likeQueryColumnProvider,
                         java.util.function.Function<String, String> identifierQuoter,
                         SelectRewriteDispatcher selectRewriteDispatcher,
                         java.util.function.Consumer<EncryptColumnRule> rangeWarningConsumer) {
        // keep existing assignments
        this.rangeWarningConsumer = rangeWarningConsumer;
    }

    Expression rewrite(Expression expression, SqlTableContext tableContext, SqlRewriteContext context) {
        if (expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            return rewriteRangeComparison((BinaryExpression) expression, tableContext, context);
        }
        // existing branches unchanged
    }

    private Expression rewriteRangeComparison(BinaryExpression expression,
                                              SqlTableContext tableContext,
                                              SqlRewriteContext context) {
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        if (left != null && right != null) {
            throw new UnsupportedEncryptedOperationException(
                    EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_OPERATION,
                    "Range comparison between two encrypted columns is not supported."
            );
        }
        ColumnResolution resolution = left != null ? new ColumnResolution(left.column(), left.rule(), true)
                : right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
        if (resolution == null) {
            expression.setLeftExpression(rewrite(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewrite(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        rangeWarningConsumer.accept(resolution.rule());
        String targetColumn = resolution.rule().isStoredInSeparateTable()
                ? resolution.rule().column()
                : assistedQueryColumnProvider.apply(resolution.rule(), "range query");
        Expression operand = resolution.leftColumn() ? expression.getRightExpression() : expression.getLeftExpression();
        Expression rewrittenOperand = rewriteAssistedOperand(
                resolution.rule(),
                operand,
                context,
                "Encrypted range condition must use prepared parameter, string literal, or CONCAT of them."
        );
        if (resolution.leftColumn()) {
            expression.setLeftExpression(columnBuilder.apply(resolution.column(), targetColumn));
            expression.setRightExpression(rewrittenOperand);
        } else {
            expression.setLeftExpression(rewrittenOperand);
            expression.setRightExpression(columnBuilder.apply(resolution.column(), targetColumn));
        }
        context.markChanged();
        return expression;
    }
}
```

```java
public SqlRewriteEngine(... ) {
    this.sqlConditionRewriter = new SqlConditionRewriter(
            valueTransformer,
            this::buildColumn,
            this::requireAssistedQueryColumn,
            this::requireLikeQueryColumn,
            this::quote,
            this::rewriteSelect,
            this::warnEncryptedRangeComparison
    );
}

private void warnEncryptedRangeComparison(EncryptColumnRule rule) {
    if (!log.isWarnEnabled()) {
        return;
    }
    log.warn("Single-sided range comparison on encrypted field [{}] is allowed for technical cursor semantics only; "
                    + "same-table fields use assisted/hash values and separate-table fields use reference values, "
                    + "so the result does not represent plaintext business ordering.",
            rule.property());
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=SqlConditionRewriterTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=SqlRewriteEngineTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=MybatisEncryptionIntegrationTest#shouldFilterSameTableCursorQueryByTechnicalHashRange test`
Expected: PASS, proving real MyBatis execution can filter by the technical hash range and still decrypt the selected rows.

- [ ] **Step 3: Commit the production change**

```bash
git add common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlConditionRewriter.java common/src/main/java/io/github/jasper/mybatis/encrypt/core/rewrite/SqlRewriteEngine.java
git commit -m "feat: allow technical single-sided encrypted range rewrites"
```

### Task 4: Update documentation and run the acceptance suites

**Files:**
- Modify: `docs/sql-support-matrix.md`
- Modify: `docs/architecture.md`

- [ ] **Step 1: Update the public SQL support contract**

```md
| Single-sided range predicates `>`, `>=`, `<`, `<=` | Supported with warning | Requires `assistedQueryColumn` for same-table fields; separate-table fields compare the main-table reference column. Results reflect technical values, not plaintext business ordering. |
| `BETWEEN` on encrypted fields | Rejected | Closed-interval business semantics are still not reliable on encrypted/hash/reference values. |
```

```md
2. 对加密字段的 `BETWEEN` 仍直接抛错；对 `>`、`>=`、`<`、`<=` 仅在存在稳定 assisted/reference 列时按技术值比较放行，并输出 warning。
```

- [ ] **Step 2: Merge carefully with existing local doc edits**

Run: `git -c safe.directory=E:/IdeaProject/mybatis-like-sharephere-support diff -- docs/architecture.md docs/sql-support-matrix.md`
Expected: Review the diff and keep any unrelated local edits intact instead of overwriting them.

- [ ] **Step 3: Run the focused regression suite**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl common -Dtest=SqlConditionRewriterTest test`
Expected: PASS.

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am -Dtest=SqlRewriteEngineTest,MybatisEncryptionIntegrationTest test`
Expected: PASS.

- [ ] **Step 4: Run the recommended acceptance command**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test`
Expected: PASS.

- [ ] **Step 5: Commit the docs update**

```bash
git add docs/architecture.md docs/sql-support-matrix.md
git commit -m "docs: describe technical encrypted range comparison support"
```

---

### Self-Review Checklist

- [ ] `BETWEEN` still fails fast everywhere after the change.
- [ ] Single-sided range predicates only compare technical assisted/reference values and never claim plaintext ordering semantics.
- [ ] Same-table range rewrites require `assistedQueryColumn`; separate-table rewrites stay on the main-table reference column.
- [ ] At least one real MyBatis integration path proves the rewritten range query executes and returns decrypted rows.
- [ ] Doc updates are merged with the existing local edits in [docs/architecture.md](/E:/IdeaProject/mybatis-like-sharephere-support/docs/architecture.md) and [docs/sql-support-matrix.md](/E:/IdeaProject/mybatis-like-sharephere-support/docs/sql-support-matrix.md) instead of overwriting them.
