package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.config.SqlDialect;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import io.github.jasper.mybatis.encrypt.util.StringUtils;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQL 改写引擎。
 *
 * <p>负责解析 MyBatis SQL，并在执行前改写加密字段、辅助查询字段和 LIKE 辅助字段；
 * 对不支持的范围查询、排序和存在歧义的查询场景会快速失败。</p>
 */
public class SqlRewriteEngine {

    private static final Logger log = LoggerFactory.getLogger(SqlRewriteEngine.class);

    private final EncryptMetadataRegistry metadataRegistry;
    private final DatabaseEncryptionProperties properties;
    private final ParameterValueResolver parameterValueResolver = new ParameterValueResolver();
    private final SqlLogMasker sqlLogMasker = new SqlLogMasker();
    private final EncryptionValueTransformer valueTransformer;
    private final DerivedTableRuleBuilder derivedTableRuleBuilder;
    private final SqlRewriteValidator sqlRewriteValidator = new SqlRewriteValidator();
    private final SqlConditionRewriter sqlConditionRewriter;
    private final SqlWriteExpressionRewriter sqlWriteExpressionRewriter;
    private final SqlSelectProjectionRewriter sqlSelectProjectionRewriter;
    private final SqlInsertRewriter sqlInsertRewriter;
    private final SqlUpdateSetRewriter sqlUpdateSetRewriter;
    private final SqlSelectTableContextBuilder sqlSelectTableContextBuilder;

    /**
     * 创建 SQL 改写引擎。
     *
     * @param metadataRegistry 加密元数据注册中心
     * @param algorithmRegistry 算法注册中心
     * @param properties 插件配置属性
     */
    public SqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                            AlgorithmRegistry algorithmRegistry,
                            DatabaseEncryptionProperties properties) {
        this.metadataRegistry = metadataRegistry;
        this.properties = properties;
        this.valueTransformer = new EncryptionValueTransformer(algorithmRegistry);
        this.derivedTableRuleBuilder = new DerivedTableRuleBuilder(metadataRegistry);
        this.sqlConditionRewriter = new SqlConditionRewriter(
                valueTransformer,
                this::buildColumn,
                this::requireAssistedQueryColumn,
                this::requireLikeQueryColumn,
                this::quote,
                this::rewriteSelect
        );
        this.sqlWriteExpressionRewriter = new SqlWriteExpressionRewriter(valueTransformer, sqlConditionRewriter);
        this.sqlSelectProjectionRewriter = new SqlSelectProjectionRewriter(
                this::resolveEncryptedColumn,
                this::buildColumn,
                this::requireAssistedQueryColumn
        );
        this.sqlInsertRewriter = new SqlInsertRewriter(sqlWriteExpressionRewriter, valueTransformer, this::quote);
        this.sqlUpdateSetRewriter = new SqlUpdateSetRewriter(
                sqlWriteExpressionRewriter,
                valueTransformer,
                this::buildColumn
        );
        this.sqlSelectTableContextBuilder = new SqlSelectTableContextBuilder(
                metadataRegistry,
                derivedTableRuleBuilder,
                this::rewriteSelect
        );
    }

    /**
     * 改写一次 MyBatis 执行请求。
     *
     * @param mappedStatement 当前 mapped statement
     * @param boundSql 原始 SQL 与参数上下文
     * @return 改写结果；当没有命中加密规则时返回未变更结果
     */
    public RewriteResult rewrite(MappedStatement mappedStatement, BoundSql boundSql) {
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject(), boundSql.getSql());
        try {
            Statement statement = JSqlParserSupport.parseStatement(boundSql.getSql());
            SqlRewriteContext context = new SqlRewriteContext(
                    mappedStatement.getConfiguration(), boundSql, parameterValueResolver);
            if (statement instanceof Insert) {
                rewriteInsert((Insert) statement, context);
            } else if (statement instanceof Update) {
                rewriteUpdate((Update) statement, context);
            } else if (statement instanceof Delete) {
                rewriteDelete((Delete) statement, context);
            } else if (statement instanceof Select) {
                rewriteSelect((Select) statement, context);
            }
            if (!context.changed()) {
                return RewriteResult.unchanged();
            }
            String rewrittenSql = statement.toString();
            String maskedSql = sqlLogMasker.mask(rewrittenSql, context.maskedParameters());
            if (properties.isLogMaskedSql() && log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite applied for statement [{}]: {}",
                        mappedStatement.getId(), maskedSql);
            }
            return new RewriteResult(true, rewrittenSql, context.parameterMappings(), context.maskedParameters(), maskedSql);
        } catch (UnsupportedEncryptedOperationException ex) {
            if (log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite rejected for statement [{}]: {}",
                        mappedStatement.getId(), ex.getMessage());
            }
            throw ex;
        } catch (Exception ex) {
            if (properties.isFailOnMissingRule()) {
                throw new EncryptionConfigurationException(EncryptionErrorCode.SQL_REWRITE_FAILED,
                        "Failed to rewrite encrypted SQL: " + boundSql.getSql(), ex);
            }
            if (log.isDebugEnabled()) {
                log.debug("Encrypted SQL rewrite skipped for statement [{}] because failOnMissingRule=false: {}",
                        mappedStatement.getId(), ex.getMessage(), ex);
            }
            return RewriteResult.unchanged();
        }
    }

    /**
     * 改写 `INSERT` 语句中的逻辑加密列。
     *
     * <p>同表模式下，逻辑列会被替换为密文列，并按规则追加辅助查询列、LIKE 查询列。
     * 独立表模式下，主表仍保留逻辑列本身，但参数值会被替换成写前准备好的外部引用 id。</p>
     */
    private void rewriteInsert(Insert insert, SqlRewriteContext context) {
        EncryptTableRule tableRule = metadataRegistry.findByTable(insert.getTable().getName()).orElse(null);
        if (tableRule == null) {
            return;
        }
        if (sqlInsertRewriter.rewrite(insert, tableRule, context)) {
            context.markChanged();
        }
    }

    /**
     * 改写 `UPDATE` 语句。
     *
     * <p>`SET` 子句负责把业务明文写成存储态值，`WHERE` 子句则统一走查询态列改写。
     * 两段逻辑故意拆开，便于排查“写入值不对”和“查询条件不命中”这两类问题。</p>
     */
    private void rewriteUpdate(Update update, SqlRewriteContext context) {
        SqlTableContext tableContext = new SqlTableContext();
        sqlSelectTableContextBuilder.registerTable(tableContext, update.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        if (sqlUpdateSetRewriter.rewrite(update, tableContext, context)) {
            context.markChanged();
        }
        // 只有 WHERE 子句会被改写到查询辅助列，SET 子句仍然写入主密文列。
        update.setWhere(sqlConditionRewriter.rewrite(update.getWhere(), tableContext, context));
    }

    /**
     * 改写 `DELETE` 的条件部分。
     *
     * <p>删除操作不会改写列清单，只允许在条件里使用受支持的加密字段比较语义。
     * 遇到不安全或不可靠的条件时，会在条件递归阶段直接失败。</p>
     */
    private void rewriteDelete(Delete delete, SqlRewriteContext context) {
        SqlTableContext tableContext = new SqlTableContext();
        sqlSelectTableContextBuilder.registerTable(tableContext, delete.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        delete.setWhere(sqlConditionRewriter.rewrite(delete.getWhere(), tableContext, context));
    }

    private void rewriteSelect(Select select, SqlRewriteContext context) {
        rewriteSelect(select, context, ProjectionMode.NORMAL);
    }

    /**
     * 改写 `SELECT` 语句。
     *
     * <p>这里同时处理三件事：注册当前查询块可见的表与别名、递归改写条件和子查询、
     * 以及按 `ProjectionMode` 决定投影列展开方式。复杂 SQL 排查时，先确认当前查询块
     * 落在哪个投影模式，通常能更快定位问题是在投影阶段还是条件阶段。</p>
     */
    private void rewriteSelect(Select select, SqlRewriteContext context, ProjectionMode projectionMode) {
        if (select instanceof SetOperationList) {
            SetOperationList setOperationList = (SetOperationList) select;
            for (Select child : setOperationList.getSelects()) {
                rewriteSelect(child, context, projectionMode);
            }
            return;
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                rewriteSelect(parenthesedSelect.getSelect(), context, projectionMode);
            }
            return;
        }
        if (!(select instanceof PlainSelect)) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.UNSUPPORTED_ENCRYPTED_SELECT,
                    "Only plain select and set-operation select are supported for encrypted SQL rewrite.");
        }
        PlainSelect plainSelect = (PlainSelect) select;
        SqlTableContext tableContext = sqlSelectTableContextBuilder.build(plainSelect, context);
        // SQL 参数绑定顺序遵循语句中的占位符出现顺序；SELECT 列表中的参数需要先消费。
        sqlConditionRewriter.consumeSelectItemParameters(plainSelect.getSelectItems(), context);
        plainSelect.setWhere(sqlConditionRewriter.rewrite(plainSelect.getWhere(), tableContext, context));
        plainSelect.setHaving(sqlConditionRewriter.rewrite(plainSelect.getHaving(), tableContext, context));
        plainSelect.setQualify(sqlConditionRewriter.rewrite(plainSelect.getQualify(), tableContext, context));
        if (tableContext.isEmpty()) {
            return;
        }
        sqlRewriteValidator.validateSelect(plainSelect, tableContext);
        if (sqlSelectProjectionRewriter.rewrite(plainSelect, tableContext, projectionMode)) {
            context.markChanged();
        }
    }

    private String requireAssistedQueryColumn(EncryptColumnRule rule, String scenario) {
        if (!rule.hasAssistedQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_ASSISTED_QUERY_COLUMN,
                    "Encrypted " + scenario + " requires assistedQueryColumn. " + describeRule(rule));
        }
        return rule.assistedQueryColumn();
    }

    private String requireLikeQueryColumn(EncryptColumnRule rule, String scenario) {
        if (!rule.hasLikeQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(EncryptionErrorCode.MISSING_LIKE_QUERY_COLUMN,
                    "Encrypted " + scenario + " requires likeQueryColumn. " + describeRule(rule));
        }
        return rule.likeQueryColumn();
    }

    private String describeRule(EncryptColumnRule rule) {
        String table = StringUtils.isNotBlank(rule.table()) ? rule.table() : "<entity-default-table>";
        if (rule.isStoredInSeparateTable()) {
            return "property=" + rule.property()
                    + ", table=" + table
                    + ", column=" + rule.column()
                    + ", storageTable=" + rule.storageTable();
        }
        return "property=" + rule.property()
                + ", table=" + table
                + ", column=" + rule.column();
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, SqlTableContext tableContext) {
        if (!(expression instanceof Column)) {
            return null;
        }
        Column column = (Column) expression;
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(quote(targetColumn));
        if (source.getTable() != null && source.getTable().getName() != null) {
            // 这里只保留表引用名（表名或别名），避免生成类似 "t AS t.`col`" 的非法 SQL。
            column.setTable(new Table(source.getTable().getName()));
        }
        return column;
    }

    private String quote(String identifier) {
        SqlDialect dialect = properties.getSqlDialect();
        return dialect == null ? identifier : dialect.quote(identifier);
    }
}
