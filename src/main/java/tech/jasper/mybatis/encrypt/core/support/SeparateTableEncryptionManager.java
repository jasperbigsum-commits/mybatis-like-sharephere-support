package tech.jasper.mybatis.encrypt.core.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

/**
 * 独立加密表管理器。
 *
 * <p>负责两类工作：一是在写操作后同步独立加密表，二是在查询结果返回后按主键批量回填并解密
 * 独立加密表中的字段。</p>
 *
 * <p><strong>事务限制：</strong>当前实现通过 DataSource 获取独立连接执行 SQL，
 * 不参与 MyBatis 当前事务。如果业务写操作回滚，独立加密表的变更不会自动回滚。
 * 在强一致性场景下，建议配合事务型 DataSource（如 Spring 的 TransactionAwareDataSourceProxy）使用。</p>
 */
public class SeparateTableEncryptionManager {

    private final DataSource dataSource;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;

    public SeparateTableEncryptionManager(DataSource dataSource,
                                          EncryptMetadataRegistry metadataRegistry,
                                          AlgorithmRegistry algorithmRegistry,
                                          DatabaseEncryptionProperties properties) {
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
    }

    public void synchronizeAfterWrite(MappedStatement mappedStatement, Object parameterObject) {
        metadataRegistry.warmUp(mappedStatement, parameterObject);
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        if (commandType != SqlCommandType.INSERT && commandType != SqlCommandType.UPDATE && commandType != SqlCommandType.DELETE) {
            return;
        }
        for (Object candidate : unwrapCandidates(parameterObject)) {
            synchronizeCandidate(commandType, candidate);
        }
    }

    public void hydrateResults(Object resultObject) {
        if (resultObject == null) {
            return;
        }
        if (resultObject instanceof Collection<?> collection) {
            hydrateCollection(collection);
            return;
        }
        hydrateCollection(List.of(resultObject));
    }

    private void synchronizeCandidate(SqlCommandType commandType, Object candidate) {
        if (candidate == null || candidate instanceof Map<?, ?>) {
            return;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return;
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!rule.isStoredInSeparateTable()) {
                continue;
            }
            Object sourceId = metaObject.hasGetter(rule.sourceIdProperty()) ? metaObject.getValue(rule.sourceIdProperty()) : null;
            if (sourceId == null) {
                if (commandType == SqlCommandType.DELETE) {
                    continue;
                }
                throw new EncryptionConfigurationException(
                        "Separate-table encrypted field requires source id property value: " + rule.sourceIdProperty());
            }
            if (commandType == SqlCommandType.DELETE) {
                deleteExternalRow(rule, sourceId);
                continue;
            }
            Object plainValue = metaObject.hasGetter(rule.property()) ? metaObject.getValue(rule.property()) : null;
            upsertExternalRow(rule, sourceId, plainValue);
        }
    }

    private void hydrateCollection(Collection<?> results) {
        Map<? extends Class<?>, ? extends List<?>> groups = results.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !(candidate instanceof Map<?, ?>))
                .collect(Collectors.groupingBy(Object::getClass, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<? extends Class<?>, ? extends List<?>> entry : groups.entrySet()) {
            EncryptTableRule tableRule = metadataRegistry.findByEntity(entry.getKey()).orElse(null);
            if (tableRule == null) {
                continue;
            }
            List<?> candidates = entry.getValue();
            for (EncryptColumnRule rule : tableRule.getColumnRules()) {
                if (!rule.isStoredInSeparateTable()) {
                    continue;
                }
                hydrateRule(candidates, rule);
            }
        }
    }

    private void hydrateRule(List<?> candidates, EncryptColumnRule rule) {
        Map<Object, Object> metaById = new LinkedHashMap<>();
        for (Object candidate : candidates) {
            MetaObject metaObject = SystemMetaObject.forObject(candidate);
            if (!metaObject.hasGetter(rule.sourceIdProperty()) || !metaObject.hasSetter(rule.property())) {
                continue;
            }
            Object sourceId = metaObject.getValue(rule.sourceIdProperty());
            if (sourceId != null) {
                metaById.put(sourceId, metaObject);
            }
        }
        if (metaById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(rule, new ArrayList<>(metaById.keySet()));
        cipherById.forEach((sourceId, cipherText) -> {
            MetaObject metaObject = (MetaObject) metaById.get(sourceId);
            if (metaObject != null && cipherText != null) {
                metaObject.setValue(rule.property(), algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(cipherText));
            }
        });
    }

    private Map<Object, String> loadCipherValues(EncryptColumnRule rule, List<Object> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = "select " + quote(rule.storageIdColumn()) + ", " + quote(rule.storageColumn())
                + " from " + quote(rule.storageTable())
                + " where " + quote(rule.storageIdColumn()) + " in (" + placeholders + ")";
        Map<Object, String> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, ids);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(resultSet.getObject(1), resultSet.getString(2));
                }
            }
            return result;
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to load separate-table encrypted values.", ex);
        }
    }

    private void upsertExternalRow(EncryptColumnRule rule, Object sourceId, Object plainValue) {
        deleteExternalRow(rule, sourceId);
        if (plainValue == null) {
            return;
        }
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        columns.add(rule.storageIdColumn());
        values.add(sourceId);
        columns.add(rule.storageColumn());
        values.add(algorithmRegistry.cipher(rule.cipherAlgorithm()).encrypt(String.valueOf(plainValue)));
        columns.add(rule.assistedQueryColumn());
        values.add(algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(String.valueOf(plainValue)));
        if (rule.hasLikeQueryColumn()) {
            columns.add(rule.likeQueryColumn());
            values.add(algorithmRegistry.like(rule.likeQueryAlgorithm()).transform(String.valueOf(plainValue)));
        }
        String placeholders = values.stream().map(current -> "?").collect(Collectors.joining(", "));
        String sql = "insert into " + quote(rule.storageTable()) + " ("
                + columns.stream().map(this::quote).collect(Collectors.joining(", "))
                + ") values (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to synchronize separate-table encrypted value.", ex);
        }
    }

    private void deleteExternalRow(EncryptColumnRule rule, Object sourceId) {
        String sql = "delete from " + quote(rule.storageTable()) + " where " + quote(rule.storageIdColumn()) + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sourceId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to delete separate-table encrypted value.", ex);
        }
    }

    private void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setObject(index + 1, values.get(index));
        }
    }

    private List<Object> unwrapCandidates(Object parameterObject) {
        if (parameterObject == null) {
            return List.of();
        }
        if (parameterObject instanceof Map<?, ?> map) {
            return map.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
        }
        return List.of(parameterObject);
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
