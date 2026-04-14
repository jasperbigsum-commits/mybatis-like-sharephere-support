package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原地解密 MyBatis 查询结果。
 *
 * <p>只处理已经注册加密元数据的实体类型，普通 Map 和无关结果对象会被直接忽略。</p>
 */
public class ResultDecryptor {

    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;
    private final ReflectorFactory reflectorFactory = new DefaultReflectorFactory();
    private final Map<String, QueryResultPlan> queryPlans = new ConcurrentHashMap<>();
    private final ThreadLocal<TraversalScope> queryScope = new ThreadLocal<>();

    /**
     * 创建结果解密器。
     *
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param separateTableEncryptionManager 独立表加密管理器
     */
    public ResultDecryptor(EncryptMetadataRegistry metadataRegistry,
                           AlgorithmRegistry algorithmRegistry,
                           SeparateTableEncryptionManager separateTableEncryptionManager) {
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
    }

    /**
     * 解密 MyBatis 返回的单个结果对象或结果集合。
     *
     * @param resultObject 查询结果对象
     * @return 完成解密和可选独立表回填后的同一个实例
     */
    public Object decrypt(Object resultObject) {
        if (resultObject == null) {
            return null;
        }
        QueryResultPlan queryResultPlan = currentPlan();
        if (separateTableEncryptionManager != null) {
            if (queryResultPlan != null) {
                separateTableEncryptionManager.hydrateResults(resultObject, queryResultPlan);
            } else {
                separateTableEncryptionManager.hydrateResults(resultObject);
            }
        }
        if (queryResultPlan != null) {
            decryptWithPlan(resultObject, queryResultPlan);
            return resultObject;
        }
        TraversalScope scope = queryScope.get();
        Set<Object> visited = scope != null ? scope.visited()
                : java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        decryptGraph(resultObject, visited);
        return resultObject;
    }

    /**
     * 打开一次查询结果处理作用域。
     *
     * <p>同一个顶层查询里的嵌套结果加载可能多次触发结果解密，
     * 这里通过线程内作用域共享已处理对象集合，避免重复解密同一实例。</p>
     */
    public void beginQueryScope() {
        beginQueryScope(null);
    }

    /**
     * 打开一次查询结果处理作用域，并记录当前 mapped statement 的结果计划。
     *
     * @param mappedStatement 当前查询对应的 mapped statement
     */
    public void beginQueryScope(MappedStatement mappedStatement) {
        TraversalScope scope = queryScope.get();
        if (scope == null) {
            scope = new TraversalScope();
            queryScope.set(scope);
        }
        scope.incrementDepth();
        scope.pushPlan(resolvePlan(mappedStatement));
    }

    /**
     * 关闭一次查询结果处理作用域。
     */
    public void endQueryScope() {
        TraversalScope scope = queryScope.get();
        if (scope == null) {
            return;
        }
        scope.popPlan();
        if (scope.decrementDepth() == 0) {
            queryScope.remove();
        }
    }

    private QueryResultPlan currentPlan() {
        TraversalScope scope = queryScope.get();
        return scope == null ? null : scope.currentPlan();
    }

    private QueryResultPlan resolvePlan(MappedStatement mappedStatement) {
        if (mappedStatement == null || StringUtils.isBlank(mappedStatement.getId())) {
            return null;
        }
        return queryPlans.computeIfAbsent(mappedStatement.getId(), ignored -> buildPlan(mappedStatement));
    }

    private QueryResultPlan buildPlan(MappedStatement mappedStatement) {
        if (mappedStatement == null || mappedStatement.getResultMaps() == null || mappedStatement.getResultMaps().isEmpty()) {
            return new QueryResultPlan(java.util.Collections.emptyList());
        }
        Configuration configuration = mappedStatement.getConfiguration();
        Map<Class<?>, Map<String, QueryResultPlan.PropertyPlan>> plansByType = new LinkedHashMap<>();
        for (ResultMap resultMap : mappedStatement.getResultMaps()) {
            Class<?> resultType = resultMap.getType();
            if (!isCandidateType(resultType)) {
                continue;
            }
            Map<String, QueryResultPlan.PropertyPlan> propertyPlans = plansByType.computeIfAbsent(
                    resultType, ignored -> new LinkedHashMap<>());
            collectPropertyPlans(configuration, resultType, resultMap, null, propertyPlans, new java.util.HashSet<String>());
        }
        List<QueryResultPlan.TypePlan> typePlans = new ArrayList<>();
        plansByType.forEach((resultType, propertyPlans) -> {
            if (!propertyPlans.isEmpty()) {
                typePlans.add(new QueryResultPlan.TypePlan(resultType, new ArrayList<>(propertyPlans.values())));
            }
        });
        return new QueryResultPlan(typePlans);
    }

    private void collectPropertyPlans(Configuration configuration,
                                      Class<?> rootType,
                                      ResultMap resultMap,
                                      String propertyPrefix,
                                      Map<String, QueryResultPlan.PropertyPlan> propertyPlans,
                                      Set<String> visited) {
        String visitKey = resultMap.getId() + "|" + (propertyPrefix == null ? "" : propertyPrefix);
        if (!visited.add(visitKey)) {
            return;
        }
        collectAutomaticPropertyPlans(rootType, resultMap.getType(), propertyPrefix, propertyPlans);
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            String property = resultMapping.getProperty();
            if (StringUtils.isBlank(property) || resultMapping.getNestedQueryId() != null) {
                continue;
            }
            String propertyPath = concatPropertyPath(propertyPrefix, property);
            if (resultMapping.getNestedResultMapId() != null) {
                ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                collectPropertyPlans(configuration, rootType, nestedResultMap, propertyPath, propertyPlans, visited);
                continue;
            }
            registerPropertyPlan(rootType, propertyPath, propertyPlans);
        }
    }

    private void collectAutomaticPropertyPlans(Class<?> rootType,
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

    private void registerPropertyPlan(Class<?> rootType,
                                      String propertyPath,
                                      Map<String, QueryResultPlan.PropertyPlan> propertyPlans) {
        EncryptColumnRule rule = resolvePropertyRule(rootType, propertyPath);
        if (rule == null) {
            return;
        }
        propertyPlans.putIfAbsent(propertyPath, new QueryResultPlan.PropertyPlan(propertyPath, rule));
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

    private boolean decryptWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        boolean handled = false;
        for (Object candidate : topLevelResults(resultObject)) {
            handled |= decryptPlannedCandidate(candidate, queryResultPlan);
        }
        return handled;
    }

    private boolean decryptPlannedCandidate(Object candidate, QueryResultPlan queryResultPlan) {
        if (candidate == null || candidate instanceof Map<?, ?> || isSimpleValueType(candidate.getClass())) {
            return false;
        }
        QueryResultPlan.TypePlan typePlan = queryResultPlan.findPlan(candidate.getClass());
        if (typePlan == null) {
            return false;
        }
        boolean handled = false;
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (QueryResultPlan.PropertyPlan propertyPlan : typePlan.getPropertyPlans()) {
            EncryptColumnRule rule = propertyPlan.getRule();
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            String propertyPath = propertyPlan.getPropertyPath();
            if (!metaObject.hasGetter(propertyPath) || !metaObject.hasSetter(propertyPath)) {
                continue;
            }
            Object value = metaObject.getValue(propertyPath);
            if (!(value instanceof String) || StringUtils.isBlank((String) value)) {
                continue;
            }
            metaObject.setValue(propertyPath,
                    algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt((String) value));
            handled = true;
        }
        return handled;
    }

    /**
     * 递归遍历返回结果对象图，确保集合结果和关联嵌套实体都会进入解密流程。
     *
     * <p>多表关联查询时，MyBatis 可能把加密实体挂在顶层 DTO 的子属性上。
     * 如果这里只处理顶层对象，嵌套实体里的密文字段就不会被还原。</p>
     */
    private void decryptGraph(Object candidate, Set<Object> visited) {
        if (candidate == null || isSimpleValueType(candidate.getClass())) {
            return;
        }
        if (candidate instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) candidate;
            map.values().forEach(value -> decryptGraph(value, visited));
            return;
        }
        if (candidate instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) candidate;
            collection.forEach(value -> decryptGraph(value, visited));
            return;
        }
        if (candidate.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(candidate);
            for (int index = 0; index < length; index++) {
                decryptGraph(java.lang.reflect.Array.get(candidate, index), visited);
            }
            return;
        }
        if (!visited.add(candidate)) {
            return;
        }
        decryptSingle(candidate);
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (String getterName : metaObject.getGetterNames()) {
            if ("class".equals(getterName)) {
                continue;
            }
            decryptGraph(metaObject.getValue(getterName), visited);
        }
    }

    /**
     * 解密单个实体实例上的同表密文字段。
     *
     * @param candidate MyBatis 返回的实体实例
     */
    private void decryptSingle(Object candidate) {
        if (candidate == null || candidate instanceof Map<?, ?>) {
            return;
        }
        EncryptTableRule tableRule = metadataRegistry.findByEntity(candidate.getClass()).orElse(null);
        if (tableRule == null) {
            return;
        }
        MetaObject metaObject = SystemMetaObject.forObject(candidate);
        for (EncryptColumnRule rule : tableRule.getColumnRules()) {
            if (!metaObject.hasGetter(rule.property()) || !metaObject.hasSetter(rule.property())) {
                continue;
            }
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            Object value = metaObject.getValue(rule.property());
            if (!(value instanceof String) || StringUtils.isBlank((String) value)) {
                continue;
            }
            metaObject.setValue(rule.property(),
                    algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt((String) value));
        }
    }

    private Collection<?> topLevelResults(Object resultObject) {
        if (resultObject == null) {
            return java.util.Collections.emptyList();
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
        return java.util.Collections.singletonList(resultObject);
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

    private boolean isSimpleValueType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || java.util.Date.class.isAssignableFrom(type)
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || Class.class == type;
    }

    private static final class TraversalScope {

        private final Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Deque<QueryResultPlan> plans = new java.util.LinkedList<>();
        private int depth;

        private Set<Object> visited() {
            return visited;
        }

        private void pushPlan(QueryResultPlan queryResultPlan) {
            plans.push(queryResultPlan);
        }

        private QueryResultPlan currentPlan() {
            return plans.peek();
        }

        private void popPlan() {
            if (!plans.isEmpty()) {
                plans.pop();
            }
        }

        private void incrementDepth() {
            depth++;
        }

        private int decrementDepth() {
            depth--;
            return depth;
        }
    }
}
