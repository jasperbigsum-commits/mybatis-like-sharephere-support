package io.github.jasper.mybatis.encrypt.core.rewrite;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.util.JSqlParserSupport;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("rewrite")
class SqlSelectTableContextBuilderTest {

    @Test
    void shouldRegisterDerivedTableRuleAfterDispatchingDerivedSelectRewrite() throws Exception {
        List<ProjectionMode> dispatchedModes = new ArrayList<ProjectionMode>();
        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(sampleProperties(), new AnnotationEncryptMetadataLoader());
        DerivedTableRuleBuilder derivedTableRuleBuilder = new DerivedTableRuleBuilder(metadataRegistry);
        SqlSelectTableContextBuilder builder = new SqlSelectTableContextBuilder(
                metadataRegistry,
                derivedTableRuleBuilder,
                (select, context, projectionMode) -> dispatchedModes.add(projectionMode)
        );
        PlainSelect plainSelect = parsePlainSelect("SELECT d.phone FROM (SELECT phone FROM user_account) d");
        SqlRewriteContext context = new SqlRewriteContext(
                new Configuration(),
                new BoundSql(new Configuration(), plainSelect.toString(), Collections.emptyList(), Collections.emptyMap()),
                new ParameterValueResolver()
        );

        SqlTableContext tableContext = builder.build(plainSelect, context);

        assertEquals(Collections.singletonList(ProjectionMode.DERIVED), dispatchedModes);
        Column column = new Column("phone");
        column.setTable(new Table("d"));
        assertTrue(tableContext.resolve(column).isPresent());
    }

    private PlainSelect parsePlainSelect(String sql) throws Exception {
        Statement statement = JSqlParserSupport.parseStatement(sql);
        Select select = (Select) statement;
        return (PlainSelect) select;
    }

    private DatabaseEncryptionProperties sampleProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties fieldRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        fieldRule.setProperty("phone");
        fieldRule.setColumn("phone");
        fieldRule.setStorageColumn("phone_cipher");
        fieldRule.setAssistedQueryColumn("phone_hash");
        fieldRule.setLikeQueryColumn("phone_like");
        fieldRule.setStorageMode(FieldStorageMode.SAME_TABLE);
        tableRule.getFields().add(fieldRule);
        properties.getTables().add(tableRule);
        properties.setDefaultCipherKey("test-key");
        return properties;
    }
}
