package io.github.jasper.mybatis.encrypt.core.metadata;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加密元数据中心注册表。
 *
 * <p>负责合并配置驱动规则与注解驱动规则，并按物理表名和实体类型两条维度缓存结果。
 * 对于多表 DTO，会优先使用字段级 {@code table} 把规则拆分注册到各自来源表。</p>
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
        if (StringUtils.isBlank(table)) {
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
        if (parameterObject instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) parameterObject;
            map.values().stream()
                    .filter(value -> value != null && isCandidateType(value.getClass()))
                    .forEach(value -> findByEntity(value.getClass()));
        }
    }

    private void registerConfiguredRules(DatabaseEncryptionProperties properties) {
        properties.getTables().forEach(tableProperties -> {
            String tableName = tableProperties.getTable();
            if (StringUtils.isBlank(tableName)) {
                throw new IllegalArgumentException("Configured table rule must define table name.");
            }
            EncryptTableRule tableRule = new EncryptTableRule(tableName);
            tableProperties.getFields().forEach(fieldProperties ->
                    tableRule.addColumnRule(toColumnRule(fieldProperties)));
            tableRules.put(tableRule.getTableName(), tableRule);
        });
    }

    private EncryptColumnRule toColumnRule(DatabaseEncryptionProperties.FieldRuleProperties properties) {
        String property = resolveConfiguredProperty(properties);
        String column = properties.getColumn() != null ? properties.getColumn() : NameUtils.camelToSnake(property);
        if (StringUtils.isBlank(column)) {
            throw new IllegalArgumentException("Configured field rule must define column or property name.");
        }
        EncryptColumnRule rule = new EncryptColumnRule(
                property,
                null,
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

    private String resolveConfiguredProperty(DatabaseEncryptionProperties.FieldRuleProperties properties) {
        if (StringUtils.isNotBlank(properties.getProperty())) {
            return properties.getProperty();
        }
        if (StringUtils.isNotBlank(properties.getColumn())) {
            return NameUtils.columnToProperty(properties.getColumn());
        }
        return null;
    }

    private void registerAnnotationColumnRule(EncryptTableRule entityRule, EncryptColumnRule columnRule) {
        // 字段级 table 优先级高于类级 @EncryptTable，适合一个 DTO 混合多张表字段的场景。
        String effectiveTable = firstNonBlank(columnRule.table(), entityRule.getTableName());
        EncryptTableRule tableRule = tableRules.computeIfAbsent(
                NameUtils.normalizeIdentifier(effectiveTable),
                ignored -> new EncryptTableRule(effectiveTable)
        );
        tableRule.mergeMissing(columnRule);
    }

    private void validateRule(EncryptColumnRule rule) {
        if (!rule.isStoredInSeparateTable()) {
            return;
        }
        if (StringUtils.isBlank(rule.storageTable())) {
            throw new IllegalArgumentException(
                    "Separate-table encrypted field must define storageTable. property=" + rule.property()
                            + ", table=" + firstNonBlank(rule.table(), "<entity-default-table>")
                            + ", column=" + rule.column());
        }
        if (!rule.hasAssistedQueryColumn()) {
            throw new IllegalArgumentException(
                    "Separate-table encrypted field must define assistedQueryColumn. property=" + rule.property()
                            + ", table=" + firstNonBlank(rule.table(), "<entity-default-table>")
                            + ", column=" + rule.column()
                            + ", storageTable=" + rule.storageTable());
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
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

    private EncryptTableRule loadEntityRule(Class<?> entityType) {
        EncryptTableRule annotationRule = annotationLoader.load(entityType);
        if (annotationRule != null) {
            annotationRule.getColumnRules().forEach(this::validateRule);
            for (EncryptColumnRule columnRule : annotationRule.getColumnRules()) {
                registerAnnotationColumnRule(annotationRule, columnRule);
            }
            return annotationRule;
        }
        return loadConfiguredEntityRule(entityType);
    }

    private EncryptTableRule loadConfiguredEntityRule(Class<?> entityType) {
        String tableName = resolveEntityTableName(entityType);
        EncryptTableRule configuredTableRule = tableRules.get(NameUtils.normalizeIdentifier(tableName));
        if (configuredTableRule == null) {
            return null;
        }
        EncryptTableRule entityRule = new EncryptTableRule(tableName);
        for (EncryptColumnRule columnRule : configuredTableRule.getColumnRules()) {
            entityRule.addColumnRule(new EncryptColumnRule(
                    resolveEntityProperty(entityType, columnRule),
                    columnRule.table(),
                    columnRule.column(),
                    columnRule.cipherAlgorithm(),
                    columnRule.assistedQueryColumn(),
                    columnRule.assistedQueryAlgorithm(),
                    columnRule.likeQueryColumn(),
                    columnRule.likeQueryAlgorithm(),
                    columnRule.storageMode(),
                    columnRule.storageTable(),
                    columnRule.storageColumn(),
                    columnRule.storageIdColumn()
            ));
        }
        return entityRule;
    }

    private String resolveEntityTableName(Class<?> entityType) {
        String explicit = annotationValue(entityType,
                "com.baomidou.mybatisplus.annotation.TableName", "value",
                "jakarta.persistence.Table", "name",
                "javax.persistence.Table", "name");
        return StringUtils.isNotBlank(explicit) ? explicit : NameUtils.camelToSnake(entityType.getSimpleName());
    }

    private String resolveEntityProperty(Class<?> entityType, EncryptColumnRule columnRule) {
        java.lang.reflect.Field matchedField = findFieldByColumn(entityType, columnRule.column());
        return matchedField != null ? matchedField.getName() : columnRule.property();
    }

    private java.lang.reflect.Field findFieldByColumn(Class<?> entityType, String column) {
        String normalizedColumn = NameUtils.normalizeIdentifier(column);
        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (normalizedColumn.equals(NameUtils.normalizeIdentifier(resolveFieldColumn(field)))) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private String resolveFieldColumn(java.lang.reflect.Field field) {
        String explicit = annotationValue(field,
                "com.baomidou.mybatisplus.annotation.TableField", "value",
                "jakarta.persistence.Column", "name",
                "javax.persistence.Column", "name");
        return StringUtils.isNotBlank(explicit) ? explicit : NameUtils.camelToSnake(field.getName());
    }

    private String annotationValue(java.lang.reflect.AnnotatedElement element, String... annotationSpecs) {
        for (int index = 0; index + 1 < annotationSpecs.length; index += 2) {
            String value = annotationAttributeValue(element, annotationSpecs[index], annotationSpecs[index + 1]);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String annotationAttributeValue(java.lang.reflect.AnnotatedElement element,
                                            String annotationClassName,
                                            String attributeName) {
        for (java.lang.annotation.Annotation annotation : element.getAnnotations()) {
            if (!annotation.annotationType().getName().equals(annotationClassName)) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod(attributeName).invoke(annotation);
                return value == null ? null : String.valueOf(value);
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
        return null;
    }
}
