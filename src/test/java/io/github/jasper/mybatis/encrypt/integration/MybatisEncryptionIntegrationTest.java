package io.github.jasper.mybatis.encrypt.integration;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;

import static org.junit.jupiter.api.Assertions.*;

class MybatisEncryptionIntegrationTest {

    private static final Path H2_DB_DIR = Path.of("target", "h2");
    private static final String H2_DB_NAME = "mybatis-encryption-integration";
    private static final Path H2_DB_FILE = H2_DB_DIR.resolve(H2_DB_NAME + ".mv.db");

    private JdbcDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;
    private ParameterCaptureInterceptor parameterCaptureInterceptor;
    private TrackingSeparateTableEncryptionManager trackingSeparateTableManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(H2_DB_DIR);
        dataSource = new JdbcDataSource();
        dataSource.setURL(databaseUrl());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema();
        parameterCaptureInterceptor = new ParameterCaptureInterceptor();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void shouldEncryptSameTableFieldAndDecryptOnRead() throws Exception {
        UserRecord user = user(1L, "Alice", "13800138000", null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord loaded = mapper.selectByPhone("13800138000");
            assertNotNull(loaded);
            assertEquals("Alice", loaded.getName());
            assertEquals("13800138000", loaded.getPhone());
        }

        assertTrue(Files.exists(H2_DB_FILE));
        assertSameTableStorage(1L, "13800138000");
    }

    @Test
    void shouldStoreSeparateTableIdInMainTableAndHydrateResultByReference() throws Exception {
        UserRecord user = user(2L, "Bob", "13900139000", "320101199001011234");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));

            UserRecord byPhone = mapper.selectByPhone("13900139000");
            assertNotNull(byPhone);
            assertEquals("13900139000", byPhone.getPhone());
            assertEquals("320101199001011234", byPhone.getIdCard());

            UserRecord byIdCard = mapper.selectByIdCard("320101199001011234");
            assertNotNull(byIdCard);
            assertEquals("Bob", byIdCard.getName());
            assertEquals("320101199001011234", byIdCard.getIdCard());
        }

        String referenceId = loadReferenceId(2L);
        assertNotNull(referenceId);
        assertFalse(referenceId.isBlank());
        assertSeparateTableStorage(referenceId, "320101199001011234");
    }

    @Test
    void shouldStoreSeparateTableReferenceAsStringInBoundSql() {
        UserRecord user = user(4L, "Dave", "13600136000", "320101199001018888");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        Object referenceValue = parameterCaptureInterceptor.lastIdCardAdditionalParameter;
        assertNotNull(referenceValue);
        assertInstanceOf(String.class, referenceValue);
    }

    @Test
    void shouldSkipSeparateTablePreparationForSameTableOnlyWrite() {
        UserRecord user = user(5L, "Eve", "13500135000", null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        assertEquals(0, trackingSeparateTableManager.prepareWriteReferencesCalls);
    }

    @Test
    void shouldSwitchToNewSeparateTableReferenceOnUpdateWithoutMutatingExistingRow() throws Exception {
        UserRecord user = user(3L, "Carol", "13700137000", "320101199001011234");

        String originalReferenceId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord inserted = mapper.selectById(3L);
            assertNotNull(inserted);
            assertEquals("320101199001011234", inserted.getIdCard());
        }

        originalReferenceId = loadReferenceId(3L);

        UserRecord update = user(3L, null, null, "320101199001019999");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updateIdCard(update));
            UserRecord loaded = mapper.selectById(3L);
            assertNotNull(loaded);
            assertEquals("320101199001019999", loaded.getIdCard());
        }

        String updatedReferenceId = loadReferenceId(3L);
        assertNotEquals(originalReferenceId, updatedReferenceId);
        assertSeparateTableStorage(originalReferenceId, "320101199001011234");
        assertSeparateTableStorage(updatedReferenceId, "320101199001019999");
    }

    @Test
    void shouldReuseExistingSeparateTableReferenceWhenAssignedHashMatches() throws Exception {
        UserRecord first = user(7L, "Gary", "13100131001", "320101199001015555");
        UserRecord second = user(8L, "Helen", "13100131002", "320101199001016666");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(first));
            assertEquals(1, mapper.insertUser(second));
        }

        String firstReferenceId = loadReferenceId(7L);
        String secondReferenceId = loadReferenceId(8L);
        assertNotEquals(firstReferenceId, secondReferenceId);

        UserRecord update = user(7L, null, null, "320101199001016666");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updateIdCard(update));
            UserRecord loaded = mapper.selectById(7L);
            assertNotNull(loaded);
            assertEquals("320101199001016666", loaded.getIdCard());
        }

        assertEquals(secondReferenceId, loadReferenceId(7L));
        assertSeparateTableStorage(firstReferenceId, "320101199001015555");
        assertSeparateTableStorage(secondReferenceId, "320101199001016666");
        assertEquals(2, queryForInt("select count(1) from user_id_card_encrypt"));
    }

    @Test
    void shouldPersistAndReadConsistentlyAcrossStorageModesOnInsertAndUpdate() throws Exception {
        UserRecord inserted = user(6L, "Frank", "13400134000", "320101199001017777");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(inserted));
            assertReadableUser(mapper, inserted);
        }

        String originalReferenceId = loadReferenceId(6L);
        assertSameTableStorage(6L, "13400134000");
        assertSeparateTableStorage(originalReferenceId, "320101199001017777");

        UserRecord updated = user(6L, "Frank", "13400134999", "320101199001016666");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updatePhoneAndIdCard(updated));
            assertReadableUser(mapper, updated);
            assertNull(mapper.selectByPhone("13400134000"));
            assertNull(mapper.selectByIdCard("320101199001017777"));
        }

        String updatedReferenceId = loadReferenceId(6L);
        assertNotEquals(originalReferenceId, updatedReferenceId);
        assertSameTableStorage(6L, "13400134999");
        assertSeparateTableStorage(originalReferenceId, "320101199001017777");
        assertSeparateTableStorage(updatedReferenceId, "320101199001016666");
    }

    @Test
    void shouldBatchInsertAcrossStorageModesUsingBatchExecutor() throws Exception {
        List<UserRecord> users = List.of(
                user(11L, "Grace", "13300133001", "320101199001010011"),
                user(12L, "Heidi", "13300133002", "320101199001010022")
        );

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : users) {
                mapper.insertUser(user);
            }
            List<BatchResult> results = session.flushStatements();
            assertFalse(results.isEmpty());
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : users) {
                assertReadableUser(mapper, user);
            }
        }

        assertEquals(2, queryForInt("select count(1) from user_account"));
        assertEquals(2, queryForInt("select count(1) from user_id_card_encrypt"));
        assertSameTableStorage(11L, "13300133001");
        assertSameTableStorage(12L, "13300133002");
        assertSeparateTableStorage(loadReferenceId(11L), "320101199001010011");
        assertSeparateTableStorage(loadReferenceId(12L), "320101199001010022");
    }

    @Test
    void shouldBatchUpdateAcrossStorageModesUsingBatchExecutor() throws Exception {
        List<UserRecord> originalUsers = List.of(
                user(21L, "Ivan", "13200132001", "320101199001010031"),
                user(22L, "Judy", "13200132002", "320101199001010032")
        );
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : originalUsers) {
                assertEquals(1, mapper.insertUser(user));
            }
        }

        String reference21 = loadReferenceId(21L);
        String reference22 = loadReferenceId(22L);
        List<UserRecord> updatedUsers = List.of(
                user(21L, "Ivan", "13200132991", "320101199001019931"),
                user(22L, "Judy", "13200132992", "320101199001019932")
        );

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : updatedUsers) {
                mapper.updatePhoneAndIdCard(user);
            }
            List<BatchResult> results = session.flushStatements();
            assertFalse(results.isEmpty());
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : updatedUsers) {
                assertReadableUser(mapper, user);
            }
            assertNull(mapper.selectByPhone("13200132001"));
            assertNull(mapper.selectByPhone("13200132002"));
            assertNull(mapper.selectByIdCard("320101199001010031"));
            assertNull(mapper.selectByIdCard("320101199001010032"));
        }

        String updatedReference21 = loadReferenceId(21L);
        String updatedReference22 = loadReferenceId(22L);
        assertNotEquals(reference21, updatedReference21);
        assertNotEquals(reference22, updatedReference22);
        assertSameTableStorage(21L, "13200132991");
        assertSameTableStorage(22L, "13200132992");
        assertSeparateTableStorage(reference21, "320101199001010031");
        assertSeparateTableStorage(reference22, "320101199001010032");
        assertSeparateTableStorage(updatedReference21, "320101199001019931");
        assertSeparateTableStorage(updatedReference22, "320101199001019932");
    }

    private SqlSessionFactory buildSqlSessionFactory() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setDefaultCipherKey("integration-test-key");
        properties.setLogMaskedSql(false);

        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        AlgorithmRegistry algorithmRegistry = new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("integration-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
        );
        TrackingSeparateTableEncryptionManager separateTableManager =
                new TrackingSeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
        trackingSeparateTableManager = separateTableManager;
        ResultDecryptor resultDecryptor = new ResultDecryptor(metadataRegistry, algorithmRegistry, separateTableManager);
        SqlRewriteEngine sqlRewriteEngine = new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
        DatabaseEncryptionInterceptor interceptor =
                new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties, separateTableManager,
                        metadataRegistry);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(interceptor);
        configuration.addInterceptor(parameterCaptureInterceptor);
        configuration.addMapper(UserMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void initializeSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists user_account");
            statement.execute("drop table if exists user_id_card_encrypt");
            RunScript.execute(connection, new StringReader(loadSchemaSql()));
        }
    }

    private String loadSchemaSql() throws Exception {
        try (InputStream inputStream =
                     MybatisEncryptionIntegrationTest.class.getResourceAsStream("/sql/h2-integration-schema.sql")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test schema resource: /sql/h2-integration-schema.sql");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String databaseUrl() {
        String basePath = H2_DB_DIR.resolve(H2_DB_NAME).toAbsolutePath().toString().replace("\\", "/");
        return "jdbc:h2:file:" + basePath + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    }

    private UserRecord user(Long id, String name, String phone, String idCard) {
        UserRecord user = new UserRecord();
        user.setId(id);
        user.setName(name);
        user.setPhone(phone);
        user.setIdCard(idCard);
        return user;
    }

    private void assertReadableUser(UserMapper mapper, UserRecord expected) {
        UserRecord byId = mapper.selectById(expected.getId());
        assertNotNull(byId);
        assertEquals(expected.getName(), byId.getName());
        assertEquals(expected.getPhone(), byId.getPhone());
        assertEquals(expected.getIdCard(), byId.getIdCard());

        if (expected.getPhone() != null) {
            UserRecord byPhone = mapper.selectByPhone(expected.getPhone());
            assertNotNull(byPhone);
            assertEquals(expected.getId(), byPhone.getId());
            assertEquals(expected.getPhone(), byPhone.getPhone());
        }
        if (expected.getIdCard() != null) {
            UserRecord byIdCard = mapper.selectByIdCard(expected.getIdCard());
            assertNotNull(byIdCard);
            assertEquals(expected.getId(), byIdCard.getId());
            assertEquals(expected.getIdCard(), byIdCard.getIdCard());
        }
    }

    private void assertSameTableStorage(Long id, String plainPhone) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = " + id)) {
            resultSet.next();
            assertNotEquals(plainPhone, resultSet.getString("phone_cipher"));
            assertNotNull(resultSet.getString("phone_hash"));
            assertEquals(plainPhone, resultSet.getString("phone_like"));
        }
    }

    private void assertSeparateTableStorage(String referenceId, String plainIdCard) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select id_card_cipher, id_card_hash, id_card_like from user_id_card_encrypt where id = "
                             + referenceId)) {
            resultSet.next();
            assertNotEquals(plainIdCard, resultSet.getString("id_card_cipher"));
            assertNotNull(resultSet.getString("id_card_hash"));
            assertEquals(plainIdCard, resultSet.getString("id_card_like"));
        }
    }

    private String loadReferenceId(long id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select id_card from user_account where id = " + id)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int queryForInt(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    interface UserMapper {

        @Insert("""
                insert into user_account (id, name, phone, id_card)
                values (#{id}, #{name}, #{phone}, #{idCard})
                """)
        int insertUser(UserRecord user);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where phone = #{phone}
                """)
        UserRecord selectByPhone(@Param("phone") String phone);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where id_card = #{idCard}
                """)
        UserRecord selectByIdCard(@Param("idCard") String idCard);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where id = #{id}
                """)
        UserRecord selectById(@Param("id") Long id);

        @Update("""
                update user_account
                set id_card = #{idCard}
                where id = #{id}
                """)
        int updateIdCard(UserRecord user);

        @Update("""
                update user_account
                set phone = #{phone}, id_card = #{idCard}
                where id = #{id}
                """)
        int updatePhoneAndIdCard(UserRecord user);
    }

    @EncryptTable("user_account")
    static class UserRecord {

        private Long id;
        private String name;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;

        @EncryptField(
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class TrackingSeparateTableEncryptionManager extends SeparateTableEncryptionManager {

        private int prepareWriteReferencesCalls;

        TrackingSeparateTableEncryptionManager(JdbcDataSource dataSource,
                                               EncryptMetadataRegistry metadataRegistry,
                                               AlgorithmRegistry algorithmRegistry,
                                               DatabaseEncryptionProperties properties) {
            super(dataSource, metadataRegistry, algorithmRegistry, properties);
        }

        @Override
        public void prepareWriteReferences(org.apache.ibatis.mapping.MappedStatement mappedStatement,
                                           BoundSql boundSql) {
            prepareWriteReferencesCalls++;
            super.prepareWriteReferences(mappedStatement, boundSql);
        }
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
    })
    static class ParameterCaptureInterceptor implements Interceptor {

        private Object lastIdCardAdditionalParameter;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (invocation.getTarget() instanceof StatementHandler statementHandler) {
                BoundSql boundSql = statementHandler.getBoundSql();
                if (boundSql.hasAdditionalParameter("idCard")) {
                    lastIdCardAdditionalParameter = boundSql.getAdditionalParameter("idCard");
                }
                if (boundSql.hasAdditionalParameter("__encrypt_prepared_refs")) {
                    Object preparedReferences = boundSql.getAdditionalParameter("__encrypt_prepared_refs");
                    if (preparedReferences instanceof Map<?, ?> references && !references.isEmpty()) {
                        lastIdCardAdditionalParameter = references.values().iterator().next();
                    }
                }
            }
            return result;
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(java.util.Properties properties) {
        }
    }
}
