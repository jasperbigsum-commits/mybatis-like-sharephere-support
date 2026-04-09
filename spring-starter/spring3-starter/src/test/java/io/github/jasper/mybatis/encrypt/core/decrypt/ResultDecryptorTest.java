package io.github.jasper.mybatis.encrypt.core.decrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
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
}
