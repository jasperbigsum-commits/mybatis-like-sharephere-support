package io.github.jasper.mybatis.encrypt.core.metadata;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties;
import io.github.jasper.mybatis.encrypt.util.NameUtils;

/**
 * Central registry for encryption metadata.
 *
 * <p>The registry merges configuration-driven rules with annotation-driven rules and caches
 * them by both physical table name and entity type.</p>
 */
public class EncryptMetadataRegistry {

    private final AnnotationEncryptMetadataLoader annotationLoader;
    private final Map<String, EncryptTableRule> tableRules = new ConcurrentHashMap<>();
    private final Map<Class<?>, EncryptTableRule> entityRules = new ConcurrentHashMap<>();

    public EncryptMetadataRegistry(DatabaseEncryptionProperties properties,
                                   AnnotationEncryptMetadataLoader annotationLoader) {
        this.annotationLoader = annotationLoader;
        registerConfiguredRules(properties);
    }

    /**
     * Finds a table rule by physical table name.
     *
     * @param table database table name
     * @return matched table rule if present
     */
    public Optional<EncryptTableRule> findByTable(String table) {
        if (table == null || table.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tableRules.get(NameUtils.normalizeIdentifier(table)));
    }

    /**
     * Finds or lazily loads a table rule for the given entity type.
     *
     * @param entityType entity class
     * @return matched table rule if present
     */
    public Optional<EncryptTableRule> findByEntity(Class<?> entityType) {
        if (!isCandidateType(entityType)) {
            return Optional.empty();
        }
        // 先尝试无锁读取，避免每次都进入 synchronized 块
        EncryptTableRule cached = entityRules.get(entityType);
        if (cached != null) {
            return Optional.of(cached);
        }
        // loadEntityRule 内部会操作 tableRules，不能在 ConcurrentHashMap.computeIfAbsent 中执行，
        // 否则可能触发递归更新或死锁。改用 synchronized 保证安全。
        synchronized (entityRules) {
            cached = entityRules.get(entityType);
            if (cached != null) {
                return Optional.of(cached);
            }
            EncryptTableRule rule = loadEntityRule(entityType);
            if (rule != null) {
                entityRules.put(entityType, rule);
            }
            return Optional.ofNullable(rule);
        }
    }

    /**
     * Forces metadata registration for a specific entity class.
     *
     * @param entityType entity class to preload
     */
    public void registerEntityType(Class<?> entityType) {
        findByEntity(entityType);
    }

    /**
     * Preloads metadata that may be needed by the current mapped statement execution.
     *
     * @param mappedStatement current mapped statement
     * @param parameterObject current MyBatis parameter object
     */
    public void warmUp(MappedStatement mappedStatement, Object parameterObject) {
        mappedStatement.getResultMaps().stream()
                .map(ResultMap::getType)
                .filter(this::isCandidateType)
                .forEach(this::findByEntity);
        if (mappedStatement.getParameterMap() != null && isCandidateType(mappedStatement.getParameterMap().getType())) {
            findByEntity(mappedStatement.getParameterMap().getType());
        }
        if (parameterObject == null) {
            return;
        }
        if (isCandidateType(parameterObject.getClass())) {
            findByEntity(parameterObject.getClass());
            return;
        }
        if (parameterObject instanceof Map<?, ?> map) {
            map.values().stream()
                    .filter(value -> value != null && isCandidateType(value.getClass()))
                    .forEach(value -> findByEntity(value.getClass()));
        }
    }

    private EncryptTableRule loadEntityRule(Class<?> entityType) {
        EncryptTableRule annotationRule = annotationLoader.load(entityType);
        if (annotationRule == null) {
            return null;
        }
        annotationRule.getColumnRules().forEach(this::validateRule);
        EncryptTableRule existing = tableRules.get(annotationRule.getTableName());
        if (existing != null) {
            existing.mergeMissing(annotationRule);
            return existing;
        }
        tableRules.put(annotationRule.getTableName(), annotationRule);
        return annotationRule;
    }

    private void registerConfiguredRules(DatabaseEncryptionProperties properties) {
        properties.getTables().forEach((name, tableProperties) -> {
            String tableName = tableProperties.getTable() != null ? tableProperties.getTable() : name;
            EncryptTableRule tableRule = new EncryptTableRule(tableName);
            tableProperties.getFields().forEach((property, fieldProperties) ->
                    tableRule.addColumnRule(toColumnRule(property, fieldProperties)));
            tableRules.put(tableRule.getTableName(), tableRule);
        });
    }

    private EncryptColumnRule toColumnRule(String property, FieldRuleProperties properties) {
        String column = properties.getColumn() != null ? properties.getColumn() : NameUtils.camelToSnake(property);
        String sourceIdColumn = firstNonBlank(properties.getSourceIdColumn(), "id");
        String sourceIdProperty = firstNonBlank(
                properties.getSourceIdProperty(),
                inferSourceIdProperty(sourceIdColumn)
        );
        if (properties.getSourceIdColumn() == null || properties.getSourceIdColumn().isBlank()) {
            sourceIdColumn = NameUtils.camelToSnake(sourceIdProperty);
        }
        EncryptColumnRule rule = new EncryptColumnRule(
                property,
                column,
                properties.getCipherAlgorithm(),
                properties.getAssistedQueryColumn(),
                properties.getAssistedQueryAlgorithm(),
                properties.getLikeQueryColumn(),
                properties.getLikeQueryAlgorithm(),
                properties.getStorageMode(),
                properties.getStorageTable(),
                properties.getStorageColumn() != null ? properties.getStorageColumn() : column,
                sourceIdProperty,
                sourceIdColumn,
                properties.getStorageIdColumn() != null ? properties.getStorageIdColumn() : sourceIdColumn
        );
        validateRule(rule);
        return rule;
    }

    private void validateRule(EncryptColumnRule rule) {
        if (!rule.isStoredInSeparateTable()) {
            return;
        }
        if (rule.storageTable() == null || rule.storageTable().isBlank()) {
            throw new IllegalArgumentException(
                    "Separate-table encrypted field must define storageTable: " + rule.property());
        }
        if (!rule.hasAssistedQueryColumn()) {
            throw new IllegalArgumentException(
                    "Separate-table encrypted field must define assistedQueryColumn: " + rule.property());
        }
    }

    private String inferSourceIdProperty(String sourceIdColumn) {
        if (sourceIdColumn == null || sourceIdColumn.isBlank()) {
            return "id";
        }
        String normalized = NameUtils.normalizeIdentifier(sourceIdColumn);
        return "id".equals(normalized) ? "id" : toCamelCase(normalized);
    }

    private String toCamelCase(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        boolean upperNext = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '_') {
                upperNext = builder.length() > 0;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(current) : current);
            upperNext = false;
        }
        return builder.length() == 0 ? "id" : builder.toString();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isCandidateType(Class<?> type) {
        return type != null
                && !type.isPrimitive()
                && !type.getName().startsWith("java.")
                && !type.isEnum();
    }
}
