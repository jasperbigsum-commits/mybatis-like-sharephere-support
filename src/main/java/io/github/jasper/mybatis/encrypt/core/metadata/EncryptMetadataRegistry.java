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
 * 加密元数据中心注册表。
 *
 * <p>负责合并配置驱动规则与注解驱动规则，并按物理表名和实体类型两条维度缓存结果。</p>
 */
public class EncryptMetadataRegistry {

    private final AnnotationEncryptMetadataLoader annotationLoader;
    private final Map<String, EncryptTableRule> tableRules = new ConcurrentHashMap<>();
    private final Map<Class<?>, EncryptTableRule> entityRules = new ConcurrentHashMap<>();

    /**
     * 创建加密元数据注册中心。
     *
     * @param properties 外部配置属性
     * @param annotationLoader 注解元数据加载器
     */
    public EncryptMetadataRegistry(DatabaseEncryptionProperties properties,
                                   AnnotationEncryptMetadataLoader annotationLoader) {
        this.annotationLoader = annotationLoader;
        registerConfiguredRules(properties);
    }

    /**
     * 按物理表名查找表规则。
     *
     * @param table 数据库表名
     * @return 命中的表规则，存在时返回
     */
    public Optional<EncryptTableRule> findByTable(String table) {
        if (table == null || table.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tableRules.get(NameUtils.normalizeIdentifier(table)));
    }

    /**
     * 按实体类型查找或懒加载表规则。
     *
     * @param entityType 实体类型
     * @return 命中的表规则，存在时返回
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
        // 实体规则加载过程中会写入 tableRules，不能放进 ConcurrentHashMap.computeIfAbsent，
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
     * 强制注册指定实体类型的元数据。
     *
     * @param entityType 需要预加载的实体类型
     */
    public void registerEntityType(Class<?> entityType) {
        findByEntity(entityType);
    }

    /**
     * 预加载当前 mapped statement 执行可能用到的元数据。
     *
     * @param mappedStatement 当前 mapped statement
     * @param parameterObject 当前 MyBatis 参数对象
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
                firstNonBlank(properties.getStorageIdColumn(), "id")
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
