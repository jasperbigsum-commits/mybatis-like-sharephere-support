package io.github.jasper.mybatis.encrypt.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
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

class MybatisEncryptionIntegrationTest {

    private JdbcDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;
    private ParameterCaptureInterceptor parameterCaptureInterceptor;
    private TrackingSeparateTableEncryptionManager trackingSeparateTableManager;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:encrypt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema();
        parameterCaptureInterceptor = new ParameterCaptureInterceptor();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void shouldEncryptSameTableFieldAndDecryptOnRead() throws Exception {
        UserRecord user = new UserRecord();
        user.setId(1L);
        user.setName("Alice");
        user.setPhone("13800138000");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord loaded = mapper.selectByPhone("13800138000");
            assertNotNull(loaded);
            assertEquals("Alice", loaded.getName());
            assertEquals("13800138000", loaded.getPhone());
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = 1")) {
            resultSet.next();
            assertNotEquals("13800138000", resultSet.getString("phone_cipher"));
            assertNotNull(resultSet.getString("phone_hash"));
            assertEquals("13800138000", resultSet.getString("phone_like"));
        }
    }

    @Test
    void shouldStoreSeparateTableIdInMainTableAndHydrateResultByReference() throws Exception {
        UserRecord user = new UserRecord();
        user.setId(2L);
        user.setName("Bob");
        user.setPhone("13900139000");
        user.setIdCard("320101199001011234");

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

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery(
                     "select id_card from user_account where id = 2")) {
            mainResult.next();
            String encryptId = mainResult.getString("id_card");
            assertNotNull(encryptId);
            assertTrue(!encryptId.isBlank());

            try (ResultSet encryptResult = statement.executeQuery(
                    "select id, id_card_cipher, id_card_hash from user_id_card_encrypt where id = " + encryptId)) {
                encryptResult.next();
                assertEquals(encryptId, encryptResult.getString("id"));
                assertNotEquals("320101199001011234", encryptResult.getString("id_card_cipher"));
                assertNotNull(encryptResult.getString("id_card_hash"));
            }
        }
    }

    @Test
    void shouldStoreSeparateTableReferenceAsStringInBoundSql() throws Exception {
        UserRecord user = new UserRecord();
        user.setId(4L);
        user.setName("Dave");
        user.setPhone("13600136000");
        user.setIdCard("320101199001018888");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        Object referenceValue = parameterCaptureInterceptor.lastIdCardAdditionalParameter;
        assertNotNull(referenceValue);
        assertTrue(referenceValue instanceof String);
    }

    @Test
    void shouldSkipSeparateTablePreparationForSameTableOnlyWrite() {
        UserRecord user = new UserRecord();
        user.setId(5L);
        user.setName("Eve");
        user.setPhone("13500135000");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        assertEquals(0, trackingSeparateTableManager.prepareWriteReferencesCalls);
    }

    @Test
    void shouldUpdateReferencedSeparateTableRowOnUpdate() throws Exception {
        UserRecord user = new UserRecord();
        user.setId(3L);
        user.setName("Carol");
        user.setPhone("13700137000");
        user.setIdCard("320101199001011234");

        String originalReferenceId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord inserted = mapper.selectById(3L);
            assertNotNull(inserted);
            assertEquals("320101199001011234", inserted.getIdCard());
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select id_card from user_account where id = 3")) {
            resultSet.next();
            originalReferenceId = resultSet.getString("id_card");
            assertNotNull(originalReferenceId);
            assertTrue(!originalReferenceId.isBlank());
        }

        UserRecord update = new UserRecord();
        update.setId(3L);
        update.setIdCard("320101199001019999");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updateIdCard(update));
            UserRecord loaded = mapper.selectById(3L);
            assertNotNull(loaded);
            assertEquals("320101199001019999", loaded.getIdCard());
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mainResult = statement.executeQuery("select id_card from user_account where id = 3")) {
            mainResult.next();
            assertEquals(originalReferenceId, mainResult.getString("id_card"));

            try (ResultSet encryptResult = statement.executeQuery(
                    "select id_card_cipher, id_card_hash from user_id_card_encrypt where id = " + originalReferenceId)) {
                encryptResult.next();
                assertNotEquals("320101199001019999", encryptResult.getString("id_card_cipher"));
                assertNotNull(encryptResult.getString("id_card_hash"));
            }
        }
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
            statement.execute("""
                    create table user_account (
                        id bigint primary key,
                        name varchar(64),
                        phone_cipher varchar(512),
                        phone_hash varchar(128),
                        phone_like varchar(255),
                        id_card bigint
                    )
                    """);
            statement.execute("""
                    create table user_id_card_encrypt (
                        id bigint auto_increment primary key,
                        id_card_cipher varchar(512),
                        id_card_hash varchar(128),
                        id_card_like varchar(255)
                    )
                    """);
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
