package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根据当前 MyBatis 查询元数据构建结果解密计划。
 *
 * <p>这个工厂把“结果类型 -> 需要处理的属性路径 -> 对应加密规则”收敛成可复用的
 * {@link QueryResultPlan}。这样结果解密阶段只需要顺序处理命中的属性，不必再遍历整个对象图。</p>
 *
 * <p>推断遵循保守原则：优先使用实体元数据、ResultMap、SQL 投影列和
 * {@link io.github.jasper.mybatis.encrypt.annotation.EncryptResultHint}；如果列来源在多表 join、
 * set operation、复杂表达式或嵌套派生表中无法唯一确定，就返回更小的计划甚至空计划，而不是
 * 进行高风险猜测。</p>
 */
public final class QueryResultPlanFactory {

    private static final String HIDDEN_ASSISTED_PREFIX = "__enc_assisted_";
    private static final String HIDDEN_LIKE_PREFIX = "__enc_like_";

    private final EncryptMetadataRegistry metadataRegistry;
    private final ReflectorFactory reflectorFactory = new DefaultReflectorFactory();
    private final Map<String, QueryResultPlan> queryPlans = new ConcurrentHashMap<>();

    /**
     * 查询结果计划生成工厂
     * @param metadataRegistry 原数据注册
     */
    public QueryResultPlanFactory(EncryptMetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
    }

    /**
     * 解析当前查询对应的结果计划。
     *
     * @param mappedStatement 当前查询 statement
     * @param boundSql 当前最终执行 SQL
     * @return 结果计划；没有可解密字段时返回空计划
     */
    public QueryResultPlan resolve(MappedStatement mappedStatement, BoundSql boundSql) {
        if (mappedStatement == null || StringUtils.isBlank(mappedStatement.getId())) {
            return QueryResultPlan.empty();
        }
        String cacheKey = buildCacheKey(mappedStatement, boundSql);
        return queryPlans.computeIfAbsent(cacheKey, ignored -> buildPlan(mappedStatement, boundSql));
    }

    private String buildCacheKey(MappedStatement mappedStatement, BoundSql boundSql) {
        if (boundSql == null || StringUtils.isBlank(boundSql.getSql())) {
            return mappedStatement.getId();
        }
        return mappedStatement.getId() + "::" + boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private QueryResultPlan buildPlan(MappedStatement mappedStatement, BoundSql boundSql) {
        if (mappedStatement.getResultMaps() == null || mappedStatement.getResultMaps().isEmpty()) {
            return QueryResultPlan.empty();
        }
        metadataRegistry.preloadResultHintMetadata(mappedStatement);
        metadataRegistry.preloadStatementTableMetadata(mappedStatement, boundSql == null ? null : boundSql.getSql());
        Configuration configuration = mappedStatement.getConfiguration();
        ResultProjectionRuleResolver projectionRuleResolver =
                ResultProjectionRuleResolver.create(metadataRegistry, boundSql == null ? null : boundSql.getSql());
        Map<Class<?>, Map<String, QueryResultPlan.PropertyPlan>> plansByType = new LinkedHashMap<>();
        for (ResultMap resultMap : mappedStatement.getResultMaps()) {
            Class<?> resultType = resultMap.getType();
            if (!isCandidateType(resultType)) {
                continue;
            }
            Map<String, QueryResultPlan.PropertyPlan> propertyPlans =
                    plansByType.computeIfAbsent(resultType, ignored -> new LinkedHashMap<>());
            collectPropertyPlans(configuration, resultType, resultMap, null, propertyPlans,
                    new java.util.HashSet<>(), projectionRuleResolver);
        }
        if (plansByType.isEmpty()) {
            return QueryResultPlan.empty();
        }
        List<QueryResultPlan.TypePlan> typePlans = new ArrayList<>();
        plansByType.forEach((resultType, propertyPlans) -> {
            if (!propertyPlans.isEmpty()) {
                typePlans.add(new QueryResultPlan.TypePlan(resultType, new ArrayList<>(propertyPlans.values())));
            }
        });
        return typePlans.isEmpty() ? QueryResultPlan.empty() : new QueryResultPlan(typePlans);
    }

    private void collectPropertyPlans(Configuration configuration,
                                      Class<?> rootType,
                                      ResultMap resultMap,
                                      String propertyPrefix,
                                      Map<String, QueryResultPlan.PropertyPlan> propertyPlans,
                                      Set<String> visited,
                                      ResultProjectionRuleResolver projectionRuleResolver) {
        String visitKey = resultMap.getId() + "|" + (propertyPrefix == null ? "" : propertyPrefix);
        if (!visited.add(visitKey)) {
            return;
        }
        collectEntityPropertyPlans(rootType, resultMap.getType(), propertyPrefix, propertyPlans);
        collectProjectedPropertyPlans(configuration, resultMap.getType(), propertyPrefix, propertyPlans,
                projectionRuleResolver);
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            String property = resultMapping.getProperty();
            if (StringUtils.isBlank(property) || resultMapping.getNestedQueryId() != null) {
                continue;
            }
            String propertyPath = concatPropertyPath(propertyPrefix, property);
            if (resultMapping.getNestedResultMapId() != null) {
                ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                collectPropertyPlans(configuration, rootType, nestedResultMap, propertyPath, propertyPlans,
                        visited, projectionRuleResolver);
                continue;
            }
            registerMappedPropertyPlan(configuration, rootType, resultMap.getType(), propertyPath, resultMapping,
                    propertyPlans, projectionRuleResolver);
        }
    }

    private void collectEntityPropertyPlans(Class<?> rootType,
                                            Class<?> mappedType,
                                            String propertyPrefix,
                                            Map<String, QueryResultPlan.PropertyPlan> propertyPlans) {
        EncryptTableRule tableRule = metadataRegistry.findByEntity(mappedType).orElse(null);
        if (tableRule == null) {
            return;
        }
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            String propertyPath = concatPropertyPath(propertyPrefix, rule.property());
            registerPropertyPlan(rootType, propertyPath, propertyPlans);
        }
    }

    private void collectProjectedPropertyPlans(Configuration configuration,
                                               Class<?> mappedType,
                                               String propertyPrefix,
                                               Map<String, QueryResultPlan.PropertyPlan> propertyPlans,
                                               ResultProjectionRuleResolver projectionRuleResolver) {
        if (projectionRuleResolver.isEmpty()) {
            return;
        }
        MetaClass metaClass = MetaClass.forClass(mappedType, reflectorFactory);
        for (ProjectedRule projectedRule : projectionRuleResolver.projectedRules()) {
            String property = metaClass.findProperty(projectedRule.projectedColumn(),
                    configuration.isMapUnderscoreToCamelCase());
            if (StringUtils.isBlank(property)) {
                property = NameUtils.columnToProperty(projectedRule.projectedColumn());
            }
            if (StringUtils.isBlank(property)
                    || !(metaClass.hasGetter(property) || metaClass.hasSetter(property))) {
                continue;
            }
            String propertyPath = concatPropertyPath(propertyPrefix, property);
            propertyPlans.putIfAbsent(propertyPath,
                    new QueryResultPlan.PropertyPlan(propertyPath, projectedRule.rule()));
        }
    }

    private void registerMappedPropertyPlan(Configuration configuration,
                                            Class<?> rootType,
                                            Class<?> mappedType,
                                            String propertyPath,
                                            ResultMapping resultMapping,
                                            Map<String, QueryResultPlan.PropertyPlan> propertyPlans,
                                            ResultProjectionRuleResolver projectionRuleResolver) {
        if (registerPropertyPlan(rootType, propertyPath, propertyPlans)) {
            return;
        }
        EncryptColumnRule rule = resolveMappedPropertyRule(configuration, rootType, mappedType, propertyPath,
                resultMapping, projectionRuleResolver);
        if (rule == null) {
            return;
        }
        propertyPlans.putIfAbsent(propertyPath, new QueryResultPlan.PropertyPlan(propertyPath, rule));
    }

    private boolean registerPropertyPlan(Class<?> rootType,
                                         String propertyPath,
                                         Map<String, QueryResultPlan.PropertyPlan> propertyPlans) {
        EncryptColumnRule rule = resolvePropertyRule(rootType, propertyPath);
        if (rule == null) {
            return false;
        }
        propertyPlans.putIfAbsent(propertyPath, new QueryResultPlan.PropertyPlan(propertyPath, rule));
        return true;
    }

    private EncryptColumnRule resolveMappedPropertyRule(Configuration configuration,
                                                        Class<?> rootType,
                                                        Class<?> mappedType,
                                                        String propertyPath,
                                                        ResultMapping resultMapping,
                                                        ResultProjectionRuleResolver projectionRuleResolver) {
        String mappedColumn = sanitizeMappedColumn(resultMapping.getColumn());
        Class<?> ownerType = resolvePropertyOwnerType(rootType, propertyPath);
        String propertyName = lastPropertyName(propertyPath);
        EncryptColumnRule rule = resolveRuleFromEntity(ownerType, propertyName, mappedColumn);
        if (rule != null) {
            return rule;
        }
        rule = resolveRuleFromEntity(mappedType, propertyName, mappedColumn);
        if (rule != null) {
            return rule;
        }
        rule = projectionRuleResolver.resolve(mappedColumn).orElse(null);
        if (rule != null) {
            return rule;
        }
        if (StringUtils.isBlank(mappedColumn)) {
            return null;
        }
        String inferredProperty = NameUtils.columnToProperty(mappedColumn);
        if (StringUtils.isBlank(inferredProperty)) {
            return null;
        }
        if (configuration.isMapUnderscoreToCamelCase()) {
            inferredProperty = NameUtils.columnToProperty(mappedColumn);
        }
        return resolveRuleFromEntity(ownerType, inferredProperty, mappedColumn);
    }

    private EncryptColumnRule resolvePropertyRule(Class<?> rootType, String propertyPath) {
        Class<?> ownerType = resolvePropertyOwnerType(rootType, propertyPath);
        if (ownerType == null) {
            return null;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(ownerType).orElse(null);
        if (tableRule == null) {
            return null;
        }
        return tableRule.findByProperty(lastPropertyName(propertyPath)).orElse(null);
    }

    private EncryptColumnRule resolveRuleFromEntity(Class<?> entityType, String property, String mappedColumn) {
        if (entityType == null) {
            return null;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(entityType).orElse(null);
        if (tableRule == null) {
            return null;
        }
        EncryptColumnRule rule = tableRule.findByProperty(property).orElse(null);
        if (rule != null) {
            return rule;
        }
        return mappedColumn == null ? null : findProjectedColumnRule(tableRule, mappedColumn);
    }

    private EncryptColumnRule findProjectedColumnRule(EncryptTableRule tableRule, String mappedColumn) {
        if (tableRule == null || StringUtils.isBlank(mappedColumn)) {
            return null;
        }
        String normalized = NameUtils.normalizeIdentifier(mappedColumn);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (matchesProjectedColumn(rule, normalized)) {
                return rule;
            }
        }
        return null;
    }

    private boolean matchesProjectedColumn(EncryptColumnRule rule, String normalizedColumn) {
        return NameUtils.normalizeIdentifier(rule.column()).equals(normalizedColumn)
                || NameUtils.normalizeIdentifier(rule.storageColumn()).equals(normalizedColumn)
                || (rule.hasAssistedQueryColumn()
                && NameUtils.normalizeIdentifier(rule.assistedQueryColumn()).equals(normalizedColumn))
                || (rule.hasLikeQueryColumn()
                && NameUtils.normalizeIdentifier(rule.likeQueryColumn()).equals(normalizedColumn));
    }

    private Class<?> resolvePropertyOwnerType(Class<?> rootType, String propertyPath) {
        if (rootType == null || StringUtils.isBlank(propertyPath)) {
            return null;
        }
        PropertyTokenizer tokenizer = new PropertyTokenizer(propertyPath);
        Class<?> currentType = rootType;
        while (tokenizer.getChildren() != null) {
            MetaClass metaClass = MetaClass.forClass(currentType, reflectorFactory);
            String name = tokenizer.getName();
            if (metaClass.hasGetter(name)) {
                currentType = metaClass.getGetterType(name);
            } else if (metaClass.hasSetter(name)) {
                currentType = metaClass.getSetterType(name);
            } else {
                return null;
            }
            tokenizer = new PropertyTokenizer(tokenizer.getChildren());
        }
        return currentType;
    }

    private String sanitizeMappedColumn(String mappedColumn) {
        if (StringUtils.isBlank(mappedColumn)) {
            return null;
        }
        String sanitized = mappedColumn.trim();
        int commaIndex = sanitized.indexOf(',');
        int equalsIndex = sanitized.indexOf('=');
        if (commaIndex >= 0 || equalsIndex >= 0) {
            return null;
        }
        return NameUtils.stripIdentifier(sanitized);
    }

    private String concatPropertyPath(String propertyPrefix, String property) {
        return StringUtils.isBlank(propertyPrefix) ? property : propertyPrefix + "." + property;
    }

    private String lastPropertyName(String propertyPath) {
        int dotIndex = propertyPath.lastIndexOf('.');
        return dotIndex >= 0 ? propertyPath.substring(dotIndex + 1) : propertyPath;
    }

    private boolean isCandidateType(Class<?> type) {
        return type != null
                && !type.isPrimitive()
                && !type.isEnum()
                && !type.getName().startsWith("java.");
    }

    private static final class ResultProjectionRuleResolver {

        private final EncryptMetadataRegistry metadataRegistry;
        private final Map<String, EncryptColumnRule> rulesByProjectedColumn = new LinkedHashMap<>();
        private final List<ProjectedRule> projectedRules = new ArrayList<>();

        private ResultProjectionRuleResolver(EncryptMetadataRegistry metadataRegistry) {
            this.metadataRegistry = metadataRegistry;
        }

        private static ResultProjectionRuleResolver create(EncryptMetadataRegistry metadataRegistry, String sql) {
            ResultProjectionRuleResolver resolver = new ResultProjectionRuleResolver(metadataRegistry);
            if (metadataRegistry == null || StringUtils.isBlank(sql)) {
                return resolver;
            }
            try {
                Statement statement = JSqlParserSupport.parseStatement(sql);
                if (statement instanceof Select) {
                    resolver.collect((Select) statement);
                }
            } catch (Exception ignore) {
                return resolver;
            }
            return resolver;
        }

        private boolean isEmpty() {
            return rulesByProjectedColumn.isEmpty();
        }

        private List<ProjectedRule> projectedRules() {
            return projectedRules;
        }

        private Optional<EncryptColumnRule> resolve(String projectedColumn) {
            if (StringUtils.isBlank(projectedColumn)) {
                return Optional.empty();
            }
            return Optional.ofNullable(rulesByProjectedColumn.get(NameUtils.normalizeIdentifier(projectedColumn)));
        }

        private void collect(Select select) {
            ProjectionTableContext tableContext = new ProjectionTableContext();
            collectSelect(tableContext, select);
        }

        private void collectSelect(ProjectionTableContext tableContext, Select select) {
            if (select instanceof ParenthesedSelect) {
                ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
                if (parenthesedSelect.getSelect() != null) {
                    collectSelect(tableContext, parenthesedSelect.getSelect());
                }
                return;
            }
            if (select instanceof SetOperationList) {
                SetOperationList setOperationList = (SetOperationList) select;
                if (!setOperationList.getSelects().isEmpty()) {
                    collectSelect(tableContext, setOperationList.getSelect(0));
                }
                return;
            }
            if (!(select instanceof PlainSelect)) {
                return;
            }
            PlainSelect plainSelect = (PlainSelect) select;
            registerLookupFromItem(tableContext, plainSelect.getFromItem());
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    registerLookupFromItem(tableContext, join.getRightItem());
                }
            }
            List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
            if (selectItems == null) {
                return;
            }
            for (SelectItem<?> item : selectItems) {
                Expression expression = item.getExpression();
                if (expression instanceof AllTableColumns) {
                    AllTableColumns allTableColumns = (AllTableColumns) expression;
                    for (EncryptColumnRule rule : tableContext.rulesForSelectExpansion(allTableColumns.getTable())) {
                        registerProjection(rule.column(), rule);
                    }
                    continue;
                }
                if (expression instanceof AllColumns) {
                    for (EncryptColumnRule rule : tableContext.rulesForSelectExpansion(null)) {
                        registerProjection(rule.column(), rule);
                    }
                    continue;
                }
                if (!(expression instanceof Column)) {
                    continue;
                }
                Column column = (Column) expression;
                EncryptColumnRule rule = tableContext.resolveProjected(column).orElse(null);
                if (rule == null) {
                    continue;
                }
                String alias = item.getAlias() != null && StringUtils.isNotBlank(item.getAlias().getName())
                        ? item.getAlias().getName()
                        : column.getColumnName();
                if (alias.startsWith(HIDDEN_ASSISTED_PREFIX) || alias.startsWith(HIDDEN_LIKE_PREFIX)) {
                    continue;
                }
                registerProjection(alias, rule);
            }
        }

        private void registerProjection(String projectedColumn, EncryptColumnRule rule) {
            if (StringUtils.isBlank(projectedColumn) || rule == null) {
                return;
            }
            String normalized = NameUtils.normalizeIdentifier(projectedColumn);
            if (rulesByProjectedColumn.putIfAbsent(normalized, rule) == null) {
                projectedRules.add(new ProjectedRule(NameUtils.stripIdentifier(projectedColumn), rule));
            }
        }

        private void registerLookupFromItem(ProjectionTableContext tableContext, FromItem fromItem) {
            if (fromItem instanceof Table) {
                Table table = (Table) fromItem;
                metadataRegistry.findByTable(table.getName()).ifPresent(tableRule -> tableContext.register(table.getName(),
                        table.getAlias() != null ? table.getAlias().getName() : null, tableRule));
                return;
            }
            if (fromItem instanceof ParenthesedSelect) {
                ParenthesedSelect parenthesedSelect = (ParenthesedSelect) fromItem;
                if (parenthesedSelect.getAlias() != null
                        && StringUtils.isNotBlank(parenthesedSelect.getAlias().getName())
                        && parenthesedSelect.getSelect() != null) {
                    EncryptTableRule derivedRule = buildDerivedRule(
                            parenthesedSelect.getAlias().getName(), parenthesedSelect.getSelect());
                    if (derivedRule != null) {
                        tableContext.registerDerived(parenthesedSelect.getAlias().getName(), derivedRule);
                    }
                }
            }
        }

        private EncryptTableRule buildDerivedRule(String alias, Select select) {
            if (select instanceof ParenthesedSelect) {
                ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
                if (parenthesedSelect.getSelect() != null) {
                    return buildDerivedRule(alias, parenthesedSelect.getSelect());
                }
            }
            if (select instanceof SetOperationList) {
                SetOperationList setOperationList = (SetOperationList) select;
                return setOperationList.getSelects().isEmpty() ? null
                        : buildDerivedRule(alias, setOperationList.getSelect(0));
            }
            if (!(select instanceof PlainSelect)) {
                return null;
            }
            PlainSelect plainSelect = (PlainSelect) select;
            ProjectionTableContext tableContext = new ProjectionTableContext();
            registerLookupFromItem(tableContext, plainSelect.getFromItem());
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    registerLookupFromItem(tableContext, join.getRightItem());
                }
            }
            EncryptTableRule derivedRule = new EncryptTableRule(alias);
            List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
            if (selectItems == null) {
                return null;
            }
            for (SelectItem<?> item : selectItems) {
                Expression expression = item.getExpression();
                if (expression instanceof AllTableColumns) {
                    AllTableColumns allTableColumns = (AllTableColumns) expression;
                    for (EncryptColumnRule rule : tableContext.rulesForSelectExpansion(allTableColumns.getTable())) {
                        derivedRule.addColumnRule(projectDerivedRule(rule.column(), rule));
                    }
                    continue;
                }
                if (expression instanceof AllColumns) {
                    for (EncryptColumnRule rule : tableContext.rulesForSelectExpansion(null)) {
                        derivedRule.addColumnRule(projectDerivedRule(rule.column(), rule));
                    }
                    continue;
                }
                if (!(expression instanceof Column)) {
                    continue;
                }
                Column column = (Column) expression;
                EncryptColumnRule sourceRule = tableContext.resolveProjected(column).orElse(null);
                if (sourceRule == null) {
                    continue;
                }
                String aliasName = item.getAlias() != null && StringUtils.isNotBlank(item.getAlias().getName())
                        ? item.getAlias().getName()
                        : column.getColumnName();
                if (aliasName.startsWith(HIDDEN_ASSISTED_PREFIX) || aliasName.startsWith(HIDDEN_LIKE_PREFIX)) {
                    continue;
                }
                derivedRule.addColumnRule(projectDerivedRule(aliasName, sourceRule));
            }
            return derivedRule.getColumnRules().isEmpty() ? null : derivedRule;
        }

        private EncryptColumnRule projectDerivedRule(String projectedColumn, EncryptColumnRule sourceRule) {
            boolean storedInSeparateTable = sourceRule.isStoredInSeparateTable();
            return new EncryptColumnRule(
                    projectedColumn,
                    sourceRule.table(),
                    projectedColumn,
                    sourceRule.cipherAlgorithm(),
                    storedInSeparateTable
                            ? sourceRule.assistedQueryColumn()
                            : (sourceRule.hasAssistedQueryColumn() ? HIDDEN_ASSISTED_PREFIX + projectedColumn : null),
                    sourceRule.assistedQueryAlgorithm(),
                    storedInSeparateTable
                            ? sourceRule.likeQueryColumn()
                            : (sourceRule.hasLikeQueryColumn() ? HIDDEN_LIKE_PREFIX + projectedColumn : null),
                    sourceRule.likeQueryAlgorithm(),
                    sourceRule.maskedColumn(),
                    sourceRule.maskedAlgorithm(),
                    sourceRule.storageMode(),
                    sourceRule.storageTable(),
                    storedInSeparateTable ? sourceRule.storageColumn() : projectedColumn,
                    sourceRule.storageIdColumn()
            );
        }
    }

    private static final class ProjectionTableContext {

        private final Map<String, EncryptTableRule> ruleByAlias = new LinkedHashMap<>();

        private void register(String tableName, String alias, EncryptTableRule rule) {
            ruleByAlias.put(NameUtils.normalizeIdentifier(tableName), rule);
            if (StringUtils.isNotBlank(alias)) {
                ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
            }
        }

        private void registerDerived(String alias, EncryptTableRule rule) {
            if (StringUtils.isNotBlank(alias)) {
                ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
            }
        }

        private List<EncryptColumnRule> rulesForSelectExpansion(Table table) {
            if (table != null && StringUtils.isNotBlank(table.getName())) {
                EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(table.getName()));
                return tableRule == null ? java.util.Collections.emptyList()
                        : new ArrayList<>(tableRule.getColumnRules());
            }
            Collection<EncryptTableRule> uniqueRules = uniqueRules();
            if (uniqueRules.size() != 1) {
                return java.util.Collections.emptyList();
            }
            return new ArrayList<>(uniqueRules.iterator().next().getColumnRules());
        }

        private Optional<EncryptColumnRule> resolveProjected(Column column) {
            if (column.getTable() != null && StringUtils.isNotBlank(column.getTable().getName())) {
                EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(column.getTable().getName()));
                if (tableRule != null) {
                    return Optional.ofNullable(matchProjectedRule(tableRule, column.getColumnName()));
                }
            }
            EncryptColumnRule candidate = null;
            for (EncryptTableRule tableRule : uniqueRules()) {
                EncryptColumnRule rule = matchProjectedRule(tableRule, column.getColumnName());
                if (rule == null) {
                    continue;
                }
                if (candidate != null) {
                    return Optional.empty();
                }
                candidate = rule;
            }
            return Optional.ofNullable(candidate);
        }

        private EncryptColumnRule matchProjectedRule(EncryptTableRule tableRule, String columnName) {
            String normalized = NameUtils.normalizeIdentifier(columnName);
            for (EncryptColumnRule rule : tableRule.getColumnRules()) {
                if (NameUtils.normalizeIdentifier(rule.column()).equals(normalized)
                        || NameUtils.normalizeIdentifier(rule.storageColumn()).equals(normalized)
                        || (rule.hasAssistedQueryColumn()
                        && NameUtils.normalizeIdentifier(rule.assistedQueryColumn()).equals(normalized))
                        || (rule.hasLikeQueryColumn()
                        && NameUtils.normalizeIdentifier(rule.likeQueryColumn()).equals(normalized))) {
                    return rule;
                }
            }
            return null;
        }

        private Collection<EncryptTableRule> uniqueRules() {
            return new LinkedHashSet<>(ruleByAlias.values());
        }
    }

    private static final class ProjectedRule {

        private final String projectedColumn;
        private final EncryptColumnRule rule;

        private ProjectedRule(String projectedColumn, EncryptColumnRule rule) {
            this.projectedColumn = projectedColumn;
            this.rule = rule;
        }

        private String projectedColumn() {
            return projectedColumn;
        }

        private EncryptColumnRule rule() {
            return rule;
        }
    }
}
