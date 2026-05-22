package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonFieldRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class EncryptJsonRuntimeSupportTest {

    @Test
    void shouldReplaceJsonSensitivePathWithHashOnWrite() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        Sm3AssistedQueryAlgorithm sm3 = new Sm3AssistedQueryAlgorithm();
        EncryptJsonRuntimeSupport support = new EncryptJsonRuntimeSupport();
        EncryptJsonFieldRule rule = sampleJsonFieldRule();

        EncryptJsonWriteResult result = support.rewriteJsonForWrite(
                "{\"phone\":\"13800138000\",\"name\":\"Aster\"}",
                rule,
                algorithms(sm4, sm3)
        );

        String expectedHash = sm3.transform("13800138000");
        assertTrue(result.rewrittenJson().contains("\"phone\":\"" + expectedHash + "\""));
        assertTrue(result.rewrittenJson().contains("\"name\":\"Aster\""));
        assertEquals(1, result.pathWrites().size());
        assertEquals("$.phone", result.pathWrites().get(0).pathRule().path());
        assertEquals("phone_encrypt", result.pathWrites().get(0).pathRule().storageTable());
        assertEquals(expectedHash, result.pathWrites().get(0).hashValue());
        assertEquals("13800138000", sm4.decrypt(result.pathWrites().get(0).cipherValue()));
    }

    @Test
    void shouldRestoreJsonHashBackToPlaintext() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        Sm3AssistedQueryAlgorithm sm3 = new Sm3AssistedQueryAlgorithm();
        EncryptJsonRuntimeSupport support = new EncryptJsonRuntimeSupport();
        EncryptJsonFieldRule rule = sampleJsonFieldRule();
        String hash = sm3.transform("13800138000");
        String cipher = sm4.encrypt("13800138000");
        Map<String, String> cipherByHash = new LinkedHashMap<String, String>();
        cipherByHash.put(hash, cipher);

        String restored = support.restoreJsonFromHashes(
                "{\"phone\":\"" + hash + "\",\"name\":\"Aster\"}",
                rule,
                new EncryptJsonCipherLookup() {
                    @Override
                    public String findCipher(EncryptJsonPathRule pathRule, String currentHashValue) {
                        return cipherByHash.get(currentHashValue);
                    }
                },
                algorithms(sm4, sm3)
        );

        assertTrue(restored.contains("\"phone\":\"13800138000\""));
        assertTrue(restored.contains("\"name\":\"Aster\""));
    }

    @Test
    void shouldLeaveMissingJsonPathUntouched() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        Sm3AssistedQueryAlgorithm sm3 = new Sm3AssistedQueryAlgorithm();
        EncryptJsonRuntimeSupport support = new EncryptJsonRuntimeSupport();
        EncryptJsonFieldRule rule = sampleJsonFieldRule();

        EncryptJsonWriteResult result = support.rewriteJsonForWrite(
                "{\"name\":\"Aster\"}",
                rule,
                algorithms(sm4, sm3)
        );

        assertEquals("{\"name\":\"Aster\"}", result.rewrittenJson());
        assertTrue(result.pathWrites().isEmpty());
    }

    @Test
    void shouldRegisterPreparedJsonPathWritesWhenRewriteUsesJdbcParameter() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        Sm3AssistedQueryAlgorithm sm3 = new Sm3AssistedQueryAlgorithm();
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(
                configuration,
                "INSERT INTO user_account(profile_json) VALUES (?)",
                Collections.singletonList(new ParameterMapping.Builder(configuration, "profileJson", String.class).build()),
                Collections.<String, Object>singletonMap("profileJson", "{\"phone\":\"13800138000\"}")
        );
        SqlRewriteContext context = new SqlRewriteContext(configuration, boundSql, new ParameterValueResolver());
        SqlWriteExpressionRewriter rewriter = new SqlWriteExpressionRewriter(
                new EncryptionValueTransformer(algorithms(sm4, sm3)),
                new SqlConditionRewriter(
                        new EncryptionValueTransformer(algorithms(sm4, sm3)),
                        (column, target) -> column,
                        (rule, scenario) -> rule.assistedQueryColumn(),
                        (rule, scenario) -> rule.likeQueryColumn(),
                        identifier -> identifier,
                        (select, rewriteContext, projectionMode, outerTableContext) -> {
                        }
                )
        );

        rewriter.rewriteEncryptJson(new net.sf.jsqlparser.expression.JdbcParameter(),
                sampleJsonFieldRule(), context, algorithms(sm4, sm3));

        @SuppressWarnings("unchecked")
        List<EncryptJsonWriteResult.PathWrite> pathWrites =
                (List<EncryptJsonWriteResult.PathWrite>) boundSql.getAdditionalParameter(
                        ParameterValueResolver.PREPARED_JSON_PATH_WRITES_PARAMETER);
        assertEquals(1, pathWrites.size());
        assertEquals("$.phone", pathWrites.get(0).pathRule().path());
    }

    private EncryptJsonFieldRule sampleJsonFieldRule() {
        return new EncryptJsonFieldRule(
                "profileJson",
                "user_account",
                "profile_json",
                "sm4",
                "sm3",
                Collections.singletonList(new EncryptJsonPathRule(
                        "$.phone",
                        "phone_encrypt",
                        "id",
                        "phone_hash",
                        "phone_cipher",
                        "sm4",
                        "sm3"
                ))
        );
    }

    private AlgorithmRegistry algorithms(CipherAlgorithm cipherAlgorithm,
                                         Sm3AssistedQueryAlgorithm assistedQueryAlgorithm) {
        return new AlgorithmRegistry(
                Collections.singletonMap("sm4", cipherAlgorithm),
                Collections.singletonMap("sm3", assistedQueryAlgorithm),
                Collections.emptyMap()
        );
    }
}
