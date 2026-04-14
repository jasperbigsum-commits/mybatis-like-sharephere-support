package io.github.jasper.mybatis.encrypt.core.decrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;

class ResultDecryptorTest {

    @Test
    void shouldDecryptAnnotatedEntityCollection() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = new ResultDecryptor(
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader()),
                new AlgorithmRegistry(
                        Map.of("sm4", sm4),
                        Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                        Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
                ),
                null
        );

        UserEntity entity = new UserEntity();
        entity.setPhone(sm4.encrypt("13800138000"));
        entity.setName("jasper");

        decryptor.decrypt(List.of(entity));

        assertEquals("13800138000", entity.getPhone());
        assertEquals("jasper", entity.getName());
    }

    @Test
    void shouldDecryptFieldLevelAnnotatedDtoWithoutEncryptTable() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = new ResultDecryptor(
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader()),
                new AlgorithmRegistry(
                        Map.of("sm4", sm4),
                        Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                        Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
                ),
                null
        );

        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13900139000"));
        dto.setName("nora");

        decryptor.decrypt(List.of(dto));

        assertEquals("13900139000", dto.getPhone());
        assertEquals("nora", dto.getName());
    }

    @Test
    void shouldDecryptMappedNestedPropertyWithoutTraversingUnmappedGetter() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = new ResultDecryptor(
                new EncryptMetadataRegistry(new DatabaseEncryptionProperties(), new AnnotationEncryptMetadataLoader()),
                new AlgorithmRegistry(
                        Map.of("sm4", sm4),
                        Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                        Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
                ),
                null
        );
        ProjectionWrapper wrapper = new ProjectionWrapper();
        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13700137000"));
        wrapper.setUser(dto);
        MappedStatement mappedStatement = mappedStatement();

        decryptor.beginQueryScope(mappedStatement);
        try {
            assertDoesNotThrow(() -> decryptor.decrypt(List.of(wrapper)));
        } finally {
            decryptor.endQueryScope();
        }

        assertEquals("13700137000", wrapper.getUser().getPhone());
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
}
