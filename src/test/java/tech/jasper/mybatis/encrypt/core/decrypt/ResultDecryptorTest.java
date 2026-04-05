package tech.jasper.mybatis.encrypt.core.decrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import tech.jasper.mybatis.encrypt.annotation.EncryptField;
import tech.jasper.mybatis.encrypt.annotation.EncryptTable;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

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

        decryptor.decrypt(List.of(entity));

        assertEquals("13800138000", entity.getPhone());
    }

    @EncryptTable("user_account")
    static class UserEntity {

        @EncryptField(column = "phone")
        private String phone;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }
}
