package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
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
 * 原地回填并解密返回对象中的独立表字段。相同独立表规则会先合并引用值再按批次查询，
 * 避免嵌套结果路径导致重复查询或单条 {@code IN (...)} 过长。</p>
 */
final class SeparateTableResultHydrator {

    private final DataSource dataSource;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableRuleSupport ruleSupport;
    private final int hydrationBatchSize;
    private final PropertyValueAccessor propertyValueAccessor = new PropertyValueAccessor();

    SeparateTableResultHydrator(DataSource dataSource,
                                EncryptMetadataRegistry metadataRegistry,
                                AlgorithmRegistry algorithmRegistry,
                                DatabaseEncryptionProperties properties,
                                SeparateTableRuleSupport ruleSupport) {
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.ruleSupport = ruleSupport;
        this.hydrationBatchSize = resolveHydrationBatchSize(properties);
    }

    void hydrateResults(Object resultObject) {
        if (resultObject == null) {
            return;
        }
        Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped =
                new LinkedHashMap<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectFallbackHydrationReferences(resultObject, grouped, visited);
        hydrateGroupedRules(grouped);
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

    private void collectFallbackHydrationReferences(Object candidate,
                                                    Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped,
                                                    Set<Object> visited) {
        if (candidate == null || ObjectTraversalUtils.isSimpleValueType(candidate.getClass())) {
            return;
        }
        if (candidate instanceof Map<?, ?>) {
            for (Object value : ((Map<?, ?>) candidate).values()) {
                collectFallbackHydrationReferences(value, grouped, visited);
            }
            return;
        }
        if (candidate instanceof Collection<?>) {
            for (Object value : (Collection<?>) candidate) {
                collectFallbackHydrationReferences(value, grouped, visited);
            }
            return;
        }
        if (candidate.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(candidate);
            for (int index = 0; index < length; index++) {
                collectFallbackHydrationReferences(java.lang.reflect.Array.get(candidate, index), grouped, visited);
            }
            return;
        }
        if (!visited.add(candidate)) {
            return;
        }
        // Fallback mode has no ResultMap plan, so it walks the object graph once and groups by storage rule.
        collectEntityHydrationReferences(candidate, grouped);
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (String getterName : metaObject.getGetterNames()) {
            if ("class".equals(getterName)) {
                continue;
            }
            collectFallbackHydrationReferences(metaObject.getValue(getterName), grouped, visited);
        }
    }

    private void collectEntityHydrationReferences(Object candidate,
                                                  Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped) {
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return;
        }
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!rule.isStoredInSeparateTable()) {
                continue;
            }
            PropertyValueAccessor.PropertyReference propertyReference =
                    propertyValueAccessor.resolve(candidate, rule.property());
            if (propertyReference == null || !propertyReference.canWrite()) {
                continue;
            }
            Object referenceId = propertyReference.getValue();
            if (referenceId != null) {
                addHydrationReference(grouped, rule, referenceId, propertyReference);
            }
        }
    }

    private boolean hydrateWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        boolean handled = false;
        Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped =
                new LinkedHashMap<>();
        for (Object candidate : ObjectTraversalUtils.topLevelResults(resultObject)) {
            if (candidate == null || ObjectTraversalUtils.isSimpleValueType(candidate.getClass())) {
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
                // Same storage rule may appear at different nested paths; batch them together.
                addHydrationReference(grouped, rule, referenceId, propertyReference);
            }
        }
        hydrateGroupedRules(grouped);
        return handled;
    }

    private void addHydrationReference(Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped,
                                       EncryptColumnRule rule,
                                       Object referenceId,
                                       PropertyValueAccessor.PropertyReference propertyReference) {
        Map<Object, List<PropertyValueAccessor.PropertyReference>> metaById =
                grouped.computeIfAbsent(rule, k -> new LinkedHashMap<>());
        Object normalizedReferenceId = ruleSupport.normalizeReferenceId(referenceId);
        List<PropertyValueAccessor.PropertyReference> propertyReferences =
                metaById.computeIfAbsent(normalizedReferenceId, k -> new ArrayList<>());
        propertyReferences.add(propertyReference);
    }

    private void hydrateGroupedRules(Map<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> grouped) {
        for (Map.Entry<EncryptColumnRule, Map<Object, List<PropertyValueAccessor.PropertyReference>>> entry : grouped.entrySet()) {
            hydrateRuleReferences(entry.getKey(), entry.getValue());
        }
    }

    private void hydrateRuleReferences(EncryptColumnRule rule,
                                       Map<Object, List<PropertyValueAccessor.PropertyReference>> propertyById) {
        if (propertyById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(rule, new ArrayList<>(propertyById.keySet()));
        for (Map.Entry<Object, String> entry : cipherById.entrySet()) {
            List<PropertyValueAccessor.PropertyReference> propertyReferences = propertyById.get(entry.getKey());
            if (propertyReferences == null || entry.getValue() == null) {
                continue;
            }
            String plainText = algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(entry.getValue());
            for (PropertyValueAccessor.PropertyReference propertyReference : propertyReferences) {
                if (propertyReference.setValue(plainText) && SensitiveDataContext.isRecording()) {
                    SensitiveDataContext.record(propertyReference.owner(), propertyReference.propertyName(),
                            plainText, rule);
                }
            }
        }
    }

    private Map<Object, String> loadCipherValues(EncryptColumnRule rule, List<Object> ids) {
        ruleSupport.requireAssistedReferenceRule(rule, "load separate-table encrypted values");
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        if (ids.size() <= hydrationBatchSize) {
            return loadCipherValuesBatch(rule, ids);
        }
        Map<Object, String> result = new LinkedHashMap<>();
        for (int start = 0; start < ids.size(); start += hydrationBatchSize) {
            int end = Math.min(start + hydrationBatchSize, ids.size());
            result.putAll(loadCipherValuesBatch(rule, ids.subList(start, end)));
        }
        return result;
    }

    private Map<Object, String> loadCipherValuesBatch(EncryptColumnRule rule, List<Object> ids) {
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

    private int resolveHydrationBatchSize(DatabaseEncryptionProperties properties) {
        if (properties == null || properties.getSeparateTableHydrationBatchSize() <= 0) {
            return 200;
        }
        return properties.getSeparateTableHydrationBatchSize();
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }
}
