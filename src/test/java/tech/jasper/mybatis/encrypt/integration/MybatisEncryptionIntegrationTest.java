package tech.jasper.mybatis.encrypt.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import tech.jasper.mybatis.encrypt.annotation.EncryptField;
import tech.jasper.mybatis.encrypt.annotation.EncryptTable;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import tech.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import tech.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import tech.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import tech.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;

class MybatisEncryptionIntegrationTest {

    private JdbcDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:encrypt_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema();
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
    void shouldSynchronizeSeparateTableAndHydrateResult() throws Exception {
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
             ResultSet resultSet = statement.executeQuery(
                     "select user_id, id_card_cipher, id_card_hash from user_id_card_encrypt where user_id = 2")) {
            resultSet.next();
            assertEquals(2L, resultSet.getLong("user_id"));
            assertNotEquals("320101199001011234", resultSet.getString("id_card_cipher"));
            assertNotNull(resultSet.getString("id_card_hash"));
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
        SeparateTableEncryptionManager separateTableManager =
                new SeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
        ResultDecryptor resultDecryptor = new ResultDecryptor(metadataRegistry, algorithmRegistry, separateTableManager);
        SqlRewriteEngine sqlRewriteEngine = new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
        DatabaseEncryptionInterceptor interceptor =
                new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties, separateTableManager);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(interceptor);
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
                        phone_like varchar(255)
                    )
                    """);
            statement.execute("""
                    create table user_id_card_encrypt (
                        user_id bigint primary key,
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
                sourceIdProperty = "id",
                sourceIdColumn = "id",
                storageIdColumn = "user_id",
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
}
