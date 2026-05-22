# Encrypted JDBC Executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Spring-managed JDBC facade that rewrites encrypted SQL, binds transformed parameters, and decrypts query results for direct `JdbcTemplate`-style usage.

**Architecture:** Put the reusable execution logic in `spring-starter/spring-support` so both Spring Boot 2 and 3 starters inherit the same behavior. The facade resolves the target `DataSource` by encryption config datasource name, delegates SQL rewrite to `SqlRewriteEngine`, executes through `JdbcTemplate`, and runs result decryption through `ResultDecryptor` for `SELECT` calls. Keep the API explicit and fail-fast for unsupported encrypted SQL shapes.

**Tech Stack:** Java 8 core APIs, Spring Framework JDBC, Spring Boot auto-configuration, existing `common` rewrite/decrypt core, JUnit 5, H2 test database.

---

### Task 1: Add a failing Spring auto-configuration test for the new executor bean

**Files:**
- Create: `spring-starter/spring-support/src/test/java/io/github/jasper/mybatis/encrypt/config/EncryptedJdbcExecutorAutoConfigurationTest.java`

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest(classes = EncryptedJdbcExecutorAutoConfigurationTest.TestApplication.class,
        properties = {
                "mybatis.encrypt.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:encrypt_jdbc;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class EncryptedJdbcExecutorAutoConfigurationTest {

    @Autowired
    private EncryptedJdbcExecutor encryptedJdbcExecutor;

    @Test
    void shouldExposeEncryptedJdbcExecutorBean() {
        assertThat(encryptedJdbcExecutor).isNotNull();
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -Dtest=EncryptedJdbcExecutorAutoConfigurationTest test`
Expected: FAIL because `EncryptedJdbcExecutor` bean does not exist yet.

### Task 2: Implement the JDBC executor facade and its auto-configuration bean

**Files:**
- Create: `spring-starter/spring-support/src/main/java/io/github/jasper/mybatis/encrypt/jdbc/EncryptedJdbcExecutor.java`
- Create: `spring-starter/spring-support/src/main/java/io/github/jasper/mybatis/encrypt/jdbc/DefaultEncryptedJdbcExecutor.java`
- Modify: `spring-starter/spring-support/src/main/java/io/github/jasper/mybatis/encrypt/config/LogsafeAutoConfiguration.java` if the bean belongs there, or create a new auto-configuration class under `spring-starter/spring-support/src/main/java/io/github/jasper/mybatis/encrypt/config/`

- [ ] **Step 3: Write minimal implementation**

```java
public interface EncryptedJdbcExecutor {
    List<Map<String, Object>> select(String dataSourceName, String sql, Object... args);
    int insert(String dataSourceName, String sql, Object... args);
    int update(String dataSourceName, String sql, Object... args);
    int delete(String dataSourceName, String sql, Object... args);
}
```

```java
public class DefaultEncryptedJdbcExecutor implements EncryptedJdbcExecutor {
    // resolve DataSource by name, rewrite SQL via SqlRewriteEngine, execute via JdbcTemplate,
    // decrypt select rows via ResultDecryptor before returning.
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -Dtest=EncryptedJdbcExecutorAutoConfigurationTest test`
Expected: PASS.

### Task 3: Add direct execution coverage for rewrite + decrypt behavior

**Files:**
- Create: `spring-starter/spring-support/src/test/java/io/github/jasper/mybatis/encrypt/jdbc/EncryptedJdbcExecutorIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void selectShouldRewriteEncryptedPredicateAndDecryptResult() {
    List<Map<String, Object>> rows = encryptedJdbcExecutor.select("master", "select id, phone from user_account where phone = ?", "13800000000");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("phone")).isEqualTo("13800000000");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -Dtest=EncryptedJdbcExecutorIntegrationTest test`
Expected: FAIL until SQL rewriting, parameter binding, and row decryption are wired correctly.

- [ ] **Step 3: Implement minimal wiring for `select/insert/update/delete`**

```java
// select: rewrite SQL, bind args, query rows, decrypt result map values by query result plan
// write ops: rewrite SQL, bind args, execute update, return affected rows
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring-support -Dtest=EncryptedJdbcExecutorIntegrationTest test`
Expected: PASS.

### Task 4: Register the bean in both Spring Boot starters and verify full module tests

**Files:**
- Modify: `spring-starter/spring2-starter/src/main/java/io/github/jasper/mybatis/encrypt/config/MybatisEncryptionAutoConfiguration.java`
- Modify: `spring-starter/spring3-starter/src/main/java/io/github/jasper/mybatis/encrypt/config/MybatisEncryptionAutoConfiguration.java`
- Modify: `spring-starter/spring2-starter/src/test/java/...` and `spring-starter/spring3-starter/src/test/java/...` auto-configuration coverage if needed

- [ ] **Step 1: Add the bean to both auto-configurations**

```java
@Bean
@ConditionalOnBean(DataSource.class)
@ConditionalOnMissingBean(EncryptedJdbcExecutor.class)
public EncryptedJdbcExecutor encryptedJdbcExecutor(...) {
    return new DefaultEncryptedJdbcExecutor(...);
}
```

- [ ] **Step 2: Run the Spring starter test suites**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring3-starter -am test`
Expected: PASS.

- [ ] **Step 3: Run the Spring Boot 2 starter suite too**

Run: `mvn "-Dmaven.repo.local=.m2repo" -pl spring-starter/spring2-starter -am test`
Expected: PASS.

### Task 5: Update docs and matrix if needed

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/sql-support-matrix.md` only if public SQL support semantics change
- Modify: `README.md` and `README.en.md` if user-facing usage changes

- [ ] **Step 1: Document the new JDBC facade and its boundary**
- [ ] **Step 2: Verify no unsupported SQL promises were introduced**
- [ ] **Step 3: Keep Chinese and English docs aligned where user-facing text changed**

---

### Self-Review Checklist

- [ ] Every new public type has Javadoc.
- [ ] `select` returns decrypted row values only, never raw ciphertext in normal use.
- [ ] Unsupported encrypted SQL still fails fast.
- [ ] Spring Boot 2 and 3 register the same bean behavior.
- [ ] Plan contains no placeholders or vague steps.
