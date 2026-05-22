package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonFieldRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptJsonPathRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable migration plan for one JSON string field.
 */
public final class EntityMigrationJsonFieldPlan {

    private final String property;
    private final String sourceColumn;
    private final String cipherAlgorithm;
    private final String assistedQueryAlgorithm;
    private final List<EntityMigrationJsonPathPlan> pathPlans;

    /**
     * Create one immutable JSON field migration plan.
     *
     * @param property entity property name
     * @param sourceColumn main-table JSON source column
     * @param cipherAlgorithm field default cipher algorithm bean name
     * @param assistedQueryAlgorithm field default hash algorithm bean name
     * @param pathPlans exact JSON path plans
     */
    public EntityMigrationJsonFieldPlan(String property,
                                        String sourceColumn,
                                        String cipherAlgorithm,
                                        String assistedQueryAlgorithm,
                                        List<EntityMigrationJsonPathPlan> pathPlans) {
        this.property = property;
        this.sourceColumn = sourceColumn;
        this.cipherAlgorithm = cipherAlgorithm;
        this.assistedQueryAlgorithm = assistedQueryAlgorithm;
        this.pathPlans = pathPlans == null
                ? Collections.<EntityMigrationJsonPathPlan>emptyList()
                : Collections.unmodifiableList(new ArrayList<EntityMigrationJsonPathPlan>(pathPlans));
    }

    public String getProperty() {
        return property;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    public String getAssistedQueryAlgorithm() {
        return assistedQueryAlgorithm;
    }

    public List<EntityMigrationJsonPathPlan> getPathPlans() {
        return pathPlans;
    }

    /**
     * Build a common-module JSON field rule for runtime helper reuse.
     *
     * @return common JSON field rule
     */
    public EncryptJsonFieldRule toJsonFieldRule() {
        List<EncryptJsonPathRule> pathRules = new ArrayList<EncryptJsonPathRule>();
        for (EntityMigrationJsonPathPlan pathPlan : pathPlans) {
            pathRules.add(new EncryptJsonPathRule(
                    pathPlan.getPath(),
                    pathPlan.getStorageTable(),
                    pathPlan.getStorageIdColumn(),
                    pathPlan.getHashColumn(),
                    pathPlan.getCipherColumn(),
                    pathPlan.getCipherAlgorithm(),
                    pathPlan.getAssistedQueryAlgorithm()
            ));
        }
        return new EncryptJsonFieldRule(
                property,
                null,
                sourceColumn,
                cipherAlgorithm,
                assistedQueryAlgorithm,
                pathRules
        );
    }
}
