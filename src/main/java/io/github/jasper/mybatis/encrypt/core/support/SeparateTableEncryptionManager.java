package io.github.jasper.mybatis.encrypt.core.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;

/**
 * 独立加密表管理器。
 *
 * <p>负责两类工作：一是在主表 SQL 执行前准备独立表引用 id，二是在查询结果返回后按引用 id
 * 回填并解密独立加密表中的字段。</p>
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

    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql) {
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
            prepareCandidateReferences(mappedStatement, boundSql, commandType, candidate);
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

    /**
     * 为当前待写实体准备独立表引用值。
     *
     * <p>这个方法只处理独立表字段。它会根据当前 SQL 类型和运行时参数状态，
     * 决定是复用已有引用、回查主表现有引用，还是新建独立表记录，最后再把引用 id 写回 BoundSql。</p>
     */
    private void prepareCandidateReferences(MappedStatement mappedStatement,
                                            BoundSql boundSql,
                                            SqlCommandType commandType,
                                            Object candidate) {
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
            String referenceId = determineReferenceId(boundSql, commandType, tableRule, rule, metaObject, plainValue);
            boundSql.setAdditionalParameter(rule.property(), referenceId);
        }
    }

    /**
     * 决定本次主表写入应使用的独立表引用 id。
     *
     * <p>它会先尝试复用当前 BoundSql 已准备好的引用值；如果是 UPDATE 且当前没有引用，
     * 就回主表查询已有引用；仍然没有时新建独立表记录，否则直接更新已有独立表记录。</p>
     */
    private String determineReferenceId(BoundSql boundSql,
                                        SqlCommandType commandType,
                                        EncryptTableRule tableRule,
                                        EncryptColumnRule rule,
                                        MetaObject metaObject,
                                        Object plainValue) {
        String referenceId = currentReferenceId(boundSql, rule.property());
        if (referenceId == null && commandType == SqlCommandType.UPDATE) {
            referenceId = loadExistingReferenceId(tableRule, rule, metaObject);
        }
        if (referenceId == null) {
            return insertExternalRow(rule, plainValue);
        }
        updateExternalRow(rule, referenceId, plainValue);
        return referenceId;
    }

    /**
     * 读取当前 BoundSql 已准备好的独立表引用值。
     *
     * <p>只有写前阶段已经把引用 id 放进 additionalParameter 时才会命中；
     * 没有命中时返回 null，让调用方继续走 UPDATE 回查或新建独立表记录。</p>
     */
    private String currentReferenceId(BoundSql boundSql, String property) {
        if (!boundSql.hasAdditionalParameter(property)) {
            return null;
        }
        return toReferenceId(boundSql.getAdditionalParameter(property));
    }

    /**
     * 读取主表当前记录中已经持久化的独立表引用 id。
     *
     * <p>这个方法只在 UPDATE 且当前 BoundSql 还没有准备好引用值时使用，
     * 目的是避免把业务明文误判成引用 id，并尽量复用已有独立表记录。</p>
     */
    private String loadExistingReferenceId(EncryptTableRule tableRule, EncryptColumnRule rule, MetaObject metaObject) {
        if (!metaObject.hasGetter("id")) {
            return null;
        }
        Object entityId = metaObject.getValue("id");
        if (entityId == null) {
            return null;
        }
        String sql = "select " + quote(rule.column())
                + " from " + quote(tableRule.getTableName())
                + " where " + quote("id") + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, entityId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return toReferenceId(resultSet.getObject(1));
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to load existing separate-table reference id.", ex);
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
        Map<Object, MetaObject> metaById = new LinkedHashMap<>();
        for (Object candidate : candidates) {
            MetaObject metaObject = SystemMetaObject.forObject(candidate);
            if (!metaObject.hasGetter(rule.property()) || !metaObject.hasSetter(rule.property())) {
                continue;
            }
            Object referenceId = metaObject.getValue(rule.property());
            if (referenceId != null) {
                metaById.put(normalizeReferenceId(referenceId), metaObject);
            }
        }
        if (metaById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(rule, new ArrayList<>(metaById.keySet()));
        cipherById.forEach((referenceId, cipherText) -> {
            MetaObject metaObject = metaById.get(referenceId);
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
                    result.put(normalizeReferenceId(resultSet.getObject(1)), resultSet.getString(2));
                }
            }
            return result;
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to load separate-table encrypted values.", ex);
        }
    }

    /**
     * 生成一条新的独立表记录并返回引用 id。
     *
     * <p>返回值会被写回主表逻辑列，因此这里拿到的主键必须能稳定转换成字符串引用值。</p>
     */
    private String insertExternalRow(EncryptColumnRule rule, Object plainValue) {
        ExternalRowValues values = buildExternalRowValues(rule, plainValue);
        String placeholders = values.values().stream().map(current -> "?").collect(Collectors.joining(", "));
        String sql = "insert into " + quote(rule.storageTable()) + " ("
                + values.columns().stream().map(this::quote).collect(Collectors.joining(", "))
                + ") values (" + placeholders + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values.values());
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return toReferenceId(generatedKeys.getObject(1));
                }
            }
            throw new EncryptionConfigurationException("Failed to obtain separate-table generated id.");
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to insert separate-table encrypted value.", ex);
        }
    }

    /**
     * 更新已存在的独立表记录。
     *
     * <p>更新路径与插入路径复用同一套列和值构造逻辑，避免 cipher、assisted 和 like 字段在两条路径中出现顺序或算法不一致。</p>
     */
    private void updateExternalRow(EncryptColumnRule rule, String referenceId, Object plainValue) {
        ExternalRowValues values = buildExternalRowValues(rule, plainValue);
        List<String> assignments = values.columns().stream()
                .map(column -> quote(column) + " = ?")
                .collect(Collectors.toCollection(ArrayList::new));
        List<Object> parameters = new ArrayList<>(values.values());
        parameters.add(referenceId);
        String sql = "update " + quote(rule.storageTable()) + " set " + String.join(", ", assignments)
                + " where " + quote(rule.storageIdColumn()) + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new EncryptionConfigurationException("Failed to update referenced separate-table encrypted value.");
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException("Failed to update separate-table encrypted value.", ex);
        }
    }

    /**
     * 构造独立表写入和更新共用的列和值。
     *
     * <p>插入和更新必须使用完全一致的列集合与生成顺序，才能保证独立表中的密文列、辅助查询列和 like 查询列始终保持一致。</p>
     */
    private ExternalRowValues buildExternalRowValues(EncryptColumnRule rule, Object plainValue) {
        // 独立表写入与更新必须生成完全一致的密文字段集合，避免两条路径分别维护时出现列顺序或算法不一致。
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String plainText = String.valueOf(plainValue);
        columns.add(rule.storageColumn());
        values.add(algorithmRegistry.cipher(rule.cipherAlgorithm()).encrypt(plainText));
        columns.add(rule.assistedQueryColumn());
        values.add(algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(plainText));
        if (rule.hasLikeQueryColumn()) {
            columns.add(rule.likeQueryColumn());
            values.add(algorithmRegistry.like(rule.likeQueryAlgorithm()).transform(plainText));
        }
        return new ExternalRowValues(columns, values);
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

    private String toReferenceId(Object referenceId) {
        if (referenceId == null) {
            return null;
        }
        String value = String.valueOf(referenceId);
        return value.isBlank() ? null : value;
    }

    private Object normalizeReferenceId(Object referenceId) {
        if (referenceId instanceof Number number) {
            return number.longValue();
        }
        if (referenceId instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignore) {
                return string;
            }
        }
        return referenceId;
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }

    private record ExternalRowValues(List<String> columns, List<Object> values) {
    }
}
