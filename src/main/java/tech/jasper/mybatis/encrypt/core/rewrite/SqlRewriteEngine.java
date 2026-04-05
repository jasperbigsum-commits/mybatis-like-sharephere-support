package tech.jasper.mybatis.encrypt.core.rewrite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import tech.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import tech.jasper.mybatis.encrypt.algorithm.AssistedQueryAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.CipherAlgorithm;
import tech.jasper.mybatis.encrypt.algorithm.LikeQueryAlgorithm;
import tech.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import tech.jasper.mybatis.encrypt.config.SqlDialect;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import tech.jasper.mybatis.encrypt.core.metadata.EncryptTableRule;
import tech.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import tech.jasper.mybatis.encrypt.exception.UnsupportedEncryptedOperationException;
import tech.jasper.mybatis.encrypt.util.NameUtils;

/**
 * SQL 闂傚倷娴囬妴鈧柛瀣崌閺岋綁鎮㈤崨濠勫嚒闂佸搫鎳庨幗婊堝焵椤掍緡鍟忛柛鐘愁殔鐓ゆ慨妞诲亾妤犵偞鐗犻獮鏍ㄦ媴閼? *
 * <p>闂備浇宕垫慨鏉懨洪姀銈呯？鐎规洖娲﹂崑鏍ㄣ亜閹板墎鐣遍柛娆忥攻閵囧嫰寮介妸褉妲堢紓浣藉蔼椤曆囨箒闂佺粯蓱瑜板啴銆傞搹顐ょ闁割偆鍠庨悘瀵糕偓娈垮枤閺咁偆鍒掑▎鎴炲磯闁靛鍎哄ú顓㈡⒒閸屾瑧璐伴柛瀣噹鍗遍柟鐑樻⒐椤愯姤銇勯幇鍫曟闁哄拋鍓涢埀顒€鍘滈崑鎾绘煕閹板吀鎲炬俊鎻掔秺閺岋綁鎮╅崘鎻捫佺紓鍌氱М閸嬫捇鎮跺顓犳噰闁?MyBatis 闂傚倷绀侀幉锟犮€冮崨鏉戠柈闁秆勵殕閸庡秹鏌曡箛瀣偓鏇犵不閵壯勫枑闁硅揪璐熼埀顑跨窔楠炲鏁傞挊澶婂?SQL闂傚倷鐒︾€笛呯矙閹达附鍤愭い鏍仦閸ゆ劙鏌ｉ弮鍌氬付闁哄嫨鍎靛鍫曟倷閺夋埈妫嗛梺鐓庣秺缁犳牠骞冨畡鎵虫瀻闊洦鎼╂禒濂告⒑閸撹尙鍘涢柛銊ョ埣閻涱喗鎯旈姀銏㈢槇婵炶揪绲介幗婊兾ｉ敐澶嬧拺闁告稑锕ラ埛鎺戔攽椤旇姤灏﹂柟顖欑窔楠炲鏁冮埀顒傜矆閸℃ü绻嗛柣鎰皺濮ｇ偤鏌? * 闂備礁鎼ˇ顖炴偋閸℃稑绠犻幖娣灪閸欏繑銇勮箛鎾跺闁藉啰鍠栭弻鏇熷緞濡厧甯ラ梺鎼炲€曠€氫即寮诲☉銏犵闁瑰灝鍟悾鍫曟⒑?LIKE 闂傚倷绀侀幖顐ゆ偖椤愶箑纾块柟缁㈠櫘閺佸淇婇妶鍛櫣缂佲偓閸℃ü绻嗛柕鍫濆閸斿秹鏌￠崱鈺佸籍闁哄本鐩獮鍥敇閻樺啿娅戦梻浣虹《閺呮盯鎳熼鐑囩稏闁靛繈鍊栭弲鏌ユ煕閵夘垳宀涢柛瀣尭椤粓鍩€椤掑嫬绠氶柛鎰靛枛缁€瀣亜韫囨挻顥犵€涙繄绱撻崒姘偓鎼佸窗濮樿泛绐楅柟鎹愵嚙閻掑灚銇勯幒鍡椾壕闂佺锕ら幗婊呮閻愮鍋撻敐搴℃灈缂佲偓閸懇鍋撻獮鍨姎闁硅櫕鍔欏鑸电鐎ｎ偆鍘藉┑鐘诧工閹冲酣銆傞崗闂寸箚闁告瑥顦悘鈺傜箾閹寸姵鏆い銏＄懇濮婅崵鈧數顭堟晶楣冩⒒娴ｅ憡鍟炵痪鏉跨Ч瀹曞綊鎮㈤悡搴ｅ姦濡炪倖甯婇懗鍫曟儗閸℃瑧纾肩紓浣股戦惃鎴︽煃瑜滈崜娑㈠箠閹剧粯鍋嬪┑鐘叉祩閺佸棙绻濇繝鍌涘櫤闁稿繑绮嶉妵鍕棘閸喖杈呴梺绋款儐閹告悂鍩為幋锕€绠婚柛鎾茶兌瀹?
 * 闂備浇顕х花鑲╁緤婵犳熬缍栧璺好″☉銏犖ч柛娑变簼閻忓啴姊洪崫鍕婵ǜ鍔庣划缁樼鐎ｎ偄浠梺鎼炲劵闂勫嫮绮婇悧鍫涗簻闁哄洦锚閻忣亞绱掔€ｎ亷鏀荤€垫澘瀚埀顒婄秮濞佳勪繆瑜忕槐鎺旂磼閵忕姴瀛ｉ梺鍝ュУ閼归箖鍩㈤幘璇茬闁挎洍鍋撶紒鐘冲▕閺岀喖鎮滃Ο铏逛患缂佺偓婢樼粔褰掑蓟閺囥垹骞㈤柡鍥╁枔閵堜即鏌ｆ惔锛勪粵闁绘濮撮悾鐑藉礈娴ｆ彃浜炬繛鎴炵懐閻掍粙鏌涘▎灞戒壕闂備浇宕垫慨鏉懨洪姀銈呭瀭鐟滅増甯掔粻顖炴煟濡も偓閻楀棛娆㈤悙纰樺亾楠炲灝鍔氭俊顐ｇ懅瀵板﹥绂掔€ｎ偄鈧灚绻涢幋鐐垫噧濠殿喖鐗撻弻锝夊箼閸曨厾锛熼梺褰掝棑婵炩偓濠殿喒鍋撻梺闈涢獜缁辨洟宕欐禒瀣拺閻熸瑥瀚懜顏堟煕鎼淬劋鎲惧┑锛勬暬楠炲鎮╅崘宸紩闂備線娼ч¨鈧紒鍙夋そ瀹?/p>
 */
public class SqlRewriteEngine {

    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final ParameterValueResolver parameterValueResolver = new ParameterValueResolver();
    private final SqlLogMasker sqlLogMasker = new SqlLogMasker();

    public SqlRewriteEngine(EncryptMetadataRegistry metadataRegistry,
                            AlgorithmRegistry algorithmRegistry,
                            DatabaseEncryptionProperties properties) {
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties;
    }

    /**
     * 闂傚倷娴囬妴鈧柛瀣崌閺岋綁鎮㈤崨濠勫嚒闂佸搫鎳愰…鍫ュ煡婢舵劕绠绘い鏍ㄧ煯婢规洘绻?MyBatis 闂傚倷绀佸﹢閬嶆偡閹惰棄骞㈤柍鍝勫€归弶鎼佹⒑閼姐倕孝婵炲樊鍙冨銊╂焼瀹ュ棗鍓虫繛鏉戝悑濞兼瑨绻?     *
     * <p>婵犵數濮烽。浠嬪焵椤掆偓閸熷潡鍩€椤掆偓缂嶅﹪骞?SQL 婵犵數鍋為崹鍫曞箰閸濄儳鐭撻柣銏㈡暩閻挾鎲搁悧鍫濈瑨闁活厽顨呴埞鎴︽偐閹绘巻鍋撻幖浣稿惞闁圭儤鍩堥悢鍡涙煕閿旇骞栨い锝呭悑娣囧﹪宕ｆ径濠勪紝闂佽鍨伴崯鏉戠暦閻旂⒈鏁冮柕蹇婃櫆婵傛牠姊虹拠鏌ュ弰婵炰匠鍏炬稑顭ㄩ崼婢儵鏌涢幇闈涙灈缂佺媴缍侀弻鈥崇暤椤旂厧鏋ゆ繛濂镐憾閺岋絾鎯旈妸锔介敪濠碘槅鍋呴〃濠囧箖妤︽妲婚梺宕囩帛閹搁箖宕版繝鍐ㄧ窞鐎光偓閳ь剟藝椤曗偓閺岋綀绠涢幘鍓侇唶濠碘槅鍋呯划搴ㄥ箲閵忋倕绠ｉ柨鏇楀亾闁?unchanged 缂傚倸鍊搁崐鐑芥倿閿曞倸绠板┑鐘崇閸婅泛顭块懜闈涘鏉?/p>
     *
     * @param mappedStatement 闂佽崵鍠愮划搴㈡櫠濡ゅ懎绠伴柛娑橈攻濞呯娀鏌ｅΟ娆惧殭缂佺姰鍎抽幉鎼佸箣閿旇　鍋撴笟鈧獮瀣晜閽樺澹勯梻浣告啞濞诧箓宕滃鐐解偓鏍⒑鐠囪尙绠扮紒缁樺姉閳ь剚鍑归崹鍫曘€佸璺侯潊闁靛牆鎳愰悡?     * @param boundSql MyBatis 闂傚倷鐒﹂惇褰掑垂婵犳艾绐楅柟鐗堟緲閸ㄥ倹鎱ㄥΟ鍨厫闁?SQL 婵犵數鍋為崹鍫曞箰閸涘娈界紒瀣儥閸ゆ洘銇勯幒鎴濐仼闁哄绀佽灋濡娴囬崗宀勬煟閻旂濮囬柍钘夘樀楠炴﹢骞囨担绋垮闂?
     * @return 闂傚倷娴囬妴鈧柛瀣崌閺岋綁鎮㈤崨濠勫嚒闂佸搫鎳庨幗婊呮閹惧瓨濯撮悷娆忓闂夊秹姊?
     */
    public RewriteResult rewrite(MappedStatement mappedStatement, BoundSql boundSql) {
        metadataRegistry.warmUp(mappedStatement, boundSql.getParameterObject());
        try {
            Statement statement = CCJSqlParserUtil.parse(boundSql.getSql());
            RewriteContext context = new RewriteContext(mappedStatement.getConfiguration(), boundSql);
            if (statement instanceof Insert insert) {
                rewriteInsert(insert, context);
            } else if (statement instanceof Update update) {
                rewriteUpdate(update, context);
            } else if (statement instanceof Delete delete) {
                rewriteDelete(delete, context);
            } else if (statement instanceof Select select) {
                rewriteSelect(select, context);
            }
            if (!context.changed) {
                return RewriteResult.unchanged();
            }
            String rewrittenSql = statement.toString();
            return new RewriteResult(true, rewrittenSql, context.parameterMappings, context.maskedParameters,
                    sqlLogMasker.mask(rewrittenSql, context.maskedParameters));
        } catch (UnsupportedEncryptedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            if (properties.isFailOnMissingRule()) {
                throw new EncryptionConfigurationException("Failed to rewrite encrypted SQL: " + boundSql.getSql(), ex);
            }
            return RewriteResult.unchanged();
        }
    }

    private void rewriteInsert(Insert insert, RewriteContext context) {
        EncryptTableRule tableRule = metadataRegistry.findByTable(insert.getTable().getName()).orElse(null);
        if (tableRule == null) {
            return;
        }
        Values values = insert.getValues();
        if (values == null || !(values.getExpressions() instanceof ExpressionList)) {
            throw new UnsupportedEncryptedOperationException("Only VALUES inserts are supported for encrypted tables.");
        }
        ExpressionList expressionList = (ExpressionList) values.getExpressions();
        List<Column> originalColumns = new ArrayList<>(insert.getColumns());
        List<Expression> originalExpressions = new ArrayList<>(expressionList.getExpressions());
        List<Column> rewrittenColumns = new ArrayList<>();
        List<Expression> rewrittenExpressions = new ArrayList<>();
        for (int index = 0; index < originalColumns.size(); index++) {
            Column column = originalColumns.get(index);
            Expression expression = originalExpressions.get(index);
            EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
            if (rule == null) {
                rewrittenColumns.add(column);
                rewrittenExpressions.add(passthroughWriteExpression(expression, context));
                continue;
            }
            if (rule.isStoredInSeparateTable()) {
                consumeExpression(expression, context);
                context.changed = true;
                continue;
            }
            rewrittenColumns.add(column);
            WriteValue writeValue = rewriteEncryptedWriteExpression(expression, rule, context);
            rewrittenExpressions.add(writeValue.expression());
            if (rule.hasAssistedQueryColumn()) {
                rewrittenColumns.add(new Column(quote(rule.assistedQueryColumn())));
                rewrittenExpressions.add(buildShadowExpression(writeValue, transformAssisted(rule, writeValue.plainValue()),
                        MaskingMode.HASH, context));
            }
            if (rule.hasLikeQueryColumn()) {
                rewrittenColumns.add(new Column(quote(rule.likeQueryColumn())));
                rewrittenExpressions.add(buildShadowExpression(writeValue, transformLike(rule, writeValue.plainValue()),
                        MaskingMode.MASKED, context));
            }
        }
        insert.setColumns(new ExpressionList<>(rewrittenColumns));
        values.setExpressions(new ParenthesedExpressionList<>(rewrittenExpressions));
        context.changed = true;
    }

    private void rewriteUpdate(Update update, RewriteContext context) {
        TableContext tableContext = new TableContext();
        registerTable(tableContext, update.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        for (UpdateSet updateSet : update.getUpdateSets()) {
            List<Column> originalColumns = new ArrayList<>(updateSet.getColumns());
            ExpressionList<Expression> updateValues = (ExpressionList<Expression>) updateSet.getValues();
            List<Expression> originalExpressions = new ArrayList<>(updateValues.getExpressions());
            List<Column> rewrittenColumns = new ArrayList<>();
            List<Expression> rewrittenExpressions = new ArrayList<>();
            for (int index = 0; index < originalColumns.size(); index++) {
                Column column = originalColumns.get(index);
                Expression expression = originalExpressions.get(index);
                EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
                if (rule == null) {
                    rewrittenColumns.add(column);
                    rewrittenExpressions.add(passthroughWriteExpression(expression, context));
                    continue;
                }
                if (rule.isStoredInSeparateTable()) {
                    consumeExpression(expression, context);
                    context.changed = true;
                    continue;
                }
                rewrittenColumns.add(column);
                WriteValue writeValue = rewriteEncryptedWriteExpression(expression, rule, context);
                rewrittenExpressions.add(writeValue.expression());
                if (rule.hasAssistedQueryColumn()) {
                    rewrittenColumns.add(buildColumn(column, rule.assistedQueryColumn()));
                    rewrittenExpressions.add(buildShadowExpression(writeValue, transformAssisted(rule, writeValue.plainValue()),
                            MaskingMode.HASH, context));
                }
                if (rule.hasLikeQueryColumn()) {
                    rewrittenColumns.add(buildColumn(column, rule.likeQueryColumn()));
                    rewrittenExpressions.add(buildShadowExpression(writeValue, transformLike(rule, writeValue.plainValue()),
                            MaskingMode.MASKED, context));
                }
            }
            updateSet.getColumns().clear();
            updateSet.getColumns().addAll(rewrittenColumns);
            updateValues.getExpressions().clear();
            updateValues.getExpressions().addAll(rewrittenExpressions);
        }
        // Only the WHERE clause is redirected to query columns; the SET clause still writes ciphertext to the main column.
        update.setWhere(rewriteCondition(update.getWhere(), tableContext, context));
    }

    private void rewriteDelete(Delete delete, RewriteContext context) {
        TableContext tableContext = new TableContext();
        registerTable(tableContext, delete.getTable());
        if (tableContext.isEmpty()) {
            return;
        }
        delete.setWhere(rewriteCondition(delete.getWhere(), tableContext, context));
    }

    private void rewriteSelect(Select select, RewriteContext context) {
        if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            throw new UnsupportedEncryptedOperationException("Only plain select is supported for encrypted SQL rewrite.");
        }
        TableContext tableContext = new TableContext();
        registerFromItem(tableContext, plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerFromItem(tableContext, join.getRightItem());
            }
        }
        if (tableContext.isEmpty()) {
            return;
        }
        plainSelect.setWhere(rewriteCondition(plainSelect.getWhere(), tableContext, context));
        stripSeparateTableSelectItems(plainSelect, tableContext);
        // 闂傚倷绀佸﹢閬嶅磿閵堝洦鏆滈柟鐑樻婵櫕銇勯幘璺盒ｉ悗姘閵囧嫰骞樼€靛摜鐣奸梺姹囧€曢幊姗€寮婚敓鐘茬妞ゅ繐瀚呴敐鍥╂／妞ゆ挾鍋熼崺锝夋煙椤旇崵鐭欑€规洩绻濋幃娆戔偓娑櫭崜楣冩⒑濮瑰洤鐒洪柛銊ゅ嵆閹崇喖顢涢悙鎻掔€銈呯箰閻楀棛绮婚敐澶嬬厸鐎广儱娴烽崢鎺懨归敐鍛儓閻庡灚褰冮埞鎴︽偐閹绘巻鍋撻幖浣瑰€舵繛鍡樻尰閻撴洘绻涢崱妯忣亪鎮橀敂鐣岀缁炬澘宕晶瀵糕偓娈垮枛閻°劌顕ラ崟顓犵煓闁圭瀛╁▓銊╂⒒娴ｇ懓顕滅紒瀣灴閹崇喖顢涘В顓炴惈椤劑宕橀悙顒併€冮梻渚€娼ч悧鍡涙偋韫囨洜涓嶇憸鐗堝笚閳锋垿鏌熺紒妯虹闁诲繗椴哥换娑㈠箵閹烘挸浠村Δ鐘靛仦鐢剝淇婇悜钘壩ㄩ柨婵嗗濠⑩偓闂備浇宕垫慨鐢稿礉閹达箑纾块柟缁㈠枛閻掑灚銇勯幒鍡椾壕婵犻潧鍊瑰鑽ゅ垝椤撶喎绶為柟閭﹀墮瀵嘲顪冮妶鍡欏闁活収鍠氬Σ鎰板Ω閳哄倻鍘遍柣搴秵閸犳寮柆宥嗙厸闁稿本鐟ч崝宥嗙箾閻撳寒鐓肩€规洖鐖兼俊鐑藉閻樺崬顥?        validateOrderBy(plainSelect.getOrderByElements(), tableContext);
    }

    private Expression rewriteCondition(Expression expression, TableContext tableContext, RewriteContext context) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof Parenthesis parenthesis) {
            parenthesis.setExpression(rewriteCondition(parenthesis.getExpression(), tableContext, context));
            return parenthesis;
        }
        if (expression instanceof AndExpression andExpression) {
            andExpression.setLeftExpression(rewriteCondition(andExpression.getLeftExpression(), tableContext, context));
            andExpression.setRightExpression(rewriteCondition(andExpression.getRightExpression(), tableContext, context));
            return andExpression;
        }
        if (expression instanceof OrExpression orExpression) {
            orExpression.setLeftExpression(rewriteCondition(orExpression.getLeftExpression(), tableContext, context));
            orExpression.setRightExpression(rewriteCondition(orExpression.getRightExpression(), tableContext, context));
            return orExpression;
        }
        if (expression instanceof EqualsTo equalsTo) {
            return rewriteEquality(equalsTo, tableContext, context);
        }
        if (expression instanceof NotEqualsTo notEqualsTo) {
            return rewriteEquality(notEqualsTo, tableContext, context);
        }
        if (expression instanceof LikeExpression likeExpression) {
            return rewriteLikeCondition(likeExpression, tableContext, context);
        }
        if (expression instanceof InExpression inExpression) {
            return rewriteInCondition(inExpression, tableContext, context);
        }
        if (expression instanceof Between between) {
            validateNonRangeEncryptedColumn(between.getLeftExpression(), tableContext,
                    "BETWEEN is not supported on encrypted fields.");
            consumeExpression(between.getBetweenExpressionStart(), context);
            consumeExpression(between.getBetweenExpressionEnd(), context);
            return between;
        }
        if (expression instanceof GreaterThan || expression instanceof GreaterThanEquals
                || expression instanceof MinorThan || expression instanceof MinorThanEquals) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            validateNonRangeEncryptedColumn(binaryExpression.getLeftExpression(), tableContext,
                    "Range comparison is not supported on encrypted fields.");
            consumeExpression(binaryExpression.getRightExpression(), context);
            return expression;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            binaryExpression.setLeftExpression(rewriteCondition(binaryExpression.getLeftExpression(), tableContext, context));
            binaryExpression.setRightExpression(rewriteCondition(binaryExpression.getRightExpression(), tableContext, context));
            return binaryExpression;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Object item : function.getParameters().getExpressions()) {
                rewriteCondition((Expression) item, tableContext, context);
            }
            return expression;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
        }
        return expression;
    }

    private Expression rewriteEquality(BinaryExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveComparison(expression, tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewriteCondition(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewriteCondition(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTableCondition(resolution, expression.getRightExpression(), context,
                    rule.assistedQueryColumn(), true);
        }
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.column();
        if (resolution.leftColumn()) {
            expression.setLeftExpression(buildColumn(resolution.column(), targetColumn));
            rewriteOperand(expression.getRightExpression(), context,
                    rule.hasAssistedQueryColumn()
                            ? transformAssisted(rule, readOperandValue(expression.getRightExpression(), context))
                            : transformCipher(rule, readOperandValue(expression.getRightExpression(), context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        } else {
            expression.setRightExpression(buildColumn(resolution.column(), targetColumn));
            rewriteOperand(expression.getLeftExpression(), context,
                    rule.hasAssistedQueryColumn()
                            ? transformAssisted(rule, readOperandValue(expression.getLeftExpression(), context))
                            : transformCipher(rule, readOperandValue(expression.getLeftExpression(), context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        }
        context.changed = true;
        return expression;
    }

    private Expression rewriteLikeCondition(LikeExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            expression.setLeftExpression(rewriteCondition(expression.getLeftExpression(), tableContext, context));
            expression.setRightExpression(rewriteCondition(expression.getRightExpression(), tableContext, context));
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            return rewriteSeparateTableCondition(resolution, expression.getRightExpression(), context,
                    rule.likeQueryColumn(), false);
        }
        if (!rule.hasLikeQueryColumn()) {
            throw new UnsupportedEncryptedOperationException(
                    "LIKE query requires likeQueryColumn for encrypted field: " + rule.property());
        }
        expression.setLeftExpression(buildColumn(resolution.column(), rule.likeQueryColumn()));
        rewriteOperand(expression.getRightExpression(), context,
                transformLike(rule, readOperandValue(expression.getRightExpression(), context)), MaskingMode.MASKED);
        context.changed = true;
        return expression;
    }

    private Expression rewriteInCondition(InExpression expression, TableContext tableContext, RewriteContext context) {
        ColumnResolution resolution = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (resolution == null) {
            consumeItemsList(expression.getRightExpression(), context);
            return expression;
        }
        EncryptColumnRule rule = resolution.rule();
        if (rule.isStoredInSeparateTable()) {
            throw new UnsupportedEncryptedOperationException("IN query is not supported for separate-table encrypted field: "
                    + rule.property());
        }
        // IN query uses the same target-column selection strategy as equality queries.
        String targetColumn = rule.hasAssistedQueryColumn() ? rule.assistedQueryColumn() : rule.column();
        if (!(expression.getRightExpression() instanceof ExpressionList expressionList)) {
            throw new UnsupportedEncryptedOperationException("Sub query IN is not supported on encrypted fields.");
        }
        for (Object item : expressionList.getExpressions()) {
            Expression current = (Expression) item;
            rewriteOperand(current, context,
                    rule.hasAssistedQueryColumn() ? transformAssisted(rule, readOperandValue(current, context))
                            : transformCipher(rule, readOperandValue(current, context)),
                    rule.hasAssistedQueryColumn() ? MaskingMode.HASH : MaskingMode.MASKED);
        }
        context.changed = true;
        return expression;
    }

    private void rewriteOperand(Expression expression, RewriteContext context, String transformedValue, MaskingMode maskingMode) {
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(transformedValue, maskingMode);
            return;
        }
        if (expression instanceof StringValue stringValue) {
            stringValue.setValue(transformedValue);
            return;
        }
        if (expression instanceof NullValue) {
            return;
        }
        throw new UnsupportedEncryptedOperationException("Encrypted query condition must use prepared parameter or string literal.");
    }

    private Expression rewriteSeparateTableCondition(ColumnResolution resolution,
                                                     Expression operand,
                                                     RewriteContext context,
                                                     String targetColumn,
                                                     boolean assisted) {
        EncryptColumnRule rule = resolution.rule();
        if (targetColumn == null || targetColumn.isBlank()) {
            throw new UnsupportedEncryptedOperationException(
                    "Separate-table encrypted field requires query column: " + rule.property());
        }
        String transformed = assisted
                ? transformAssisted(rule, readOperandValue(operand, context))
                : transformLike(rule, readOperandValue(operand, context));
        replaceOperandBinding(operand, context, transformed, assisted ? MaskingMode.HASH : MaskingMode.MASKED);
        return buildExistsSubQuery(resolution.column(), rule, targetColumn, buildQueryValueExpression(operand, transformed), assisted);
    }

    private void replaceOperandBinding(Expression operand, RewriteContext context, String transformed, MaskingMode maskingMode) {
        if (operand instanceof JdbcParameter) {
            context.replaceLastConsumed(transformed, maskingMode);
            return;
        }
        if (operand instanceof StringValue || operand instanceof LongValue || operand instanceof NullValue) {
            return;
        }
        throw new UnsupportedEncryptedOperationException("Separate-table encrypted query must use prepared parameter or literal.");
    }

    private WriteValue rewriteEncryptedWriteExpression(Expression expression,
                                                       EncryptColumnRule rule,
                                                       RewriteContext context) {
        Object plainValue = readOperandValue(expression, context);
        String cipherValue = transformCipher(rule, plainValue);
        if (expression instanceof JdbcParameter) {
            context.replaceLastConsumed(cipherValue, MaskingMode.MASKED);
            return new WriteValue(expression, plainValue, true);
        }
        if (expression instanceof StringValue stringValue) {
            stringValue.setValue(cipherValue);
            return new WriteValue(stringValue, plainValue, false);
        }
        if (expression instanceof LongValue) {
            return new WriteValue(new StringValue(cipherValue), plainValue, false);
        }
        if (expression instanceof NullValue) {
            return new WriteValue(expression, null, false);
        }
        throw new UnsupportedEncryptedOperationException("Encrypted write only supports prepared parameters or string literals.");
    }

    private Expression passthroughWriteExpression(Expression expression, RewriteContext context) {
        consumeExpression(expression, context);
        return expression;
    }

    private Expression buildShadowExpression(WriteValue writeValue, String value, MaskingMode maskingMode, RewriteContext context) {
        if (value == null) {
            return new NullValue();
        }
        if (writeValue.parameterized()) {
            return context.insertSynthetic(value, maskingMode);
        }
        return new StringValue(value);
    }

    private Object readOperandValue(Expression expression, RewriteContext context) {
        if (expression instanceof JdbcParameter) {
            int parameterIndex = context.consumeOriginal();
            return context.originalValue(parameterIndex, parameterValueResolver);
        }
        if (expression instanceof StringValue stringValue) {
            return stringValue.getValue();
        }
        if (expression instanceof LongValue longValue) {
            return longValue.getStringValue();
        }
        if (expression instanceof NullValue) {
            return null;
        }
        return null;
    }

    private void consumeExpression(Expression expression, RewriteContext context) {
        if (expression == null) {
            return;
        }
        if (expression instanceof JdbcParameter) {
            context.consumeOriginal();
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            consumeExpression(parenthesis.getExpression(), context);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            consumeExpression(binaryExpression.getLeftExpression(), context);
            consumeExpression(binaryExpression.getRightExpression(), context);
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Object item : function.getParameters().getExpressions()) {
                consumeExpression((Expression) item, context);
            }
        }
    }

    private void consumeItemsList(Object itemsList, RewriteContext context) {
        if (itemsList instanceof ExpressionList expressionList) {
            for (Object item : expressionList.getExpressions()) {
                consumeExpression((Expression) item, context);
            }
        }
    }

    private Expression buildQueryValueExpression(Expression operand, String transformed) {
        if (operand instanceof JdbcParameter) {
            return new JdbcParameter();
        }
        if (operand instanceof StringValue) {
            return transformed == null ? new NullValue() : new StringValue(transformed);
        }
        if (operand instanceof LongValue) {
            return transformed == null ? new NullValue() : new StringValue(transformed);
        }
        if (operand instanceof NullValue) {
            return new NullValue();
        }
        throw new UnsupportedEncryptedOperationException("Separate-table encrypted query must use prepared parameter or literal.");
    }

    private void stripSeparateTableSelectItems(PlainSelect plainSelect, TableContext tableContext) {
        if (plainSelect.getSelectItems() == null) {
            return;
        }
        List<SelectItem<?>> retained = new ArrayList<>();
        for (SelectItem<?> item : plainSelect.getSelectItems()) {
            boolean removable = isSeparateTableSelectItem(item, tableContext);
            if (!removable) {
                retained.add(item);
            }
        }
        if (!retained.isEmpty()) {
            plainSelect.setSelectItems(retained);
        }
    }

    private boolean isSeparateTableSelectItem(SelectItem<?> item, TableContext tableContext) {
        final boolean[] removable = {false};
        item.accept(new SelectItemVisitorAdapter() {
            @Override
            public void visit(SelectItem selectExpressionItem) {
                ColumnResolution resolution = resolveEncryptedColumn(selectExpressionItem.getExpression(), tableContext);
                removable[0] = resolution != null && resolution.rule().isStoredInSeparateTable();
            }
        });
        return removable[0];
    }

    private void validateOrderBy(List<OrderByElement> orderByElements, TableContext tableContext) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement element : orderByElements) {
            ColumnResolution resolution = resolveEncryptedColumn(element.getExpression(), tableContext);
            if (resolution != null) {
                throw new UnsupportedEncryptedOperationException(
                        "ORDER BY is not supported on encrypted field: " + resolution.rule().property());
            }
        }
    }

    private Expression buildExistsSubQuery(Column sourceColumn,
                                           EncryptColumnRule rule,
                                           String targetColumn,
                                           Expression valueExpression,
                                           boolean equality) {
        PlainSelect subQueryBody = new PlainSelect();
        subQueryBody.addSelectItems(SelectItem.from(new LongValue(1)));
        subQueryBody.setFromItem(new Table(quote(rule.storageTable())));
        EqualsTo joinEquals = new EqualsTo();
        joinEquals.setLeftExpression(new Column(quote(rule.storageIdColumn())));
        joinEquals.setRightExpression(buildColumn(sourceColumn, rule.sourceIdColumn()));
        Expression valuePredicate;
        if (equality) {
            EqualsTo valueEquals = new EqualsTo();
            valueEquals.setLeftExpression(new Column(quote(targetColumn)));
            valueEquals.setRightExpression(valueExpression);
            valuePredicate = valueEquals;
        } else {
            LikeExpression likeExpression = new LikeExpression();
            likeExpression.setLeftExpression(new Column(quote(targetColumn)));
            likeExpression.setRightExpression(valueExpression);
            valuePredicate = likeExpression;
        }
        subQueryBody.setWhere(new AndExpression(joinEquals, valuePredicate));
        ExistsExpression existsExpression = new ExistsExpression();
        existsExpression.setRightExpression(subQueryBody);
        return existsExpression;
    }

    private void validateNonRangeEncryptedColumn(Expression expression, TableContext tableContext, String message) {
        if (resolveEncryptedColumn(expression, tableContext) != null) {
            throw new UnsupportedEncryptedOperationException(message);
        }
    }

    private ColumnResolution resolveComparison(BinaryExpression expression, TableContext tableContext) {
        ColumnResolution left = resolveEncryptedColumn(expression.getLeftExpression(), tableContext);
        if (left != null) {
            return new ColumnResolution(left.column(), left.rule(), true);
        }
        ColumnResolution right = resolveEncryptedColumn(expression.getRightExpression(), tableContext);
        return right == null ? null : new ColumnResolution(right.column(), right.rule(), false);
    }

    private ColumnResolution resolveEncryptedColumn(Expression expression, TableContext tableContext) {
        if (!(expression instanceof Column column)) {
            return null;
        }
        EncryptColumnRule rule = tableContext.resolve(column).orElse(null);
        return rule == null ? null : new ColumnResolution(column, rule, true);
    }

    private void registerFromItem(TableContext tableContext, FromItem fromItem) {
        if (fromItem instanceof Table table) {
            registerTable(tableContext, table);
        }
    }

    private void registerTable(TableContext tableContext, Table table) {
        EncryptTableRule rule = metadataRegistry.findByTable(table.getName()).orElse(null);
        if (rule == null) {
            return;
        }
        tableContext.register(table.getName(), table.getAlias() != null ? table.getAlias().getName() : null, rule);
    }

    private Column buildColumn(Column source, String targetColumn) {
        Column column = new Column(quote(targetColumn));
        if (source.getTable() != null && source.getTable().getName() != null) {
            Table table = new Table(source.getTable().getName());
            if (source.getTable().getAlias() != null) {
                table.setAlias(new Alias(source.getTable().getAlias().getName(), false));
            }
            column.setTable(table);
        }
        return column;
    }

    private String transformCipher(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.cipher(rule.cipherAlgorithm()));
    }

    private String transformAssisted(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.assisted(rule.assistedQueryAlgorithm()));
    }

    private String transformLike(EncryptColumnRule rule, Object plainValue) {
        return applyTransform(rule, plainValue, algorithmRegistry.like(rule.likeQueryAlgorithm()));
    }

    private String applyTransform(EncryptColumnRule rule, Object plainValue, Object algorithm) {
        if (plainValue == null) {
            return null;
        }
        String value = String.valueOf(plainValue);
        if (algorithm instanceof CipherAlgorithm cipherAlgorithm) {
            return cipherAlgorithm.encrypt(value);
        }
        if (algorithm instanceof AssistedQueryAlgorithm assistedQueryAlgorithm) {
            return assistedQueryAlgorithm.transform(value);
        }
        if (algorithm instanceof LikeQueryAlgorithm likeQueryAlgorithm) {
            return likeQueryAlgorithm.transform(value);
        }
        throw new EncryptionConfigurationException("Unsupported algorithm for field: " + rule.property());
    }

    private static final class RewriteContext {

        private final Configuration configuration;
        private final BoundSql boundSql;
        private final List<ParameterMapping> parameterMappings;
        private final Map<String, MaskedValue> maskedParameters = new LinkedHashMap<>();
        private int currentParameterIndex;
        private int generatedIndex;
        private boolean changed;

        private RewriteContext(Configuration configuration, BoundSql boundSql) {
            this.configuration = configuration;
            this.boundSql = boundSql;
            this.parameterMappings = new ArrayList<>(boundSql.getParameterMappings());
        }

        private int consumeOriginal() {
            return currentParameterIndex++;
        }

        private Object originalValue(int index, ParameterValueResolver resolver) {
            if (index < 0 || index >= parameterMappings.size()) {
                return null;
            }
            return resolver.resolve(configuration, boundSql, boundSql.getParameterObject(), parameterMappings.get(index));
        }

        private void replaceLastConsumed(Object value, MaskingMode maskingMode) {
            replaceParameter(currentParameterIndex - 1, value, maskingMode);
        }

        private JdbcParameter insertSynthetic(Object value, MaskingMode maskingMode) {
            String property = nextSyntheticName();
            // Shadow-column parameters must be inserted at the current position so that SQL placeholders stay aligned.
            parameterMappings.add(currentParameterIndex, new ParameterMapping.Builder(configuration, property,
                    value == null ? String.class : value.getClass()).build());
            maskedParameters.put(property, mask(maskingMode, value));
            currentParameterIndex++;
            changed = true;
            return new JdbcParameter();
        }

        private void replaceParameter(int parameterIndex, Object value, MaskingMode maskingMode) {
            if (parameterIndex < 0 || parameterIndex >= parameterMappings.size()) {
                return;
            }
            ParameterMapping original = parameterMappings.get(parameterIndex);
            String property = nextSyntheticName();
            // Do not reuse the original property name, or MyBatis may overwrite values from the business parameter object.
            ParameterMapping rewritten = new ParameterMapping.Builder(configuration, property,
                    value == null ? String.class : value.getClass())
                    .jdbcType(original.getJdbcType())
                    .build();
            boundSql.setAdditionalParameter(property, value);
            maskedParameters.put(property, mask(maskingMode, value));
            changed = true;
        }

        private String nextSyntheticName() {
            generatedIndex++;
            return "__encrypt_generated_" + generatedIndex;
        }

        private MaskedValue mask(MaskingMode maskingMode, Object value) {
            if (value == null) {
                return new MaskedValue(maskingMode.name(), "<null>");
            }
            if (maskingMode == MaskingMode.HASH) {
                return new MaskedValue(maskingMode.name(), String.valueOf(value));
            }
            return new MaskedValue(maskingMode.name(), "***");
        }
    }

    private static final class TableContext {

        private final Map<String, EncryptTableRule> ruleByAlias = new LinkedHashMap<>();

        private void register(String tableName, String alias, EncryptTableRule rule) {
            ruleByAlias.put(NameUtils.normalizeIdentifier(tableName), rule);
            if (alias != null && !alias.isBlank()) {
                ruleByAlias.put(NameUtils.normalizeIdentifier(alias), rule);
            }
        }

        private Optional<EncryptColumnRule> resolve(Column column) {
            if (column.getTable() != null && column.getTable().getName() != null && !column.getTable().getName().isBlank()) {
                EncryptTableRule tableRule = ruleByAlias.get(NameUtils.normalizeIdentifier(column.getTable().getName()));
                if (tableRule != null) {
                    return tableRule.findByColumn(column.getColumnName());
                }
            }
            EncryptColumnRule candidate = null;
            for (EncryptTableRule tableRule : ruleByAlias.values()) {
                EncryptColumnRule rule = tableRule.findByColumn(column.getColumnName()).orElse(null);
                if (rule == null) {
                    continue;
                }
                if (candidate != null) {
                    // Unqualified encrypted columns are rejected when multiple encrypted tables match the same name.
                    throw new UnsupportedEncryptedOperationException(
                            "Ambiguous encrypted column reference: " + column.getFullyQualifiedName());
                }
                candidate = rule;
            }
            return Optional.ofNullable(candidate);
        }

        private boolean isEmpty() {
            return ruleByAlias.isEmpty();
        }
    }

    private record WriteValue(Expression expression, Object plainValue, boolean parameterized) {
    }

    private record ColumnResolution(Column column, EncryptColumnRule rule, boolean leftColumn) {
    }

    private enum MaskingMode {
        MASKED,
        HASH
    }

    private String quote(String identifier) {
        SqlDialect dialect = properties.getSqlDialect();
        return dialect == null ? identifier : dialect.quote(identifier);
    }
}
