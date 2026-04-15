package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * migration JDBC 测试公共支撑，集中管理样例实体、临时数据源和属性文件工具。
 */
abstract class MigrationJdbcTestSupport {

    protected DataSource newDataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    protected void executeSql(DataSource dataSource, String... sqlList) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : sqlList) {
                statement.execute(sql);
            }
        }
    }

    protected Path createTempDirectory(String prefix) throws Exception {
        return Files.createTempDirectory(prefix);
    }

    protected Properties loadSinglePropertiesFile(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return loadProperties(files.findFirst().orElseThrow(AssertionError::new));
        }
    }

    protected Properties loadProperties(Path file) throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
        }
        return properties;
    }

    protected void storeProperties(Path file, Properties properties, String comment) throws Exception {
        Files.createDirectories(file.getParent());
        try (OutputStream outputStream = Files.newOutputStream(file)) {
            properties.store(outputStream, comment);
        }
    }

    protected Path confirmationFile(Path confirmationDir, EntityMigrationPlan plan) {
        return confirmationDir.resolve(
                plan.getEntityName().replaceAll("[^a-zA-Z0-9._-]", "_")
                        + "__" + plan.getTableName().replaceAll("[^a-zA-Z0-9._-]", "_")
                        + ".confirm.properties");
    }

    protected Properties confirmationProperties(MigrationRiskManifest manifest, boolean approved) {
        Properties properties = new Properties();
        properties.setProperty("approved", Boolean.toString(approved));
        properties.setProperty("entityName", manifest.getEntityName());
        properties.setProperty("tableName", manifest.getTableName());
        for (int cursorIndex = 0; cursorIndex < manifest.getCursorColumns().size(); cursorIndex++) {
            properties.setProperty("cursorColumns." + cursorIndex, manifest.getCursorColumns().get(cursorIndex));
        }
        int index = 1;
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            properties.setProperty("entry." + index++, entry.asToken());
        }
        return properties;
    }

    protected EncryptMetadataRegistry metadataRegistry() {
        return new EncryptMetadataRegistry(properties(), new AnnotationEncryptMetadataLoader());
    }

    protected DatabaseEncryptionProperties properties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setDefaultCipherKey("unit-test-key-123");
        return properties;
    }

    protected DatabaseEncryptionProperties configuredProperties() {
        DatabaseEncryptionProperties properties = properties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule =
                new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties phoneRule =
                new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setColumn("phone");
        phoneRule.setStorageColumn("phone_cipher");
        phoneRule.setAssistedQueryColumn("phone_hash");
        phoneRule.setLikeQueryColumn("phone_like");
        tableRule.getFields().add(phoneRule);
        properties.getTables().add(tableRule);
        return properties;
    }

    protected AlgorithmRegistry algorithmRegistry() {
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm> cipherAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm>();
        cipherAlgorithms.put("sm4", new Sm4CipherAlgorithm("unit-test-key-123"));
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm> assistedAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm>();
        assistedAlgorithms.put("sm3", new Sm3AssistedQueryAlgorithm());
        Map<String, io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm> likeAlgorithms =
                new LinkedHashMap<String, io.github.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm>();
        likeAlgorithms.put("normalizedLike", new NormalizedLikeQueryAlgorithm());
        return new AlgorithmRegistry(cipherAlgorithms, assistedAlgorithms, likeAlgorithms);
    }

    @EncryptTable("user_account")
    static class SameTableUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;
    }

    @EncryptTable("user_account")
    static class SeparateTableUserEntity {

        private Long id;

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
    }

    @EncryptTable("user_account")
    static class HashOverwriteUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone",
                likeQueryColumn = "phone_like"
        )
        private String phone;
    }

    @EncryptTable("user_account")
    static class LikeOverwriteUserEntity {

        private Long id;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone"
        )
        private String phone;
    }

    static class MultiTableDto {

        private Long id;

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher"
        )
        private String phone;

        @EncryptField(
                table = "user_archive",
                column = "archive_phone",
                storageColumn = "archive_phone_cipher"
        )
        private String archivePhone;
    }
}
