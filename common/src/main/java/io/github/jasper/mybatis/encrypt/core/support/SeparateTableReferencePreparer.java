package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 独立表写前引用准备器。
 *
 * <p>负责在主表写 SQL 执行前，为独立表字段计算或复用 hash 引用值，
 * 并把最终引用值注册回当前 {@link BoundSql} 参数上下文。</p>
 */
final class SeparateTableReferencePreparer {

    private final DataSource dataSource;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SnowflakeIdGenerator idGenerator;
    private final SeparateTableRowPersister rowPersister;
    private final SeparateTableRuleSupport ruleSupport;

    SeparateTableReferencePreparer(DataSource dataSource,
                                   EncryptMetadataRegistry metadataRegistry,
                                   AlgorithmRegistry algorithmRegistry,
                                   SeparateTableRowPersister rowPersister,
                                   SeparateTableRuleSupport ruleSupport) {
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.idGenerator = new SnowflakeIdGenerator();
        this.rowPersister = rowPersister;
        this.ruleSupport = ruleSupport;
    }

    void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql, Executor executor) {
        if (metadataRegistry == null || mappedStatement == null || boundSql == null) {
            return;
        }
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject());
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        if (commandType != SqlCommandType.INSERT && commandType != SqlCommandType.UPDATE) {
            return;
        }
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject == null) {
            return;
        }
        for (Object candidate : unwrapCandidates(parameterObject)) {
            prepareCandidateReferences(mappedStatement, boundSql, commandType, candidate, executor);
        }
    }

    private void prepareCandidateReferences(MappedStatement mappedStatement,
                                            BoundSql boundSql,
                                            SqlCommandType commandType,
                                            Object candidate,
                                            Executor executor) {
        if (candidate == null || candidate instanceof Map<?, ?>) {
            return;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return;
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!rule.isStoredInSeparateTable() || !metaObject.hasGetter(rule.property())) {
                continue;
            }
            Object plainValue = metaObject.getValue(rule.property());
            if (plainValue == null) {
                continue;
            }
            String referenceId = determineReferenceId(
                    mappedStatement, boundSql, commandType, tableRule, rule, metaObject, plainValue, executor);
            registerPreparedReference(boundSql, candidate, rule.property(), referenceId);
        }
    }

    private String determineReferenceId(MappedStatement mappedStatement,
                                        BoundSql boundSql,
                                        SqlCommandType commandType,
                                        EncryptTableRule tableRule,
                                        EncryptColumnRule rule,
                                        MetaObject metaObject,
                                        Object plainValue,
                                        Executor executor) {
        String referenceId = currentReferenceId(boundSql, rule.property());
        if (referenceId != null) {
            return referenceId;
        }
        String assignedHash = ruleSupport.assignHash(rule, plainValue);
        if (commandType == SqlCommandType.UPDATE) {
            String currentStoredReferenceId = loadExistingReferenceId(tableRule, rule, metaObject);
            if (currentStoredReferenceId != null && matchesAssignedHash(currentStoredReferenceId, assignedHash)) {
                return Optional.ofNullable(findReferenceIdByAssignedHash(rule, assignedHash))
                        .orElseGet(() -> insertExternalRow(mappedStatement, rule, plainValue, assignedHash, executor));
            }
        }
        return Optional.ofNullable(findReferenceIdByAssignedHash(rule, assignedHash))
                .orElseGet(() -> insertExternalRow(mappedStatement, rule, plainValue, assignedHash, executor));
    }

    private String currentReferenceId(BoundSql boundSql, String property) {
        if (!boundSql.hasAdditionalParameter(property)) {
            return null;
        }
        return ruleSupport.normalizeReferenceId(boundSql.getAdditionalParameter(property));
    }

    private String loadExistingReferenceId(EncryptTableRule tableRule, EncryptColumnRule rule, MetaObject metaObject) {
        if (!metaObject.hasGetter("id")) {
            return null;
        }
        Object entityId = metaObject.getValue("id");
        if (entityId == null) {
            return null;
        }
        String sql = "select " + ruleSupport.quote(rule.column())
                + " from " + ruleSupport.quote(tableRule.getTableName())
                + " where " + ruleSupport.quote("id") + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, entityId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return ruleSupport.normalizeReferenceId(resultSet.getObject(1));
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to load existing separate-table reference id.", ex);
        }
    }

    private boolean matchesAssignedHash(String currentStoredReferenceId, String assignedHash) {
        return java.util.Objects.equals(currentStoredReferenceId, assignedHash);
    }

    private String findReferenceIdByAssignedHash(EncryptColumnRule rule, String assignedHash) {
        ruleSupport.requireAssistedReferenceRule(rule, "find separate-table reference");
        String sql = "select " + ruleSupport.quote(rule.assistedQueryColumn())
                + " from " + ruleSupport.quote(rule.storageTable())
                + " where " + ruleSupport.quote(rule.assistedQueryColumn()) + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, assignedHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return ruleSupport.normalizeReferenceId(resultSet.getObject(1));
                }
                return null;
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to find separate-table reference by assigned hash.", ex);
        }
    }

    private String insertExternalRow(MappedStatement mappedStatement,
                                     EncryptColumnRule rule,
                                     Object plainValue,
                                     String assignedHash,
                                     Executor executor) {
        ExternalRowValues values = buildExternalRowValues(rule, plainValue, assignedHash);
        long generatedId = idGenerator.nextId();
        LinkedHashMap<String, Object> columnValues = new LinkedHashMap<String, Object>();
        columnValues.put(rule.storageIdColumn(), generatedId);
        for (int index = 0; index < values.columns().size(); index++) {
            columnValues.put(values.columns().get(index), values.values().get(index));
        }
        rowPersister.insert(new SeparateTableInsertRequest(rule.storageTable(), columnValues), mappedStatement, executor);
        return assignedHash;
    }

    private ExternalRowValues buildExternalRowValues(EncryptColumnRule rule, Object plainValue, String assignedHash) {
        ruleSupport.requireAssistedReferenceRule(rule, "build separate-table row");
        LinkedHashMap<String, Object> columnValues = new LinkedHashMap<String, Object>();
        String plainText = String.valueOf(plainValue);
        columnValues.put(rule.storageColumn(), algorithmRegistry.cipher(rule.cipherAlgorithm()).encrypt(plainText));
        columnValues.put(rule.assistedQueryColumn(), assignedHash);
        if (rule.hasLikeQueryColumn()) {
            columnValues.put(rule.likeQueryColumn(),
                    algorithmRegistry.like(rule.likeQueryAlgorithm()).transform(plainText));
        }
        if (rule.hasDistinctMaskedColumn()) {
            columnValues.put(rule.maskedColumn(),
                    algorithmRegistry.like(rule.effectiveMaskedAlgorithm()).transform(plainText));
        }
        return new ExternalRowValues(new ArrayList<String>(columnValues.keySet()), new ArrayList<Object>(columnValues.values()));
    }

    private List<Object> unwrapCandidates(Object parameterObject) {
        if (parameterObject == null) {
            return Collections.emptyList();
        }
        List<Object> results = new ArrayList<Object>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        collectCandidates(parameterObject, results, visited);
        return results;
    }

    private void collectCandidates(Object parameterObject, List<Object> results, Set<Object> visited) {
        if (parameterObject == null) {
            return;
        }
        if (parameterObject instanceof Map<?, ?>) {
            for (Object value : ((Map<?, ?>) parameterObject).values()) {
                collectCandidates(value, results, visited);
            }
            return;
        }
        if (parameterObject instanceof Collection<?>) {
            for (Object value : (Collection<?>) parameterObject) {
                collectCandidates(value, results, visited);
            }
            return;
        }
        if (parameterObject.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(parameterObject);
            for (int index = 0; index < length; index++) {
                collectCandidates(java.lang.reflect.Array.get(parameterObject, index), results, visited);
            }
            return;
        }
        if (visited.add(parameterObject)) {
            results.add(parameterObject);
        }
    }

    private void registerPreparedReference(BoundSql boundSql, Object candidate, String property, String referenceId) {
        Map<String, Object> preparedReferences = preparedReferences(boundSql);
        List<String> parameterPaths = resolveParameterPaths(boundSql, candidate, property);
        if (parameterPaths.isEmpty()) {
            preparedReferences.put(property, referenceId);
            return;
        }
        for (String path : parameterPaths) {
            preparedReferences.put(path, referenceId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preparedReferences(BoundSql boundSql) {
        if (boundSql.hasAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER)) {
            Object existing = boundSql.getAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER);
            if (existing instanceof Map<?, ?>) {
                return (Map<String, Object>) existing;
            }
        }
        Map<String, Object> preparedReferences = new LinkedHashMap<String, Object>();
        boundSql.setAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER, preparedReferences);
        return preparedReferences;
    }

    private List<String> resolveParameterPaths(BoundSql boundSql, Object candidate, String property) {
        Set<String> paths = new LinkedHashSet<>();
        Object parameterObject = boundSql.getParameterObject();
        MetaObject parameterMetaObject = parameterObject == null ? null : SystemMetaObject.forObject(parameterObject);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
            String parameterProperty = parameterMapping.getProperty();
            if (parameterProperty == null || !property.equals(lastPropertyName(parameterProperty))) {
                continue;
            }
            if (belongsToCandidate(boundSql, parameterMetaObject, parameterObject, candidate, parameterProperty, property)) {
                paths.add(parameterProperty);
            }
        }
        return new ArrayList<String>(paths);
    }

    private boolean belongsToCandidate(BoundSql boundSql,
                                       MetaObject parameterMetaObject,
                                       Object parameterObject,
                                       Object candidate,
                                       String parameterProperty,
                                       String property) {
        String root = new PropertyTokenizer(parameterProperty).getName();
        if (boundSql.hasAdditionalParameter(root)) {
            return boundSql.getAdditionalParameter(root) == candidate;
        }
        if (property.equals(parameterProperty)) {
            return parameterObject == candidate;
        }
        return parameterMetaObject != null && parameterMetaObject.hasGetter(root)
                && parameterMetaObject.getValue(root) == candidate;
    }

    private String lastPropertyName(String property) {
        int dotIndex = property.lastIndexOf('.');
        String segment = dotIndex >= 0 ? property.substring(dotIndex + 1) : property;
        int bracketIndex = segment.indexOf('[');
        return bracketIndex >= 0 ? segment.substring(0, bracketIndex) : segment;
    }

    private static final class ExternalRowValues {

        private final List<String> columns;
        private final List<Object> values;

        private ExternalRowValues(List<String> columns, List<Object> values) {
            this.columns = columns;
            this.values = values;
        }

        private List<String> columns() {
            return columns;
        }

        private List<Object> values() {
            return values;
        }
    }
}
