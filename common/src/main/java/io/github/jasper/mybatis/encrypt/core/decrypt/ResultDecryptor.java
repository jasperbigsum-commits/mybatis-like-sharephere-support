package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import io.github.jasper.mybatis.encrypt.util.ObjectTraversalUtils;
import io.github.jasper.mybatis.encrypt.util.PropertyValueAccessor;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;

/**
 * 按查询结果计划原地解密 MyBatis 返回对象。
 *
 * <p>这里只处理已经映射到返回对象上的属性，不再做对象图兜底遍历。
 * 哪些属性需要回填或解密，统一由 {@link QueryResultPlanFactory} 根据当前查询结果映射提前确定。</p>
 *
 * <p>这意味着解密边界与 MyBatis 的结果装配边界保持一致：框架不会猜测业务后续复制出来的 DTO，
 * 也不会对 SQL 表达式、聚合值或运行期再次查询覆盖后的属性做二次修正。若响应对象不是直接持有
 * 已解密字段，应在控制器边界改用 {@code @SensitiveField} 或显式返回数据库中的脱敏列。</p>
 */
public class ResultDecryptor {

    private final AlgorithmRegistry algorithmRegistry;
    private final SeparateTableEncryptionManager separateTableEncryptionManager;
    private final QueryResultPlanFactory queryResultPlanFactory;
    private final PropertyValueAccessor propertyValueAccessor = new PropertyValueAccessor();

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
     * 对当前结果集对象执行回填和解密。
     *
     * @param resultObject MyBatis 已映射完成的结果对象
     * @param queryResultPlan 当前查询对应的结果计划
     * @return 原结果对象本身
     */
    public Object decrypt(Object resultObject, QueryResultPlan queryResultPlan) {
        if (resultObject == null) {
            return null;
        }
        if (queryResultPlan == null || queryResultPlan.isEmpty()) {
            return resultObject;
        }
        if (separateTableEncryptionManager != null) {
            separateTableEncryptionManager.hydrateResults(resultObject, queryResultPlan);
        }
        decryptWithPlan(resultObject, queryResultPlan);
        return resultObject;
    }

    /**
     * 解析当前查询对应的结果计划。
     *
     * @param mappedStatement 当前结果对应的 statement
     * @param boundSql 当前最终执行 SQL
     * @return 当前查询结果计划
     */
    public QueryResultPlan resolvePlan(MappedStatement mappedStatement, BoundSql boundSql) {
        return queryResultPlanFactory.resolve(mappedStatement, boundSql);
    }

    private void decryptWithPlan(Object resultObject, QueryResultPlan queryResultPlan) {
        for (Object candidate : ObjectTraversalUtils.topLevelResults(resultObject)) {
            decryptCandidate(candidate, queryResultPlan);
        }
    }

    private void decryptCandidate(Object candidate, QueryResultPlan queryResultPlan) {
        if (candidate == null || ObjectTraversalUtils.isSimpleValueType(candidate.getClass())) {
            return;
        }
        QueryResultPlan.TypePlan typePlan = queryResultPlan.findPlan(candidate.getClass());
        if (typePlan == null) {
            return;
        }
        for (QueryResultPlan.PropertyPlan propertyPlan : typePlan.getPropertyPlans()) {
            EncryptColumnRule rule = propertyPlan.getRule();
            if (rule.isStoredInSeparateTable()) {
                continue;
            }
            String propertyPath = propertyPlan.getPropertyPath();
            PropertyValueAccessor.PropertyReference propertyReference =
                    propertyValueAccessor.resolve(candidate, propertyPath);
            if (propertyReference == null || !propertyReference.canWrite()) {
                continue;
            }
            // 每个属性都基于当前 candidate 重新解析 owner/property，不复用上一次 MetaObject 或引用；
            // 如果 getValue() 已经不是数据库密文，通常是业务 getter/setter 或二次查询覆盖了属性。
            Object value = propertyReference.getValue();
            if (!(value instanceof String) || StringUtils.isBlank((String) value)) {
                continue;
            }
            String decrypted = algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt((String) value);
            if (propertyReference.setValue(decrypted) && SensitiveDataContext.isRecording()) {
                SensitiveDataContext.record(propertyReference.owner(), propertyReference.propertyName(),
                        decrypted, rule);
            }
        }
    }
}
