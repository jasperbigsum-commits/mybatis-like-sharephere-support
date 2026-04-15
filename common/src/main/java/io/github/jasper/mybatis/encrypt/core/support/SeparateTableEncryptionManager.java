package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.decrypt.QueryResultPlan;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 独立加密表管理器。
 *
 * <p>负责两类工作：一是在主表 SQL 执行前准备独立表 hash 引用值，二是在查询结果返回后按 hash
 * 回填并解密独立加密表中的字段。</p>
 */
public class SeparateTableEncryptionManager {

    private final DataSource dataSource;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final SnowflakeIdGenerator idGenerator;
    private final SeparateTableRowPersister rowPersister;
    private final ThreadLocal<HydrationScope> hydrationScope = new ThreadLocal<>();

    /**
     * 创建独立表加密管理器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     */
    public SeparateTableEncryptionManager(DataSource dataSource,
                                          EncryptMetadataRegistry metadataRegistry,
                                          AlgorithmRegistry algorithmRegistry,
                                          DatabaseEncryptionProperties properties) {
        this(dataSource, metadataRegistry, algorithmRegistry, properties,
                new DefaultSeparateTableRowPersister(dataSource, properties));
    }

    /**
     * 创建独立表加密管理器。
     *
     * @param dataSource 数据源
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     * @param rowPersister 独立表写入执行器
     */
    public SeparateTableEncryptionManager(DataSource dataSource,
                                          EncryptMetadataRegistry metadataRegistry,
                                          AlgorithmRegistry algorithmRegistry,
                                          DatabaseEncryptionProperties properties,
                                          SeparateTableRowPersister rowPersister) {
        this.dataSource = dataSource;
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
        this.idGenerator = new SnowflakeIdGenerator();
        this.rowPersister = rowPersister;
    }

    /**
     * 为写操作预先准备独立表 hash 引用值。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     */
    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql) {
        prepareWriteReferences(mappedStatement, boundSql, null);
    }

    /**
     * 为写操作预先准备独立表 hash 引用值。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 当前 BoundSql
     * @param executor 当前业务 SQL 所使用的 executor
     */
    public void prepareWriteReferences(MappedStatement mappedStatement, BoundSql boundSql, Executor executor) {
        if (usesLegacyPrepareOverride()) {
            prepareWriteReferences(mappedStatement, boundSql);
            return;
        }
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

    private boolean usesLegacyPrepareOverride() {
        try {
            return getClass()
                    .getMethod("prepareWriteReferences", MappedStatement.class, BoundSql.class)
                    .getDeclaringClass() != SeparateTableEncryptionManager.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /**
     * 对查询结果执行独立表字段回填与解密。
     *
     * @param resultObject 查询结果对象或集合
     */
    public void hydrateResults(Object resultObject) {
        if (resultObject == null) {
            return;
        }
        hydrateCollection(collectHydrationCandidates(resultObject));
    }

    /**
     * 对查询结果执行独立表字段回填与解密，并优先使用本次查询的结果计划。
     *
     * @param resultObject 查询结果对象或集合
     * @param queryResultPlan 当前查询结果计划
     */
    public void hydrateResults(Object resultObject, QueryResultPlan queryResultPlan) {
        if (resultObject == null) {
            return;
        }
        if (queryResultPlan == null) {
            hydrateResults(resultObject);
            return;
        }
        hydrateWithPlan(resultObject, queryResultPlan);
    }

    /**
     * 打开一次查询结果回填作用域。
     *
     * <p>嵌套查询结果可能在同一个顶层查询期间被多次经过回填流程，
     * 这里共享已处理对象集合，避免把已经回填成明文的字段再次当成引用 id 处理。</p>
     */
    public void beginQueryScope() {
        HydrationScope scope = hydrationScope.get();
        if (scope == null) {
            scope = new HydrationScope();
            hydrationScope.set(scope);
        }
        scope.incrementDepth();
    }

    /**
     * 关闭一次查询结果回填作用域。
     */
    public void endQueryScope() {
        HydrationScope scope = hydrationScope.get();
        if (scope == null) {
            return;
        }
        if (scope.decrementDepth() == 0) {
            hydrationScope.remove();
        }
    }

    /**
     * 为当前待写实体准备独立表引用值。
     *
     * <p>这个方法只处理独立表字段。它会根据当前 SQL 类型和运行时参数状态，
     * 决定是复用已有引用、回查主表现有引用，还是新建独立表记录，最后再把 hash 引用值写回 BoundSql。</p>
     */
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

    /**
     * 决定本次主表写入应使用的独立表 hash 引用值。
     *
     * <p>它会先尝试复用当前 BoundSql 已准备好的引用值；如果是 UPDATE 且当前没有引用，
     * 就回主表查询已有引用；若已有引用对应的独立表记录与当前明文一致则直接复用，
     * 否则再按 assistedQueryColumn 对应的 hash 值查找可复用记录，仍然没有时新建独立表记录。</p>
     */
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
        String assignedHash = assignHash(rule, plainValue);
        if (commandType == SqlCommandType.UPDATE) {
            // 当前的hash值引用值
            String currentStoredReferenceId = loadExistingReferenceId(tableRule, rule, metaObject);
            if (currentStoredReferenceId != null
                    && matchesAssignedHash(currentStoredReferenceId, assignedHash)) {
                // 同时更新判断是否独立表表是否存在，不匹配则创建记录
                return Optional.ofNullable(findReferenceIdByAssignedHash(rule, assignedHash))
                        .orElseGet(() -> insertExternalRow(mappedStatement, rule, plainValue, assignedHash, executor));
            }
        }
        return Optional.ofNullable(findReferenceIdByAssignedHash(rule, assignedHash))
                .orElseGet(() -> insertExternalRow(mappedStatement, rule, plainValue, assignedHash, executor));
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
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to load existing separate-table reference id.", ex);
        }
    }

    /**
     * 判断指定引用是否已经指向与当前明文相同的独立表记录。
     *
     * <p>独立表当前采用增量追加模式，不做原地更新；因此只有在旧引用对应记录与当前 hash
     * 一致时才复用旧引用，否则应切换到已有同值记录或新建记录。</p>
     */
    private boolean matchesAssignedHash(String currentStoredReferenceId, String assignedHash) {
        return Objects.equals(currentStoredReferenceId, assignedHash);
    }

    /**
     * 按 assistedQueryColumn 对应的 hash 值查找可复用的独立表记录。
     *
     * <p>独立表不执行更新和删除时，相同明文应尽量复用同一条外表记录，避免重复插入。</p>
     */
    private String findReferenceIdByAssignedHash(EncryptColumnRule rule, String assignedHash) {
        requireAssistedReferenceRule(rule, "find separate-table reference");
        String sql = "select " + quote(rule.assistedQueryColumn())
                + " from " + quote(rule.storageTable())
                + " where " + quote(rule.assistedQueryColumn()) + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, assignedHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return toReferenceId(resultSet.getObject(1));
                }
                return null;
            }
        } catch (SQLException ex) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to find separate-table reference by assigned hash.", ex);
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

    /**
     * 递归收集需要执行独立表回填的结果实体。
     *
     * <p>关联查询场景下，真正带有引用 id 的加密实体可能位于顶层 DTO 的嵌套属性中，
     * 因此这里需要沿结果对象图继续下钻，而不是只扫描最外层返回对象。</p>
     */
    private List<Object> collectHydrationCandidates(Object resultObject) {
        List<Object> results = new ArrayList<>();
        HydrationScope scope = hydrationScope.get();
        Set<Object> visited = scope != null ? scope.visited()
                : Collections.newSetFromMap(new IdentityHashMap<>());
        collectHydrationCandidates(resultObject, results, visited);
        return results;
    }

    private void collectHydrationCandidates(Object candidate, List<Object> results, Set<Object> visited) {
        if (candidate == null || isSimpleValueType(candidate.getClass())) {
            return;
        }
        if (candidate instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) candidate;
            map.values().forEach(value -> collectHydrationCandidates(value, results, visited));
            return;
        }
        if (candidate instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) candidate;
            collection.forEach(value -> collectHydrationCandidates(value, results, visited));
            return;
        }
        if (candidate.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(candidate);
            for (int index = 0; index < length; index++) {
                collectHydrationCandidates(java.lang.reflect.Array.get(candidate, index), results, visited);
            }
            return;
        }
        if (!visited.add(candidate)) {
            return;
        }
        if (metadataRegistry.findByEntity(candidate.getClass()).isPresent()) {
            results.add(candidate);
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (String getterName : metaObject.getGetterNames()) {
            if ("class".equals(getterName)) {
                continue;
            }
            collectHydrationCandidates(metaObject.getValue(getterName), results, visited);
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

    private boolean hydrateWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        boolean handled = false;
        Map<HydrationKey, Map<Object, List<MetaObject>>> grouped = new LinkedHashMap<>();
        for (Object candidate : topLevelResults(resultObject)) {
            if (candidate == null || candidate instanceof Map<?, ?> || isSimpleValueType(candidate.getClass())) {
                continue;
            }
            QueryResultPlan.TypePlan typePlan = queryResultPlan.findPlan(candidate.getClass());
            if (typePlan == null) {
                continue;
            }
            handled = true;
            MetaObject metaObject = SystemMetaObject.forObject(candidate);
            for (QueryResultPlan.PropertyPlan propertyPlan : typePlan.getPropertyPlans()) {
                EncryptColumnRule rule = propertyPlan.getRule();
                if (!rule.isStoredInSeparateTable()) {
                    continue;
                }
                String propertyPath = propertyPlan.getPropertyPath();
                if (!metaObject.hasGetter(propertyPath) || !metaObject.hasSetter(propertyPath)) {
                    continue;
                }
                Object referenceId = metaObject.getValue(propertyPath);
                if (referenceId == null) {
                    continue;
                }
                grouped.computeIfAbsent(new HydrationKey(rule, propertyPath), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(normalizeReferenceId(referenceId), ignored -> new ArrayList<>())
                        .add(metaObject);
            }
        }
        grouped.forEach(this::hydratePlannedRule);
        return handled;
    }

    private void hydratePlannedRule(HydrationKey hydrationKey, Map<Object, List<MetaObject>> metaById) {
        if (metaById.isEmpty()) {
            return;
        }
        Map<Object, String> cipherById = loadCipherValues(hydrationKey.rule(), new ArrayList<>(metaById.keySet()));
        cipherById.forEach((referenceId, cipherText) -> {
            List<MetaObject> metaObjects = metaById.get(referenceId);
            if (metaObjects == null || cipherText == null) {
                return;
            }
            String plainText = algorithmRegistry.cipher(hydrationKey.rule().cipherAlgorithm()).decrypt(cipherText);
            metaObjects.forEach(metaObject -> metaObject.setValue(hydrationKey.propertyPath(), plainText));
        });
    }

    private Map<Object, String> loadCipherValues(EncryptColumnRule rule, List<Object> ids) {
        requireAssistedReferenceRule(rule, "load separate-table encrypted values");
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = "select " + quote(rule.assistedQueryColumn()) + ", " + quote(rule.storageColumn())
                + " from " + quote(rule.storageTable())
                + " where " + quote(rule.assistedQueryColumn()) + " in (" + placeholders + ")";
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
            throw new EncryptionConfigurationException(EncryptionErrorCode.SEPARATE_TABLE_OPERATION_FAILED,
                    "Failed to load separate-table encrypted values.", ex);
        }
    }

    /**
     * 生成一条新的独立表记录并返回 hash 引用值。
     *
     * <p>独立表主键由框架内部使用雪花算法预生成，再与密文列一起写入外表；
     * 主表实际保存的是 assistedQueryColumn 对应的 hash 值，避免跨系统依赖独立表内部主键。</p>
     */
    private String insertExternalRow(MappedStatement mappedStatement,
                                     EncryptColumnRule rule,
                                     Object plainValue,
                                     String assignedHash,
                                     Executor executor) {
        ExternalRowValues values = buildExternalRowValues(rule, plainValue, assignedHash);
        long generatedId = idGenerator.nextId();
        LinkedHashMap<String, Object> columnValues = new LinkedHashMap<>();
        columnValues.put(rule.storageIdColumn(), generatedId);
        for (int index = 0; index < values.columns().size(); index++) {
            columnValues.put(values.columns().get(index), values.values().get(index));
        }
        rowPersister.insert(new SeparateTableInsertRequest(rule.storageTable(), columnValues), mappedStatement, executor);
        return assignedHash;
    }

    /**
     * 构造独立表写入使用的列和值。
     *
     * <p>独立表当前采用增量追加模式，相同明文依赖 hash 复用，未命中时再插入一条新记录。</p>
     */
    private ExternalRowValues buildExternalRowValues(EncryptColumnRule rule, Object plainValue, String assignedHash) {
        requireAssistedReferenceRule(rule, "build separate-table row");
        // 独立表只做增量插入，因此这里统一生成插入所需的密文列、辅助列和 like 列。
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        String plainText = String.valueOf(plainValue);
        columns.add(rule.storageColumn());
        values.add(algorithmRegistry.cipher(rule.cipherAlgorithm()).encrypt(plainText));
        columns.add(rule.assistedQueryColumn());
        values.add(assignedHash);
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
            return Collections.emptyList();
        }
        List<Object> results = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectCandidates(parameterObject, results, visited);
        return results;
    }

    private void collectCandidates(Object parameterObject, List<Object> results, Set<Object> visited) {
        if (parameterObject == null) {
            return;
        }
        if (parameterObject instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) parameterObject;
            map.values().forEach(value -> collectCandidates(value, results, visited));
            return;
        }
        if (parameterObject instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) parameterObject;
            collection.forEach(value -> collectCandidates(value, results, visited));
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
        parameterPaths.forEach(path -> preparedReferences.put(path, referenceId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preparedReferences(BoundSql boundSql) {
        if (boundSql.hasAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER)) {
            Object existing = boundSql.getAdditionalParameter(ParameterValueResolver.PREPARED_REFERENCE_PARAMETER);
            if (existing instanceof Map<?, ?>) {
                return (Map<String, Object>) existing;
            }
        }
        Map<String, Object> preparedReferences = new LinkedHashMap<>();
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
        return new ArrayList<>(paths);
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

    private String toReferenceId(Object referenceId) {
        if (referenceId == null) {
            return null;
        }
        String value = String.valueOf(referenceId);
        return StringUtils.isBlank(value) ? null : value;
    }

    private Object normalizeReferenceId(Object referenceId) {
        return toReferenceId(referenceId);
    }

    private Collection<?> topLevelResults(Object resultObject) {
        if (resultObject == null) {
            return Collections.emptyList();
        }
        if (resultObject instanceof Collection<?>) {
            return (Collection<?>) resultObject;
        }
        if (resultObject.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(resultObject);
            List<Object> results = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                results.add(java.lang.reflect.Array.get(resultObject, index));
            }
            return results;
        }
        return Collections.singletonList(resultObject);
    }

    private String assignHash(EncryptColumnRule rule, Object plainValue) {
        requireAssistedReferenceRule(rule, "prepare separate-table hash reference");
        return algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(String.valueOf(plainValue));
    }

    private void requireAssistedReferenceRule(EncryptColumnRule rule, String action) {
        if (!rule.hasAssistedQueryColumn()) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN,
                    "Separate-table encrypted field requires assistedQueryColumn to " + action
                            + ". property=" + rule.property()
                            + ", table=" + rule.table()
                            + ", column=" + rule.column()
                            + ", storageTable=" + rule.storageTable());
        }
        if (StringUtils.isBlank(rule.assistedQueryAlgorithm())) {
            throw new EncryptionConfigurationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_ALGORITHM,
                    "Separate-table encrypted field requires assistedQueryAlgorithm to " + action
                            + ". property=" + rule.property()
                            + ", table=" + rule.table()
                            + ", column=" + rule.column()
                            + ", storageTable=" + rule.storageTable());
        }
    }

    private boolean isSimpleValueType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Date.class.isAssignableFrom(type)
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || Class.class == type;
    }

    private static final class HydrationScope {

        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private int depth;

        private Set<Object> visited() {
            return visited;
        }

        private void incrementDepth() {
            depth++;
        }

        private int decrementDepth() {
            depth--;
            return depth;
        }
    }

    private static final class HydrationKey {

        private final EncryptColumnRule rule;
        private final String propertyPath;

        private HydrationKey(EncryptColumnRule rule, String propertyPath) {
            this.rule = rule;
            this.propertyPath = propertyPath;
        }

        private EncryptColumnRule rule() {
            return rule;
        }

        private String propertyPath() {
            return propertyPath;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HydrationKey)) {
                return false;
            }
            HydrationKey that = (HydrationKey) other;
            return Objects.equals(rule, that.rule) && Objects.equals(propertyPath, that.propertyPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rule, propertyPath);
        }
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
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
