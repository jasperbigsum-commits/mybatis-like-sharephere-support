package io.github.jasper.mybatis.encrypt.core.decrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptResultHint;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;

class ResultDecryptorTest {

    @Test
    void shouldDecryptAnnotatedEntityCollection() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());

        UserEntity entity = new UserEntity();
        entity.setPhone(sm4.encrypt("13800138000"));
        entity.setName("jasper");

        MappedStatement mappedStatement = entityMappedStatement(UserEntity.class, "test.selectEntity");
        decrypt(decryptor, mappedStatement, List.of(entity));

        assertEquals("13800138000", entity.getPhone());
        assertEquals("jasper", entity.getName());
    }

    @Test
    void shouldDecryptFieldLevelAnnotatedDtoWithoutEncryptTable() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());

        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13900139000"));
        dto.setName("nora");

        MappedStatement mappedStatement = entityMappedStatement(UserProjectionDto.class, "test.selectAnnotatedDto");
        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13900139000", dto.getPhone());
        assertEquals("nora", dto.getName());
    }

    @Test
    void shouldDecryptMappedNestedPropertyWithoutTraversingUnmappedGetter() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        ProjectionWrapper wrapper = new ProjectionWrapper();
        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13700137000"));
        wrapper.setUser(dto);
        MappedStatement mappedStatement = mappedStatement();

        assertDoesNotThrow(() -> decrypt(decryptor, mappedStatement, List.of(wrapper)));

        assertEquals("13700137000", wrapper.getUser().getPhone());
    }

    @Test
    void shouldDecryptConfiguredDtoWithoutEncryptFieldByExplicitResultMapping() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13600136000"));
        dto.setName("iris");
        MappedStatement mappedStatement = configuredDtoMappedStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13600136000", dto.getPhone());
        assertEquals("iris", dto.getName());
    }

    @Test
    void shouldDecryptConfiguredDtoWithoutEncryptFieldByAutoMappedProjectionAlias() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13500135000"));
        dto.setName("zoe");
        MappedStatement mappedStatement = autoMappedConfiguredDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13500135000", dto.getPhone());
        assertEquals("zoe", dto.getName());
    }

    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByMethodHintAndAutoMappedProjection() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13400134000"));
        dto.setName("maya");
        MappedStatement mappedStatement = hintedAutoMappedDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13400134000", dto.getPhone());
        assertEquals("maya", dto.getName());
    }

    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByMethodHintAndSelectAll() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13300133000"));
        dto.setName("wildcard");
        MappedStatement mappedStatement = hintedSelectAllDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13300133000", dto.getPhone());
        assertEquals("wildcard", dto.getName());
    }

    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByAutoDetectedSqlSourceTable() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13200132000"));
        dto.setName("auto");
        MappedStatement mappedStatement = autoDetectedDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13200132000", dto.getPhone());
        assertEquals("auto", dto.getName());
    }

    private void decrypt(ResultDecryptor decryptor, MappedStatement mappedStatement, Object resultObject) {
        BoundSql boundSql = mappedStatement.getBoundSql(null);
        QueryResultPlan queryResultPlan = decryptor.resolvePlan(mappedStatement, boundSql);
        decryptor.decrypt(resultObject, queryResultPlan);
    }

    private ResultDecryptor createDecryptor(Sm4CipherAlgorithm sm4, DatabaseEncryptionProperties properties) {
        return new ResultDecryptor(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                new AlgorithmRegistry(
                        Map.of("sm4", sm4),
                        Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                        Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
                ),
                null
        );
    }

    private MappedStatement mappedStatement() {
        Configuration configuration = new Configuration();
        ResultMapping phoneMapping = new ResultMapping.Builder(configuration, "user.phone", "phone", String.class).build();
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.wrapperMap", ProjectionWrapper.class, List.of(phoneMapping)).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, "select phone from user_account", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectWrapper", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement entityMappedStatement(Class<?> resultType, String id) {
        Configuration configuration = new Configuration();
        ResultMap resultMap = new ResultMap.Builder(configuration, id + ".rm", resultType, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, "select * from user_account", List.of(),
                parameterObject);
        return new MappedStatement.Builder(configuration, id, sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement configuredDtoMappedStatement() {
        Configuration configuration = new Configuration();
        ResultMapping idMapping = new ResultMapping.Builder(configuration, "id", "id", Long.class).build();
        ResultMapping nameMapping = new ResultMapping.Builder(configuration, "name", "name", String.class).build();
        ResultMapping phoneMapping = new ResultMapping.Builder(configuration, "phone", "phone_value", String.class).build();
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.configuredDtoMap", PlainUserProjectionDto.class,
                List.of(idMapping, nameMapping, phoneMapping)).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone_value from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectConfiguredDto", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement autoMappedConfiguredDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.autoMappedConfiguredDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectAutoMappedConfiguredDto", sqlSource,
                SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement hintedAutoMappedDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.hintedAutoMappedDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, HintMapper.class.getName() + ".selectPlainUserProjection",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement hintedSelectAllDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.hintedSelectAllDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select * from user_account", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, HintMapper.class.getName() + ".selectPlainUserByWildcard",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement autoDetectedDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.autoDetectedDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPlainUserProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private DatabaseEncryptionProperties configuredUserTableProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setColumn("phone");
        phoneRule.setProperty("phone");
        phoneRule.setStorageColumn("phone");
        phoneRule.setCipherAlgorithm("sm4");
        tableRule.setFields(List.of(phoneRule));
        properties.setTables(List.of(tableRule));
        assertNotNull(properties.getTables());
        return properties;
    }

    @EncryptTable("user_account")
    static class UserEntity {

        @EncryptField(column = "phone")
        private String phone;

        private String name;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class UserProjectionDto {

        @EncryptField(table = "user_account", column = "phone")
        private String phone;

        private String name;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class PlainUserProjectionDto {

        private Long id;
        private String phone;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class ProjectionWrapper {

        private UserProjectionDto user;

        public UserProjectionDto getUser() {
            return user;
        }

        public void setUser(UserProjectionDto user) {
            this.user = user;
        }

        public String getProblematic() {
            throw new IllegalStateException("unmapped getter should not be traversed");
        }
    }

    interface HintMapper {

        @EncryptResultHint(entities = UserEntity.class)
        PlainUserProjectionDto selectPlainUserProjection();

        @EncryptResultHint(entities = UserEntity.class)
        PlainUserProjectionDto selectPlainUserByWildcard();
    }

    interface AutoDetectedMapper {

        PlainUserProjectionDto selectPlainUserProjection();

        int insertUser(UserEntity user);
    }
}
