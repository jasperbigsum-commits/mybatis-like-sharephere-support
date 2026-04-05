package tech.jasper.mybatis.encrypt.core.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;

class SqlRewriteEngineTest {

    @Test
    void shouldRewriteInsertWithCipherAndShadowColumns() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        Map<String, Object> parameterObject = Map.of("phone", "13800138000", "name", "Alice");
        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account (phone, name) VALUES (?, ?)",
                List.of(
                        new ParameterMapping.Builder(configuration, "phone", String.class).build(),
                        new ParameterMapping.Builder(configuration, "name", String.class).build()
                ),
                parameterObject
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.INSERT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_hash`"));
        assertTrue(result.sql().contains("`phone_like`"));
        result.applyTo(boundSql);
        assertEquals(4, boundSql.getParameterMappings().size());
    }

    @Test
    void shouldRewriteSelectEqualityToAssistedColumn() {
        Configuration configuration = new Configuration();
        DatabaseEncryptionProperties properties = sampleProperties();
        SqlRewriteEngine engine = new SqlRewriteEngine(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                sampleAlgorithms(),
                properties
        );

        Map<String, Object> parameterObject = Map.of("phone", "13800138000");
        BoundSql boundSql = new BoundSql(
                configuration,
                "SELECT id, phone FROM user_account WHERE phone = ?",
                List.of(new ParameterMapping.Builder(configuration, "phone", String.class).build()),
                parameterObject
        );

        RewriteResult result = engine.rewrite(mappedStatement(configuration, SqlCommandType.SELECT, Map.class), boundSql);

        assertTrue(result.changed());
        assertTrue(result.sql().contains("`phone_hash` = ?"));
        assertEquals(1, result.maskedParameters().size());
    }

    private DatabaseEncryptionProperties sampleProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setColumn("phone");
        fieldRule.setAssistedQueryColumn("phone_hash");
        fieldRule.setLikeQueryColumn("phone_like");
        tableRule.getFields().put("phone", fieldRule);
        properties.getTables().put("userAccount", tableRule);
        properties.setDefaultCipherKey("unit-test-key");
        return properties;
    }

    private AlgorithmRegistry sampleAlgorithms() {
        return new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("unit-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
        );
    }

    private MappedStatement mappedStatement(Configuration configuration, SqlCommandType commandType, Class<?> parameterType) {
        SqlSource sqlSource = parameterObject -> null;
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, "pm", parameterType, List.of()).build();
        ResultMap resultMap = new ResultMap.Builder(configuration, "rm", Map.class, List.of()).build();
        return new MappedStatement.Builder(configuration, "test." + commandType.name().toLowerCase(), sqlSource, commandType)
                .parameterMap(parameterMap)
                .resultMaps(List.of(resultMap))
                .build();
    }
}
