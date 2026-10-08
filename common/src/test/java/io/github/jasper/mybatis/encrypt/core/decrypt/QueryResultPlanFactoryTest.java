package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryResultPlanFactoryTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decryptsJoinedFieldsThroughOraclePaginationAndAdditionalAnonymousLayers(boolean snakeCase) {
        String name = snakeCase ? "borrower_name" : "borrowerName";
        String id = snakeCase ? "borrower_id_card_no" : "borrowerIdCardNo";
        String sql = "select p.*, b.real_name_cipher as " + name + ", b.id_card_no_cipher as " + id
                + " from project_info p left join borrower b on p.borrower_id = b.id";
        sql = "select * from (select TMP.*, ROWNUM ROW_ID from (" + sql
                + ") TMP where ROWNUM <= ?) where ROW_ID > ?";
        for (int depth = 0; depth < 4; depth++) {
            QueryResultPlan plan = plan(sql, ProjectView.class);
            assertNotNull(plan.findPlan(ProjectView.class), "Missing joined projection plan at depth " + depth);
            assertEquals(Arrays.asList("borrowerName", "borrowerIdCardNo"), plan.findPlan(ProjectView.class)
                    .getPropertyPlans().stream().map(QueryResultPlan.PropertyPlan::getPropertyPath)
                    .collect(Collectors.toList()));
            assertTrue(plan.findPlan(ProjectView.class).getPropertyPlans().stream()
                    .allMatch(property -> "borrower".equals(property.getRule().table())));
            Sm4CipherAlgorithm cipher = new Sm4CipherAlgorithm("pagination-test-key");
            ProjectView view = new ProjectView();
            view.borrowerName = cipher.encrypt("测试借款人");
            view.borrowerIdCardNo = cipher.encrypt("320101199001011234");
            decryptor(cipher).decrypt(Collections.singletonList(view), plan);
            assertEquals("测试借款人", view.borrowerName);
            assertEquals("320101199001011234", view.borrowerIdCardNo);
            sql = "select * from (" + sql + ")";
        }
    }

    @Test
    void tracesRenamedColumnsAcrossMixedNamedAndAnonymousScopesIntoMap() {
        String sql = "select renamed as borrowerName from (select T.person as renamed from "
                + "(select person from (select b.real_name_cipher as person from borrower b)) T)";
        QueryResultPlan plan = plan(sql, Map.class);
        Sm4CipherAlgorithm cipher = new Sm4CipherAlgorithm("pagination-test-key");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("borrowerName", cipher.encrypt("测试借款人"));
        decryptor(cipher).decrypt(Collections.singletonList(row), plan);
        assertEquals("测试借款人", row.get("borrowerName"));
        assertEquals(1, plan.findPlan(Map.class).getPropertyPlans().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select borrowerName from (select id from (select b.id, b.real_name_cipher as borrowerName from borrower b))",
            "select b.real_name_cipher as borrowerName from (select b.real_name_cipher as borrowerName from borrower b)",
            "select * from (select substring(b.real_name_cipher, 1, 3) as borrowerName from borrower b)",
            "select * from (select b.real_name_cipher as borrowerName from borrower b) join project_info p on 1 = 1",
            "select borrowerName from (select b.real_name_cipher as borrowerName from borrower b) join (select b.real_name_cipher as borrowerName from borrower b) on 1 = 1"
    })
    void doesNotGuessAcrossMissingProjectionsExpressionsOrAnonymousJoinSources(String sql) {
        assertTrue(plan(sql, ProjectView.class).isEmpty());
    }

    private QueryResultPlan plan(String sql, Class<?> resultType) {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        MappedStatement statement = new MappedStatement.Builder(configuration, "probe.select",
                new StaticSqlSource(configuration, sql), SqlCommandType.SELECT)
                .resultMaps(Collections.singletonList(new ResultMap.Builder(configuration, "probe.result",
                        resultType, Collections.emptyList()).build())).build();
        return new QueryResultPlanFactory(registry()).resolve(statement, statement.getBoundSql(null));
    }

    private EncryptMetadataRegistry registry() {
        EncryptMetadataRegistry registry = new EncryptMetadataRegistry(new DatabaseEncryptionProperties(),
                new AnnotationEncryptMetadataLoader());
        registry.registerEntityType(Borrower.class);
        return registry;
    }

    private ResultDecryptor decryptor(Sm4CipherAlgorithm cipher) {
        return new ResultDecryptor(registry(), new AlgorithmRegistry(Collections.singletonMap("sm4", cipher),
                Collections.emptyMap(), Collections.emptyMap()), null);
    }

    @EncryptTable("borrower")
    static class Borrower {
        @EncryptField(storageColumn = "real_name_cipher", assistedQueryColumn = "real_name")
        private String realName;
        @EncryptField(storageColumn = "id_card_no_cipher", assistedQueryColumn = "id_card_no")
        private String idCardNo;
    }

    static class ProjectView {
        public String borrowerName;
        public String borrowerIdCardNo;
    }
}
