package io.github.jasper.mybatis.encrypt.core.metadata;

import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.annotation.EncryptResultHint;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
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
     * 返回当前已注册的物理表名快照。
     *
     * @return 已注册物理表名
     */
    public Set<String> getRegisteredTableNames() {
        return new LinkedHashSet<String>(tableRules.keySet());
    }

    /**
     * 预加载当前 mapped statement 执行可能用到的元数据。
     *
     * @param mappedStatement 当前 mapped statement
     * @param parameterObject 当前 MyBatis 参数对象
     */
    public void warmUp(MappedStatement mappedStatement, Object parameterObject) {
        warmUp(mappedStatement, parameterObject, null);
    }

    /**
     * 预加载当前 mapped statement 执行可能用到的元数据。
     *
     * <p>除了参数与结果类型本身，还会尝试根据当前 SQL 中出现的表名，
     * 反查同 mapper 相关的方法签名里已经声明过的实体类型，
     * 让 XML {@code resultType} 这类纯 DTO 查询也能建立来源表规则。</p>
     *
     * @param mappedStatement 当前 mapped statement
     * @param parameterObject 当前 MyBatis 参数对象
     * @param sql 当前待执行 SQL
     */
    public void warmUp(MappedStatement mappedStatement, Object parameterObject, String sql) {
        preloadResultHintMetadata(mappedStatement);
        preloadStatementTableMetadata(mappedStatement, sql);
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

    /**
     * 根据当前 statement SQL 中出现的物理表名预热表规则。
     *
     * <p>当 mapper 方法返回的是不带加密注解的 DTO，且又没有显式的
     * {@link EncryptResultHint} 时，仍然可以通过 SQL 里出现的表名，
     * 结合同 mapper 中已有的实体签名，补装载来源表规则。</p>
     *
     * @param mappedStatement 当前 mapped statement
     * @param sql 当前待执行 SQL
     */
    public void preloadStatementTableMetadata(MappedStatement mappedStatement, String sql) {
        Class<?> mapperType = resolveMapperType(mappedStatement);
        if (mapperType == null || StringUtils.isBlank(sql)) {
            return;
        }
        for (String tableName : collectTableNames(sql)) {
            if (findByTable(tableName).isPresent()) {
                continue;
            }
            preloadMapperRelatedEntitiesForTable(mapperType, tableName);
        }
    }

    /**
     * 按 mapper 方法上的 {@link EncryptResultHint} 预热来源实体或来源表规则。
     *
     * @param mappedStatement 当前 mapped statement
     */
    public void preloadResultHintMetadata(MappedStatement mappedStatement) {
        Class<?> mapperType = resolveMapperType(mappedStatement);
        if (mapperType == null) {
            return;
        }
        String methodName = resolveMappedMethodName(mappedStatement);
        if (StringUtils.isBlank(methodName)) {
            return;
        }
        try {
            for (java.lang.reflect.Method method : mapperType.getMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                EncryptResultHint hint = method.getAnnotation(EncryptResultHint.class);
                if (hint == null) {
                    continue;
                }
                preloadHintEntities(hint);
                preloadHintTables(hint, mapperType);
            }
        } catch (RuntimeException ignore) {
        }
    }

    private void registerConfiguredRules(DatabaseEncryptionProperties properties) {
        properties.getTables().forEach(tableProperties -> {
            String tableName = tableProperties.getTable();
            if (StringUtils.isBlank(tableName)) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_TABLE_RULE,
                        "Configured table rule must define table name.");
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
            throw new EncryptionConfigurationException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "Configured field rule must define column or property name.");
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
            throw new EncryptionConfigurationException(EncryptionErrorCode.MISSING_STORAGE_TABLE,
                    "Separate-table encrypted field must define storageTable. property=" + rule.property()
                            + ", table=" + firstNonBlank(rule.table(), "<entity-default-table>")
                            + ", column=" + rule.column());
        }
        if (!rule.hasAssistedQueryColumn()) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN,
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
        EncryptTable encryptTable = entityType.getAnnotation(EncryptTable.class);
        if (encryptTable != null && StringUtils.isNotBlank(encryptTable.value())) {
            return encryptTable.value();
        }
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

    private void preloadHintEntities(EncryptResultHint hint) {
        for (Class<?> entityType : hint.entities()) {
            if (entityType != null && entityType != void.class && entityType != Void.class) {
                registerEntityType(entityType);
            }
        }
    }

    private void preloadHintTables(EncryptResultHint hint, Class<?> mapperType) {
        for (String table : hint.tables()) {
            if (StringUtils.isBlank(table)) {
                continue;
            }
            if (findByTable(table).isPresent()) {
                continue;
            }
            preloadMapperRelatedEntitiesForTable(mapperType, table);
            findByTable(table);
        }
    }

    private void preloadMapperRelatedEntitiesForTable(Class<?> mapperType, String tableName) {
        if (mapperType == null || StringUtils.isBlank(tableName)) {
            return;
        }
        String normalizedTable = NameUtils.normalizeIdentifier(tableName);
        Set<Class<?>> candidates = new LinkedHashSet<Class<?>>();
        for (java.lang.reflect.Method method : mapperType.getMethods()) {
            collectTypes(method.getGenericReturnType(), candidates);
            for (Type parameterType : method.getGenericParameterTypes()) {
                collectTypes(parameterType, candidates);
            }
        }
        for (Class<?> candidate : candidates) {
            if (!isCandidateType(candidate)) {
                continue;
            }
            if (!normalizedTable.equals(NameUtils.normalizeIdentifier(resolveEntityTableName(candidate)))) {
                continue;
            }
            registerEntityType(candidate);
        }
    }

    private void collectTypes(Type type, Set<Class<?>> candidates) {
        if (type == null) {
            return;
        }
        if (type instanceof Class<?>) {
            Class<?> candidate = (Class<?>) type;
            candidates.add(candidate);
            if (candidate.isArray()) {
                candidates.add(candidate.getComponentType());
            }
            return;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            collectTypes(parameterizedType.getRawType(), candidates);
            for (Type actualTypeArgument : parameterizedType.getActualTypeArguments()) {
                collectTypes(actualTypeArgument, candidates);
            }
            return;
        }
        if (type instanceof GenericArrayType) {
            collectTypes(((GenericArrayType) type).getGenericComponentType(), candidates);
        }
    }

    private Class<?> resolveMapperType(MappedStatement mappedStatement) {
        if (mappedStatement == null || StringUtils.isBlank(mappedStatement.getId())) {
            return null;
        }
        int separator = mappedStatement.getId().lastIndexOf('.');
        if (separator <= 0 || separator >= mappedStatement.getId().length() - 1) {
            return null;
        }
        String mapperClassName = mappedStatement.getId().substring(0, separator);
        try {
            return Class.forName(mapperClassName);
        } catch (ClassNotFoundException ignore) {
            return null;
        }
    }

    private String resolveMappedMethodName(MappedStatement mappedStatement) {
        if (mappedStatement == null || StringUtils.isBlank(mappedStatement.getId())) {
            return null;
        }
        int separator = mappedStatement.getId().lastIndexOf('.');
        if (separator <= 0 || separator >= mappedStatement.getId().length() - 1) {
            return null;
        }
        return mappedStatement.getId().substring(separator + 1);
    }

    private Set<String> collectTableNames(String sql) {
        Set<String> tables = new LinkedHashSet<String>();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            collectTables(statement, tables);
        } catch (Exception ignore) {
            return tables;
        }
        return tables;
    }

    private void collectTables(Statement statement, Set<String> tables) {
        if (statement instanceof Select) {
            collectTables((Select) statement, tables);
            return;
        }
        if (statement instanceof Update) {
            Update update = (Update) statement;
            registerTableName(update.getTable(), tables);
            if (update.getFromItem() != null) {
                collectTables(update.getFromItem(), tables);
            }
            if (update.getJoins() != null) {
                for (Join join : update.getJoins()) {
                    collectTables(join.getRightItem(), tables);
                }
            }
            return;
        }
        if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            registerTableName(delete.getTable(), tables);
            return;
        }
        if (statement instanceof Insert) {
            Insert insert = (Insert) statement;
            registerTableName(insert.getTable(), tables);
            if (insert.getSelect() != null) {
                collectTables(insert.getSelect(), tables);
            }
        }
    }

    private void collectTables(Select select, Set<String> tables) {
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                collectTables(parenthesedSelect.getSelect(), tables);
            }
            return;
        }
        if (select instanceof SetOperationList) {
            SetOperationList setOperationList = (SetOperationList) select;
            for (Select child : setOperationList.getSelects()) {
                collectTables(child, tables);
            }
            return;
        }
        if (!(select instanceof PlainSelect)) {
            return;
        }
        PlainSelect plainSelect = (PlainSelect) select;
        collectTables(plainSelect.getFromItem(), tables);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                collectTables(join.getRightItem(), tables);
            }
        }
    }

    private void collectTables(FromItem fromItem, Set<String> tables) {
        if (fromItem instanceof Table) {
            registerTableName((Table) fromItem, tables);
            return;
        }
        if (fromItem instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
            if (parenthesedSelect.getSelect() != null) {
                collectTables(parenthesedSelect.getSelect(), tables);
            }
        }
    }

    private void registerTableName(Table table, Set<String> tables) {
        if (table == null || StringUtils.isBlank(table.getName())) {
            return;
        }
        tables.add(NameUtils.stripIdentifier(table.getName()));
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
