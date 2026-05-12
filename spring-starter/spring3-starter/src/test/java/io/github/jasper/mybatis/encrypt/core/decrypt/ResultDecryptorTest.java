package io.github.jasper.mybatis.encrypt.core.decrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptResultHint;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;

@Tag("unit")
@Tag("decrypt")
class ResultDecryptorTest {

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptAnnotatedEntityCollection() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());

        UserEntity entity = new UserEntity();
        entity.setPhone(sm4.encrypt("13800138000"));
        entity.setName("jasper");

        MappedStatement mappedStatement = entityMappedStatement(UserEntity.class, "test.selectEntity");
        decrypt(decryptor, mappedStatement, List.of(entity));

        assertEquals("13800138000", entity.getPhone());
        assertEquals("jasper", entity.getName());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptFieldLevelAnnotatedDtoWithoutEncryptTable() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());

        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13900139000"));
        dto.setName("nora");

        MappedStatement mappedStatement = entityMappedStatement(UserProjectionDto.class, "test.selectAnnotatedDto");
        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13900139000", dto.getPhone());
        assertEquals("nora", dto.getName());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptMappedNestedPropertyWithoutTraversingUnmappedGetter() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        ProjectionWrapper wrapper = new ProjectionWrapper();
        UserProjectionDto dto = new UserProjectionDto();
        dto.setPhone(sm4.encrypt("13700137000"));
        wrapper.setUser(dto);
        MappedStatement mappedStatement = mappedStatement();

        assertDoesNotThrow(() -> decrypt(decryptor, mappedStatement, List.of(wrapper)));

        assertEquals("13700137000", wrapper.getUser().getPhone());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptConfiguredDtoWithoutEncryptFieldByExplicitResultMapping() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13600136000"));
        dto.setName("iris");
        MappedStatement mappedStatement = configuredDtoMappedStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13600136000", dto.getPhone());
        assertEquals("iris", dto.getName());
    }

    /**
     * 测试目的：验证同表模式与独立表模式存在列名冲突时，结果计划不会绑定到错误字段规则。
     * 测试场景：构造物理列、投影别名和返回属性冲突的查询，断言优先按别名消歧或在无别名时保守跳过。
     */
    @Test
    void shouldDecryptConfiguredDtoWithoutEncryptFieldByAutoMappedProjectionAlias() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13500135000"));
        dto.setName("zoe");
        MappedStatement mappedStatement = autoMappedConfiguredDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13500135000", dto.getPhone());
        assertEquals("zoe", dto.getName());
    }

    @Test
    void shouldDecryptConfiguredDtoFromMaxAggregateProjectionAlias() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13500135002"));
        MappedStatement mappedStatement = maxAggregateConfiguredDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13500135002", dto.getPhone());
    }

    /**
     * 测试目的：验证 Map 返回结果也能按 ResultMap 或 SQL 投影生成解密计划。
     * 测试场景：构造 Map 行数据和显式结果映射，断言密文字段按 key 写回为明文且普通字段不受影响。
     */
    @Test
    void shouldDecryptMapResultByExplicitResultMapping() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("phone", sm4.encrypt("13500135001"));
        row.put("name", "map-user");
        MappedStatement mappedStatement = configuredMapMappedStatement();

        decrypt(decryptor, mappedStatement, List.of(row));

        assertEquals("13500135001", row.get("phone"));
        assertEquals("map-user", row.get("name"));
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByMethodHintAndAutoMappedProjection() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13400134000"));
        dto.setName("maya");
        MappedStatement mappedStatement = hintedAutoMappedDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13400134000", dto.getPhone());
        assertEquals("maya", dto.getName());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByMethodHintAndSelectAll() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13300133000"));
        dto.setName("wildcard");
        MappedStatement mappedStatement = hintedSelectAllDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13300133000", dto.getPhone());
        assertEquals("wildcard", dto.getName());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldByAutoDetectedSqlSourceTable() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13200132000"));
        dto.setName("auto");
        MappedStatement mappedStatement = autoDetectedDtoStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13200132000", dto.getPhone());
        assertEquals("auto", dto.getName());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldDecryptAutoDetectedDtoWhenSqlContainsRepeatedBlankLines() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13100131000"));
        dto.setName("blank-lines");
        MappedStatement mappedStatement = autoDetectedDtoStatementWithBlankLines();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13100131000", dto.getPhone());
        assertEquals("blank-lines", dto.getName());
    }

    /**
     * 测试目的：验证同表模式与独立表模式存在列名冲突时，结果计划不会绑定到错误字段规则。
     * 测试场景：构造物理列、投影别名和返回属性冲突的查询，断言优先按别名消歧或在无别名时保守跳过。
     */
    @Test
    void shouldPreferProjectionAliasWhenPhysicalColumnMatchesAnotherStorageModeRule() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, conflictingStorageModeProperties());
        PlainUserProjectionDto dto = new PlainUserProjectionDto();
        dto.setPhone(sm4.encrypt("13000130000"));
        MappedStatement mappedStatement = conflictingProjectionStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13000130000", dto.getPhone());
    }

    /**
     * 测试目的：验证同表模式与独立表模式存在列名冲突时，结果计划不会绑定到错误字段规则。
     * 测试场景：构造物理列、投影别名和返回属性冲突的查询，断言优先按别名消歧或在无别名时保守跳过。
     */
    @Test
    void shouldSkipAmbiguousPhysicalProjectionWithoutAlias() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, conflictingStorageModeProperties());
        MappedStatement mappedStatement = conflictingProjectionWithoutAliasStatement();

        QueryResultPlan queryResultPlan = decryptor.resolvePlan(mappedStatement, mappedStatement.getBoundSql(null));

        assertTrue(queryResultPlan.isEmpty());
    }

    /**
     * 测试目的：验证查询结果解密能按 MyBatis 映射边界精确处理返回对象。
     * 测试场景：构造实体、DTO、嵌套对象或提示注解，断言解密计划、属性写回和敏感值记录符合预期。
     */
    @Test
    void shouldUseBackingFieldAndRecordSensitiveValueWhenGetterHasSideEffect() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, new DatabaseEncryptionProperties());
        SideEffectGetterDto dto = new SideEffectGetterDto();
        dto.setPhone(sm4.encrypt("13800138000"));
        MappedStatement mappedStatement = entityMappedStatement(SideEffectGetterDto.class, "test.selectSideEffectDto");

        try (SensitiveDataContext.Scope ignored =
                     SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            decrypt(decryptor, mappedStatement, List.of(dto));
            new SensitiveDataMasker().mask(dto);
        }

        assertEquals("*******8000", dto.rawPhone());
    }

    /**
     * 测试目的：验证 JOIN 非加密表且非加密表存在同名字段时，解密计划不会把非加密表的别名列绑定到加密规则。
     * 测试场景：LEFT JOIN 一张非加密表，该表字段名与加密表字段同名但使用了别名，
     * 断言加密表字段正常解密，非加密表别名列保持明文不做解密。
     */
    @Test
    void shouldNotDecryptNonEncryptedTableAliasedColumnWithSameName() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        JoinResultDto dto = new JoinResultDto();
        dto.setPhone(sm4.encrypt("13800138000"));
        dto.setBackupPhone("plaintext-from-non-encrypted-table");
        MappedStatement mappedStatement = joinResultAutoMappedStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13800138000", dto.getPhone());
        assertEquals("plaintext-from-non-encrypted-table", dto.getBackupPhone());
    }

    /**
     * 测试目的：验证 JOIN 非加密表且使用通配符展开时，非加密表的同名列不会被错误解密。
     * 测试场景：SELECT 使用加密表通配符 a.* 展开，同时非加密表同名列使用别名，
     * 断言加密字段正常解密，非加密别名列保持明文。
     */
    @Test
    void shouldNotDecryptNonEncryptedTableAliasedColumnWhenEncryptedTableUsesWildcard() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        JoinResultDto dto = new JoinResultDto();
        dto.setPhone(sm4.encrypt("13900139000"));
        dto.setBackupPhone("wildcard-plaintext");
        MappedStatement mappedStatement = joinResultWildcardMappedStatement();

        decrypt(decryptor, mappedStatement, List.of(dto));

        assertEquals("13900139000", dto.getPhone());
        assertEquals("wildcard-plaintext", dto.getBackupPhone());
    }

    /**
     * 测试目的：验证多表 JOIN 时，非加密表的无别名同名列不会通过唯一规则回退被错误解析。
     * 测试场景：非加密表字段与加密表字段同名但不使用别名，
     * 断言解密计划为空，不会将非加密表字段绑定到加密规则。
     */
    @Test
    void shouldSkipNonEncryptedTableColumnWithoutAliasInMultiTableJoin() {
        Sm4CipherAlgorithm sm4 = new Sm4CipherAlgorithm("unit-test-key");
        ResultDecryptor decryptor = createDecryptor(sm4, configuredUserTableProperties());
        MappedStatement mappedStatement = joinResultWithoutAliasMappedStatement();

        QueryResultPlan queryResultPlan = decryptor.resolvePlan(mappedStatement, mappedStatement.getBoundSql(null));

        assertTrue(queryResultPlan.isEmpty());
    }

    private void decrypt(ResultDecryptor decryptor, MappedStatement mappedStatement, Object resultObject) {
        BoundSql boundSql = mappedStatement.getBoundSql(null);
        QueryResultPlan queryResultPlan = decryptor.resolvePlan(mappedStatement, boundSql);
        decryptor.decrypt(resultObject, queryResultPlan);
    }

    private ResultDecryptor createDecryptor(Sm4CipherAlgorithm sm4, DatabaseEncryptionProperties properties) {
        return new ResultDecryptor(
                new EncryptMetadataRegistry(properties, new AnnotationEncryptMetadataLoader()),
                new AlgorithmRegistry(
                        Map.of("sm4", sm4),
                        Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                        Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
                ),
                null
        );
    }

    private MappedStatement mappedStatement() {
        Configuration configuration = new Configuration();
        ResultMapping phoneMapping = new ResultMapping.Builder(configuration, "user.phone", "phone", String.class).build();
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.wrapperMap", ProjectionWrapper.class, List.of(phoneMapping)).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, "select phone from user_account", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectWrapper", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement entityMappedStatement(Class<?> resultType, String id) {
        Configuration configuration = new Configuration();
        ResultMap resultMap = new ResultMap.Builder(configuration, id + ".rm", resultType, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration, "select * from user_account", List.of(),
                parameterObject);
        return new MappedStatement.Builder(configuration, id, sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement configuredDtoMappedStatement() {
        Configuration configuration = new Configuration();
        ResultMapping idMapping = new ResultMapping.Builder(configuration, "id", "id", Long.class).build();
        ResultMapping nameMapping = new ResultMapping.Builder(configuration, "name", "name", String.class).build();
        ResultMapping phoneMapping = new ResultMapping.Builder(configuration, "phone", "phone_value", String.class).build();
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.configuredDtoMap", PlainUserProjectionDto.class,
                List.of(idMapping, nameMapping, phoneMapping)).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone_value from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectConfiguredDto", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement autoMappedConfiguredDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.autoMappedConfiguredDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectAutoMappedConfiguredDto", sqlSource,
                SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement maxAggregateConfiguredDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.maxAggregateConfiguredDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select max(u.phone) as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPlainUserProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement configuredMapMappedStatement() {
        Configuration configuration = new Configuration();
        ResultMapping nameMapping = new ResultMapping.Builder(configuration, "name", "name", String.class).build();
        ResultMapping phoneMapping = new ResultMapping.Builder(configuration, "phone", "phone_value", String.class).build();
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.configuredMapResult", Map.class, List.of(nameMapping, phoneMapping)).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.name, u.phone as phone_value from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectConfiguredMapResult", sqlSource,
                SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement hintedAutoMappedDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.hintedAutoMappedDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, HintMapper.class.getName() + ".selectPlainUserProjection",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement hintedSelectAllDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.hintedSelectAllDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select * from user_account", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, HintMapper.class.getName() + ".selectPlainUserByWildcard",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement autoDetectedDtoStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.autoDetectedDto", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.name, u.phone as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPlainUserProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement autoDetectedDtoStatementWithBlankLines() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.autoDetectedDtoBlankLines", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id,\n\n u.name,\n\n u.phone as phone\n\n from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPlainUserProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement conflictingProjectionStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.conflictingProjection", PlainUserProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.phone_cipher as phone from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPlainUserProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement conflictingProjectionWithoutAliasStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.conflictingProjectionWithoutAlias", PhoneCipherProjectionDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "select u.id, u.phone_cipher from user_account u", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration,
                AutoDetectedMapper.class.getName() + ".selectPhoneCipherProjection", sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement joinResultAutoMappedStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.joinResultAutoMapped", JoinResultDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "SELECT a.phone, b.phone AS backup_phone FROM user_account a " +
                        "LEFT JOIN order_account b ON a.id = b.user_id", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectJoinResult",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement joinResultWildcardMappedStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.joinResultWildcard", JoinResultDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "SELECT a.*, b.phone AS backup_phone FROM user_account a " +
                        "LEFT JOIN order_account b ON a.id = b.user_id", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectJoinResultWildcard",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private MappedStatement joinResultWithoutAliasMappedStatement() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        ResultMap resultMap = new ResultMap.Builder(
                configuration, "test.joinResultNoAlias", JoinResultNoAliasDto.class, List.of()).build();
        SqlSource sqlSource = parameterObject -> new BoundSql(configuration,
                "SELECT b.phone FROM user_account a " +
                        "LEFT JOIN order_account b ON a.id = b.user_id", List.of(), parameterObject);
        return new MappedStatement.Builder(configuration, "test.selectJoinResultNoAlias",
                sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
    }

    private DatabaseEncryptionProperties configuredUserTableProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");
        DatabaseEncryptionProperties.FieldRuleProperties phoneRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        phoneRule.setColumn("phone");
        phoneRule.setProperty("phone");
        phoneRule.setStorageColumn("phone");
        phoneRule.setCipherAlgorithm("sm4");
        tableRule.setFields(List.of(phoneRule));
        properties.setTables(List.of(tableRule));
        assertNotNull(properties.getTables());
        return properties;
    }

    private DatabaseEncryptionProperties conflictingStorageModeProperties() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        DatabaseEncryptionProperties.TableRuleProperties tableRule = new DatabaseEncryptionProperties.TableRuleProperties();
        tableRule.setTable("user_account");

        DatabaseEncryptionProperties.FieldRuleProperties separateRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        separateRule.setProperty("idCard");
        separateRule.setColumn("phone_cipher");
        separateRule.setStorageMode(FieldStorageMode.SEPARATE_TABLE);
        separateRule.setStorageTable("user_id_card_encrypt");
        separateRule.setStorageColumn("id_card_cipher");
        separateRule.setStorageIdColumn("id");
        separateRule.setAssistedQueryColumn("phone_hash");

        DatabaseEncryptionProperties.FieldRuleProperties sameTableRule = new DatabaseEncryptionProperties.FieldRuleProperties();
        sameTableRule.setProperty("phone");
        sameTableRule.setColumn("phone");
        sameTableRule.setStorageColumn("phone_cipher");
        sameTableRule.setAssistedQueryColumn("phone_hash");

        tableRule.setFields(List.of(separateRule, sameTableRule));
        properties.setTables(List.of(tableRule));
        return properties;
    }

    @EncryptTable("user_account")
    static class UserEntity {

        @EncryptField(column = "phone")
        private String phone;

        private String name;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class UserProjectionDto {

        @EncryptField(table = "user_account", column = "phone")
        private String phone;

        private String name;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class SideEffectGetterDto {

        @EncryptField(table = "user_account", column = "phone")
        @SensitiveField
        private String phone;

        public String getPhone() {
            return "business-side-effect-value";
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        String rawPhone() {
            return phone;
        }
    }

    static class PlainUserProjectionDto {

        private Long id;
        private String phone;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class PhoneCipherProjectionDto {

        private String phoneCipher;

        public String getPhoneCipher() {
            return phoneCipher;
        }

        public void setPhoneCipher(String phoneCipher) {
            this.phoneCipher = phoneCipher;
        }
    }

    static class JoinResultDto {

        private String phone;
        private String backupPhone;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getBackupPhone() {
            return backupPhone;
        }

        public void setBackupPhone(String backupPhone) {
            this.backupPhone = backupPhone;
        }
    }

    static class JoinResultNoAliasDto {

        private String phone;

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    static class ProjectionWrapper {

        private UserProjectionDto user;

        public UserProjectionDto getUser() {
            return user;
        }

        public void setUser(UserProjectionDto user) {
            this.user = user;
        }

        public String getProblematic() {
            throw new IllegalStateException("unmapped getter should not be traversed");
        }
    }

    interface HintMapper {

        @EncryptResultHint(entities = UserEntity.class)
        PlainUserProjectionDto selectPlainUserProjection();

        @EncryptResultHint(entities = UserEntity.class)
        PlainUserProjectionDto selectPlainUserByWildcard();
    }

    interface AutoDetectedMapper {

        PlainUserProjectionDto selectPlainUserProjection();

        PhoneCipherProjectionDto selectPhoneCipherProjection();

        int insertUser(UserEntity user);
    }
}
