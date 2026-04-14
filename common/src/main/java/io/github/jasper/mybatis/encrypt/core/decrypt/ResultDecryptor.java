package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 按查询结果计划原地解密 MyBatis 返回对象。
 *
 * <p>这里只处理已经映射到返回对象上的属性，不再做对象图兜底遍历。
 * 哪些属性需要回填或解密，统一由 {@link QueryResultPlanFactory} 根据当前查询结果映射提前确定。</p>
 */
public class ResultDecryptor {

    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;
    private final QueryResultPlanFactory queryResultPlanFactory;
    private final ThreadLocal<QueryScope> queryScope = new ThreadLocal<>();

    /**
     * 结果解密器
     * @param metadataRegistry 元数据注册
     * @param algorithmRegistry 算法注册
     * @param separateTableEncryptionManager 独立表加密管理器
     */
    public ResultDecryptor(EncryptMetadataRegistry metadataRegistry,
                           AlgorithmRegistry algorithmRegistry,
                           SeparateTableEncryptionManager separateTableEncryptionManager) {
        this.algorithmRegistry = algorithmRegistry;
        this.separateTableEncryptionManager = separateTableEncryptionManager;
        this.queryResultPlanFactory = new QueryResultPlanFactory(metadataRegistry);
    }

    /**
     * 记录当前结果集处理上下文。
     *
     * @param mappedStatement 当前结果对应的 statement
     * @param boundSql 当前最终执行 SQL
     */
    public void beginQueryScope(MappedStatement mappedStatement, BoundSql boundSql) {
        QueryScope scope = queryScope.get();
        if (scope == null) {
            scope = new QueryScope();
            queryScope.set(scope);
        }
        scope.incrementDepth();
        scope.pushPlan(queryResultPlanFactory.resolve(mappedStatement, boundSql));
    }

    /**
     * 关闭当前结果集处理上下文。
     */
    public void endQueryScope() {
        QueryScope scope = queryScope.get();
        if (scope == null) {
            return;
        }
        scope.popPlan();
        if (scope.decrementDepth() == 0) {
            queryScope.remove();
        }
    }

    /**
     * 对当前结果集对象执行回填和解密。
     *
     * @param resultObject MyBatis 已映射完成的结果对象
     * @return 原结果对象本身
     */
    public Object decrypt(Object resultObject) {
        if (resultObject == null) {
            return null;
        }
        QueryResultPlan queryResultPlan = currentPlan();
        if (queryResultPlan == null || queryResultPlan.isEmpty()) {
            return resultObject;
        }
        if (separateTableEncryptionManager != null) {
            separateTableEncryptionManager.hydrateResults(resultObject, queryResultPlan);
        }
        decryptWithPlan(resultObject, queryResultPlan);
        return resultObject;
    }

    private QueryResultPlan currentPlan() {
        QueryScope scope = queryScope.get();
        return scope == null ? null : scope.currentPlan();
    }

    private void decryptWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        for (Object candidate : topLevelResults(resultObject)) {
            decryptCandidate(candidate, queryResultPlan);
        }
    }

    private void decryptCandidate(Object candidate, QueryResultPlan queryResultPlan) {
        if (candidate == null || candidate instanceof Map<?, ?> || isSimpleValueType(candidate.getClass())) {
            return;
        }
        QueryResultPlan.TypePlan typePlan = queryResultPlan.findPlan(candidate.getClass());
        if (typePlan == null) {
            return;
        }
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
        }
    }

    private Collection<?> topLevelResults(Object resultObject) {
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

    private static final class QueryScope {

        private final Deque<QueryResultPlan> plans = new java.util.LinkedList<>();
        private int depth;

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
