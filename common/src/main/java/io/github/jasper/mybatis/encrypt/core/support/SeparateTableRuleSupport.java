package io.github.jasper.mybatis.encrypt.core.support;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * 独立表规则辅助器。
 *
 * <p>集中维护独立表模式共享的规则约束、hash 引用值计算和标识符规范化逻辑，
 * 避免写前准备与结果回填各自维护一套相同规则。</p>
 */
final class SeparateTableRuleSupport {

    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;

    SeparateTableRuleSupport(AlgorithmRegistry algorithmRegistry,
                             DatabaseEncryptionProperties properties) {
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
    }

    String assignHash(EncryptColumnRule rule, Object plainValue) {
        requireAssistedReferenceRule(rule, "prepare separate-table hash reference");
        return algorithmRegistry.assisted(rule.assistedQueryAlgorithm()).transform(String.valueOf(plainValue));
    }

    void requireAssistedReferenceRule(EncryptColumnRule rule, String action) {
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

    String normalizeReferenceId(Object referenceId) {
        if (referenceId == null) {
            return null;
        }
        String value = String.valueOf(referenceId);
        return StringUtils.isBlank(value) ? null : value;
    }

    String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
