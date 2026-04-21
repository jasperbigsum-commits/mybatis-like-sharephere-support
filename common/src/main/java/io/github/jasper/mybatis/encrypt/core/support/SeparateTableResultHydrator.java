package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.ObjectTraversalUtils;
import io.github.jasper.mybatis.encrypt.util.PropertyValueAccessor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 独立表查询结果回填器。
 *
 * <p>负责根据主表里保存的 hash 引用值批量回查独立表密文，再按查询结果计划或实体规则
 * 原地回填并解密返回对象中的独立表字段。</p>
 */
final class SeparateTableResultHydrator {

    private final DataSource dataSource;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableRuleSupport ruleSupport;
    private final PropertyValueAccessor propertyValueAccessor = new PropertyValueAccessor();

    SeparateTableResultHydrator(DataSource dataSource,
                                EncryptMetadataRegistry metadataRegistry,
                                AlgorithmRegistry algorithmRegistry,
                                SeparateTableRuleSupport ruleSupport) {
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.ruleSupport = ruleSupport;
    }

    void hydrateResults(Object resultObject) {
        if (resultObject == null) {
            return;
        }
        hydrateCollection(collectHydrationCandidates(resultObject));
    }

    void hydrateResults(Object resultObject, QueryResultPlan queryResultPlan) {
        if (resultObject == null) {
            return;
        }
        if (queryResultPlan == null) {
            hydrateResults(resultObject);
            return;
        }
        hydrateWithPlan(resultObject, queryResultPlan);
    }

    private void hydrateCollection(Collection<?> results) {
        Map<Class<?>, List<Object>> groups = new LinkedHashMap<>();
        for (Object candidate : results) {
            if (candidate == null || candidate instanceof Map<?, ?>) {
                continue;
            }
            List<Object> sameType = groups.get(candidate.getClass());
            if (sameType == null) {
                sameType = new ArrayList<>();
                groups.put(candidate.getClass(), sameType);
            }
            sameType.add(candidate);
        }
        for (Map.Entry<Class<?>, List<Object>> entry : groups.entrySet()) {
            EncryptTableRule tableRule = metadataRegistry.findByEntity(entry.getKey()).orElse(null);
            if (tableRule == null) {
                continue;
            }
            for (EncryptColumnRule rule : tableRule.getColumnRules()) {
                if (!rule.isStoredInSeparateTable()) {
                    continue;
                }
                hydrateRule(entry.getValue(), rule);
            }
        }
    }

    private List<Object> collectHydrationCandidates(Object resultObject) {
        List<Object> results = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectHydrationCandidates(resultObject, results, visited);
        return results;
    }

    private void collectHydrationCandidates(Object candidate, List<Object> results, Set<Object> visited) {
        if (candidate == null || ObjectTraversalUtils.isSimpleValueType(candidate.getClass())) {
            return;
        }
        if (candidate instanceof Map<?, ?>) {
            for (Object value : ((Map<?, ?>) candidate).values()) {
                collectHydrationCandidates(value, results, visited);
            }
            return;
        }
        if (candidate instanceof Collection<?>) {
            for (Object value : (Collection<?>) candidate) {
                collectHydrationCandidates(value, results, visited);
            }
            return;
        }
        if (candidate.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(candidate);
            for (int index = 0; index < length; index++) {
                collectHydrationCandidates(java.lang.reflect.Array.get(candidate, index), results, visited);
            }
            return;
        }
        if (!visited.add(candidate)) {
            return;
        }
        if (metadataRegistry.findByEntity(candidate.getClass()).isPresent()) {
            results.add(candidate);
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (String getterName : metaObject.getGetterNames()) {
            if ("class".equals(getterName)) {
                continue;
            }
            collectHydrationCandidates(metaObject.getValue(getterName), results, visited);
        }
    }

    private void hydrateRule(List<?> candidates, EncryptColumnRule rule) {
        Map<Object, PropertyValueAccessor.PropertyReference> propertyById = new LinkedHashMap<>();
        for (Object candidate : candidates) {
            PropertyValueAccessor.PropertyReference propertyReference =
                    propertyValueAccessor.resolve(candidate, rule.property());
            if (propertyReference == null || !propertyReference.canWrite()) {
                continue;
            }
            Object referenceId = propertyReference.getValue();
            if (referenceId != null) {
                propertyById.put(ruleSupport.normalizeReferenceId(referenceId), propertyReference);
            }
        }
        if (propertyById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(rule, new ArrayList<>(propertyById.keySet()));
        for (Map.Entry<Object, String> entry : cipherById.entrySet()) {
            PropertyValueAccessor.PropertyReference propertyReference = propertyById.get(entry.getKey());
            if (propertyReference != null && entry.getValue() != null) {
                String plainText = algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(entry.getValue());
                if (propertyReference.setValue(plainText) && SensitiveDataContext.isRecording()) {
                    SensitiveDataContext.record(propertyReference.owner(), propertyReference.propertyName(), plainText, rule);
                }
            }
        }
    }

    private boolean hydrateWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        boolean handled = false;
        Map<HydrationKey, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped =
                new LinkedHashMap<>();
        for (Object candidate : ObjectTraversalUtils.topLevelResults(resultObject)) {
            if (candidate == null || candidate instanceof Map<?, ?> || ObjectTraversalUtils.isSimpleValueType(candidate.getClass())) {
                continue;
            }
            QueryResultPlan.TypePlan typePlan = queryResultPlan.findPlan(candidate.getClass());
            if (typePlan == null) {
                continue;
            }
            handled = true;
            for (QueryResultPlan.PropertyPlan propertyPlan : typePlan.getPropertyPlans()) {
                EncryptColumnRule rule = propertyPlan.getRule();
                if (!rule.isStoredInSeparateTable()) {
                    continue;
                }
                String propertyPath = propertyPlan.getPropertyPath();
                PropertyValueAccessor.PropertyReference propertyReference =
                        propertyValueAccessor.resolve(candidate, propertyPath);
                if (propertyReference == null || !propertyReference.canWrite()) {
                    continue;
                }
                Object referenceId = propertyReference.getValue();
                if (referenceId == null) {
                    continue;
                }
                HydrationKey hydrationKey = new HydrationKey(rule, propertyPath);
                Map<Object, List<PropertyValueAccessor.PropertyReference>> metaById = grouped.computeIfAbsent(hydrationKey, k -> new LinkedHashMap<>());
                Object normalizedReferenceId = ruleSupport.normalizeReferenceId(referenceId);
                List<PropertyValueAccessor.PropertyReference> propertyReferences =
                        metaById.computeIfAbsent(normalizedReferenceId, k -> new ArrayList<>());
                propertyReferences.add(propertyReference);
            }
        }
        for (Map.Entry<HydrationKey, Map<Object, List<PropertyValueAccessor.PropertyReference>>> entry : grouped.entrySet()) {
            hydratePlannedRule(entry.getKey(), entry.getValue());
        }
        return handled;
    }

    private void hydratePlannedRule(HydrationKey hydrationKey,
                                    Map<Object, List<PropertyValueAccessor.PropertyReference>> propertyById) {
        if (propertyById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(hydrationKey.rule(), new ArrayList<>(propertyById.keySet()));
        for (Map.Entry<Object, String> entry : cipherById.entrySet()) {
            List<PropertyValueAccessor.PropertyReference> propertyReferences = propertyById.get(entry.getKey());
            if (propertyReferences == null || entry.getValue() == null) {
                continue;
            }
            String plainText = algorithmRegistry.cipher(hydrationKey.rule().cipherAlgorithm()).decrypt(entry.getValue());
            for (PropertyValueAccessor.PropertyReference propertyReference : propertyReferences) {
                if (propertyReference.setValue(plainText) && SensitiveDataContext.isRecording()) {
                    SensitiveDataContext.record(propertyReference.owner(), propertyReference.propertyName(),
                            plainText, hydrationKey.rule());
                }
            }
        }
    }

    private Map<Object, String> loadCipherValues(EncryptColumnRule rule, List<Object> ids) {
        ruleSupport.requireAssistedReferenceRule(rule, "load separate-table encrypted values");
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        String sql = "select " + ruleSupport.quote(rule.assistedQueryColumn()) + ", " + ruleSupport.quote(rule.storageColumn())
                + " from " + ruleSupport.quote(rule.storageTable())
                + " where " + ruleSupport.quote(rule.assistedQueryColumn()) + " in (" + placeholders + ")";
        Map<Object, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, ids);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(ruleSupport.normalizeReferenceId(resultSet.getObject(1)), resultSet.getString(2));
                }
            }
            return result;
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to load separate-table encrypted values.", ex);
        }
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }

    private static final class HydrationKey {

        private final EncryptColumnRule rule;
        private final String propertyPath;

        private HydrationKey(EncryptColumnRule rule, String propertyPath) {
            this.rule = rule;
            this.propertyPath = propertyPath;
        }

        private EncryptColumnRule rule() {
            return rule;
        }

        private String propertyPath() {
            return propertyPath;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HydrationKey)) {
                return false;
            }
            HydrationKey that = (HydrationKey) other;
            return java.util.Objects.equals(rule, that.rule)
                    && java.util.Objects.equals(propertyPath, that.propertyPath);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(rule, propertyPath);
        }
    }
}
