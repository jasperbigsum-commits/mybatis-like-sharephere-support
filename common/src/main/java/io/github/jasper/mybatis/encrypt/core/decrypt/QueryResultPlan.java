package io.github.jasper.mybatis.encrypt.core.decrypt;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前查询结果的定向处理计划。
 *
 * <p>计划由 MyBatis 的 ResultMap 解析而来，用于把“本次查询真正会映射到哪些属性”
 * 收敛成一组属性路径，避免对整个返回对象图做无差别遍历。</p>
 */
public final class QueryResultPlan {

    private static final QueryResultPlan EMPTY = new QueryResultPlan(Collections.emptyList());

    private final List<TypePlan> typePlans;

    public static QueryResultPlan empty() {
        return EMPTY;
    }

    /**
     * 创建查询结果计划。
     *
     * @param typePlans 根结果类型及其属性处理计划
     */
    public QueryResultPlan(List<TypePlan> typePlans) {
        this.typePlans = typePlans == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(typePlans));
    }

    /**
     * 当前计划是否为空。
     *
     * @return 未解析出任何可处理属性时返回 {@code true}
     */
    public boolean isEmpty() {
        return typePlans.isEmpty();
    }

    /**
     * 返回全部根结果类型计划。
     *
     * @return 根结果类型计划列表
     */
    public List<TypePlan> getTypePlans() {
        return typePlans;
    }

    /**
     * 按运行时结果类型匹配处理计划。
     *
     * @param candidateType 运行时结果类型
     * @return 命中的类型计划，不存在时返回 {@code null}
     */
    public TypePlan findPlan(Class<?> candidateType) {
        if (candidateType == null) {
            return null;
        }
        for (TypePlan typePlan : typePlans) {
            if (typePlan.getResultType().isAssignableFrom(candidateType)) {
                return typePlan;
            }
        }
        return null;
    }

    /**
     * 单个根结果类型的处理计划。
     */
    public static final class TypePlan {

        private final Class<?> resultType;
        private final List<PropertyPlan> propertyPlans;

        /**
         * 创建类型计划。
         *
         * @param resultType 根结果类型
         * @param propertyPlans 属性计划
         */
        public TypePlan(Class<?> resultType, List<PropertyPlan> propertyPlans) {
            this.resultType = resultType;
            this.propertyPlans = propertyPlans == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(propertyPlans));
        }

        /**
         * 返回根结果类型。
         *
         * @return 根结果类型
         */
        public Class<?> getResultType() {
            return resultType;
        }

        /**
         * 返回属性计划列表。
         *
         * @return 属性计划列表
         */
        public List<PropertyPlan> getPropertyPlans() {
            return propertyPlans;
        }
    }

    /**
     * 单个属性路径的处理计划。
     */
    public static final class PropertyPlan {

        private final String propertyPath;
        private final EncryptColumnRule rule;

        /**
         * 创建属性计划。
         *
         * @param propertyPath 根结果对象上的属性路径
         * @param rule 加密字段规则
         */
        public PropertyPlan(String propertyPath, EncryptColumnRule rule) {
            this.propertyPath = propertyPath;
            this.rule = rule;
        }

        /**
         * 返回属性路径。
         *
         * @return 属性路径
         */
        public String getPropertyPath() {
            return propertyPath;
        }

        /**
         * 返回加密字段规则。
         *
         * @return 加密字段规则
         */
        public EncryptColumnRule getRule() {
            return rule;
        }
    }
}
