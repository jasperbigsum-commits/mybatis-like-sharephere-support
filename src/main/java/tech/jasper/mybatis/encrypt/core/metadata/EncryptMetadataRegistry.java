package tech.jasper.mybatis.encrypt.core.metadata;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.FieldRuleProperties;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties.TableRuleProperties;
import tech.jasper.mybatis.encrypt.util.NameUtils;

/**
 * 加密元数据注册中心。
 *
 * <p>负责合并配置文件规则与注解规则，并按表名和实体类型建立缓存。
 * SQL 改写阶段主要按表名取规则，结果解密阶段主要按实体类型取规则。</p>
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
     * 按表名查询规则。
     *
     * @param table 数据库表名
     * @return 表规则
     */
    public Optional<EncryptTableRule> findByTable(String table) {
        return Optional.ofNullable(tableRules.get(NameUtils.normalizeIdentifier(table)));
    }

    /**
     * 按实体类型查询规则，首次访问时会尝试从注解加载并缓存。
     *
     * @param entityType 实体类型
     * @return 表规则
     */
    public Optional<EncryptTableRule> findByEntity(Class<?> entityType) {
        return Optional.ofNullable(entityRules.computeIfAbsent(entityType, this::loadEntityRule));
    }

    public void registerEntityType(Class<?> entityType) {
        findByEntity(entityType);
    }

    /**
     * 在 SQL 执行前预热可能使用到的实体规则，减少首次查询时的延迟抖动。
     *
     * @param mappedStatement 当前执行的 MappedStatement
     * @param parameterObject 当前入参对象
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
            // 配置规则优先级高于注解规则，注解只补齐缺失字段，避免启动后行为漂移。
            existing.mergeMissing(annotationRule);
            return existing;
        }
        tableRules.put(annotationRule.getTableName(), annotationRule);
        return annotationRule;
    }

    private void registerConfiguredRules(DatabaseEncryptionProperties properties) {
        properties.getTables().forEach((name, tableProperties) -> {
            // 外层 map key 既可以作为逻辑名，也可以直接作为默认表名。
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
                properties.getSourceIdProperty() != null ? properties.getSourceIdProperty() : "id",
                properties.getSourceIdColumn() != null ? properties.getSourceIdColumn() : NameUtils.camelToSnake(properties.getSourceIdProperty()),
                properties.getStorageIdColumn() != null ? properties.getStorageIdColumn()
                        : (properties.getSourceIdColumn() != null ? properties.getSourceIdColumn()
                        : NameUtils.camelToSnake(properties.getSourceIdProperty()))
        );
        validateRule(rule);
        return rule;
    }

    private void validateRule(EncryptColumnRule rule) {
        if (!rule.isStoredInSeparateTable()) {
            return;
        }
        if (rule.storageTable() == null || rule.storageTable().isBlank()) {
            throw new IllegalArgumentException("Separate-table encrypted field must define storageTable: " + rule.property());
        }
        if (!rule.hasAssistedQueryColumn()) {
            throw new IllegalArgumentException("Separate-table encrypted field must define assistedQueryColumn: " + rule.property());
        }
    }

    private boolean isCandidateType(Class<?> type) {
        // 只对“像实体”的类型做规则解析，避免把 String、基础类型或 JDK 类型误当成业务对象。
        return type != null
                && !type.isPrimitive()
                && !type.getName().startsWith("java.")
                && !type.isEnum();
    }
}
