package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptJsonField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptJsonPath;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("decrypt")
class EncryptJsonFieldResultDecryptorTest {

    @Test
    void shouldRestoreEncryptJsonFieldFromStoredHashJson() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:encrypt_json_result_decrypt;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        Sm3AssistedQueryAlgorithm sm3 = new Sm3AssistedQueryAlgorithm();
        String phone = "13800138000";
        String hash = sm3.transform(phone);
        String cipher = sm4.encrypt(phone);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table phone_encrypt (id bigint primary key, phone_hash varchar(64), phone_cipher varchar(512))");
            statement.execute("insert into phone_encrypt (id, phone_hash, phone_cipher) values (1, '"
                    + hash + "', '" + cipher + "')");
        }

        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setDefaultCipherKey("unit-test-key");
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader());
        AlgorithmRegistry algorithmRegistry = new AlgorithmRegistry(
                Map.of("sm4", sm4),
                Map.of("sm3", sm3),
                Collections.emptyMap()
        );
        SeparateTableEncryptionManager manager =
                new SeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
        ResultDecryptor decryptor = new ResultDecryptor(metadataRegistry, algorithmRegistry, manager);

        JsonUserEntity entity = new JsonUserEntity();
        entity.setProfileJson("{\"phone\":\"" + hash + "\",\"name\":\"Aster\"}");

        MappedStatement mappedStatement = mappedStatement();
        QueryResultPlan queryResultPlan = decryptor.resolvePlan(mappedStatement, mappedStatement.getBoundSql(null));
        decryptor.decrypt(Collections.singletonList(entity), queryResultPlan);

        assertTrue(entity.getProfileJson().contains("\"phone\":\"13800138000\""));
        assertTrue(entity.getProfileJson().contains("\"name\":\"Aster\""));
    }

    @SuppressWarnings("unused")
    private MappedStatement mappedStatement() {
        Configuration configuration = new Configuration();
        ResultMap resultMap = new ResultMap.Builder(configuration, "test.jsonEntity", JsonUserEntity.class,
                Collections.emptyList()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select profile_json from user_account", Collections.emptyList(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectJsonEntity", sqlSource, SqlCommandType.SELECT)
                .resultMaps(Collections.singletonList(resultMap))
                .build();
    }

    @EncryptTable("user_account")
    static class JsonUserEntity {

        @EncryptJsonField(
                column = "profile_json",
                paths = {
                        @EncryptJsonPath(
                                path = "$.phone",
                                storageTable = "phone_encrypt",
                                storageIdColumn = "id",
                                hashColumn = "phone_hash",
                                cipherColumn = "phone_cipher"
                        )
                }
        )
        private String profileJson;

        public String getProfileJson() {
            return profileJson;
        }

        public void setProfileJson(String profileJson) {
            this.profileJson = profileJson;
        }
    }
}
