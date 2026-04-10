package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 原地解密 MyBatis 查询结果。
 *
 * <p>只处理已经注册加密元数据的实体类型，普通 Map 和无关结果对象会被直接忽略。</p>
 */
public class ResultDecryptor {

    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;
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
        if (separateTableEncryptionManager != null) {
            separateTableEncryptionManager.hydrateResults(resultObject);
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
        TraversalScope scope = queryScope.get();
        if (scope == null) {
            scope = new TraversalScope();
            queryScope.set(scope);
        }
        scope.incrementDepth();
    }

    /**
     * 关闭一次查询结果处理作用域。
     */
    public void endQueryScope() {
        TraversalScope scope = queryScope.get();
        if (scope == null) {
            return;
        }
        if (scope.decrementDepth() == 0) {
            queryScope.remove();
        }
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
}
