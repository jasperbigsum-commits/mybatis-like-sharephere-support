package io.github.jasper.mybatis.encrypt.integration;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.metadata.AnnotationEncryptMetadataLoader;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.core.metadata.FieldStorageMode;
import io.github.jasper.mybatis.encrypt.core.rewrite.ParameterValueResolver;
import io.github.jasper.mybatis.encrypt.core.rewrite.SqlRewriteEngine;
import io.github.jasper.mybatis.encrypt.core.support.SeparateTableEncryptionManager;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.algorithm.support.NormalizedLikeQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm3AssistedQueryAlgorithm;
import io.github.jasper.mybatis.encrypt.algorithm.support.Sm4CipherAlgorithm;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.core.decrypt.ResultDecryptor;
import io.github.jasper.mybatis.encrypt.plugin.DatabaseEncryptionInterceptor;

import static org.junit.jupiter.api.Assertions.*;

class MybatisEncryptionIntegrationTest {

    private static final Path H2_DB_DIR = Path.of("target", "h2");
    private static final String H2_DB_NAME = "mybatis-encryption-integration";
    private static final Path H2_DB_FILE = H2_DB_DIR.resolve(H2_DB_NAME + ".mv.db");

    private JdbcDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;
    private ParameterCaptureInterceptor parameterCaptureInterceptor;
    private TrackingSeparateTableEncryptionManager trackingSeparateTableManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(H2_DB_DIR);
        dataSource = new JdbcDataSource();
        dataSource.setURL(databaseUrl());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        initializeSchema();
        parameterCaptureInterceptor = new ParameterCaptureInterceptor();
        sqlSessionFactory = buildSqlSessionFactory();
    }

    @Test
    void shouldEncryptSameTableFieldAndDecryptOnRead() throws Exception {
        UserRecord user = user(1L, "Alice", "13800138000", null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord loaded = mapper.selectByPhone("13800138000");
            assertNotNull(loaded);
            assertEquals("Alice", loaded.getName());
            assertEquals("13800138000", loaded.getPhone());
        }

        assertTrue(Files.exists(H2_DB_FILE));
        assertSameTableStorage(1L, "13800138000");
    }

    @Test
    void shouldStoreSeparateTableIdInMainTableAndHydrateResultByReference() throws Exception {
        UserRecord user = user(2L, "Bob", "13900139000", "320101199001011234");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));

            UserRecord byPhone = mapper.selectByPhone("13900139000");
            assertNotNull(byPhone);
            assertEquals("13900139000", byPhone.getPhone());
            assertEquals("320101199001011234", byPhone.getIdCard());

            UserRecord byIdCard = mapper.selectByIdCard("320101199001011234");
            assertNotNull(byIdCard);
            assertEquals("Bob", byIdCard.getName());
            assertEquals("320101199001011234", byIdCard.getIdCard());
        }

        String referenceId = loadReferenceId(2L);
        assertNotNull(referenceId);
        assertFalse(referenceId.isBlank());
        assertSeparateTableStorage(referenceId, "320101199001011234");
    }

    @Test
    void shouldStoreSeparateTableReferenceAsStringInBoundSql() {
        UserRecord user = user(4L, "Dave", "13600136000", "320101199001018888");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        Object referenceValue = parameterCaptureInterceptor.lastIdCardAdditionalParameter;
        assertNotNull(referenceValue);
        assertInstanceOf(String.class, referenceValue);
    }

    @Test
    void shouldInsertSeparateTableRowThroughMybatisExecutorChain() {
        UserRecord user = user(12L, "Luna", "13600136012", "320101199001010212");
        parameterCaptureInterceptor.reset();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        StatementCapture separateInsert =
                parameterCaptureInterceptor.findPreparedStatementContaining("insert into `user_id_card_encrypt`");
        assertNotNull(separateInsert);
        assertInstanceOf(Map.class, separateInsert.parameterObject);
        assertEquals(4, separateInsert.parameters.size());
        assertTrue(singleLine(separateInsert.sql).toLowerCase(Locale.ROOT).contains("values (?, ?, ?, ?)"));
    }

    @Test
    void shouldSkipSeparateTablePreparationForSameTableOnlyWrite() {
        UserRecord user = user(5L, "Eve", "13500135000", null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        assertEquals(0, trackingSeparateTableManager.prepareWriteReferencesCalls);
    }

    @Test
    void shouldSwitchToNewSeparateTableReferenceOnUpdateWithoutMutatingExistingRow() throws Exception {
        UserRecord user = user(3L, "Carol", "13700137000", "320101199001011234");

        String originalReferenceId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
            UserRecord inserted = mapper.selectById(3L);
            assertNotNull(inserted);
            assertEquals("320101199001011234", inserted.getIdCard());
        }

        originalReferenceId = loadReferenceId(3L);

        UserRecord update = user(3L, null, null, "320101199001019999");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updateIdCard(update));
            UserRecord loaded = mapper.selectById(3L);
            assertNotNull(loaded);
            assertEquals("320101199001019999", loaded.getIdCard());
        }

        String updatedReferenceId = loadReferenceId(3L);
        assertNotEquals(originalReferenceId, updatedReferenceId);
        assertSeparateTableStorage(originalReferenceId, "320101199001011234");
        assertSeparateTableStorage(updatedReferenceId, "320101199001019999");
    }

    @Test
    void shouldReuseExistingSeparateTableReferenceWhenAssignedHashMatches() throws Exception {
        UserRecord first = user(7L, "Gary", "13100131001", "320101199001015555");
        UserRecord second = user(8L, "Helen", "13100131002", "320101199001016666");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(first));
            assertEquals(1, mapper.insertUser(second));
        }

        String firstReferenceId = loadReferenceId(7L);
        String secondReferenceId = loadReferenceId(8L);
        assertNotEquals(firstReferenceId, secondReferenceId);

        UserRecord update = user(7L, null, null, "320101199001016666");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updateIdCard(update));
            UserRecord loaded = mapper.selectById(7L);
            assertNotNull(loaded);
            assertEquals("320101199001016666", loaded.getIdCard());
        }

        assertEquals(secondReferenceId, loadReferenceId(7L));
        assertSeparateTableStorage(firstReferenceId, "320101199001015555");
        assertSeparateTableStorage(secondReferenceId, "320101199001016666");
        assertEquals(2, queryForInt("select count(1) from user_id_card_encrypt"));
    }

    @Test
    void shouldPersistAndReadConsistentlyAcrossStorageModesOnInsertAndUpdate() throws Exception {
        UserRecord inserted = user(6L, "Frank", "13400134000", "320101199001017777");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(inserted));
            assertReadableUser(mapper, inserted);
        }

        String originalReferenceId = loadReferenceId(6L);
        assertSameTableStorage(6L, "13400134000");
        assertSeparateTableStorage(originalReferenceId, "320101199001017777");

        UserRecord updated = user(6L, "Frank", "13400134999", "320101199001016666");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.updatePhoneAndIdCard(updated));
            assertReadableUser(mapper, updated);
            assertNull(mapper.selectByPhone("13400134000"));
            assertNull(mapper.selectByIdCard("320101199001017777"));
        }

        String updatedReferenceId = loadReferenceId(6L);
        assertNotEquals(originalReferenceId, updatedReferenceId);
        assertSameTableStorage(6L, "13400134999");
        assertSeparateTableStorage(originalReferenceId, "320101199001017777");
        assertSeparateTableStorage(updatedReferenceId, "320101199001016666");
    }

    @Test
    void shouldKeepSelectPlaceholderParameterBeforeEncryptedWhereParameter() throws Exception {
        UserRecord user = user(9L, "Iris", "13600136009", "320101199001010209");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        String referenceId = loadReferenceId(9L);
        String expectedHash = loadSeparateTableHash(referenceId);
        parameterCaptureInterceptor.reset();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserProjectionDto loaded = mapper.selectWithPlainPlaceholderAndEncryptedWhere(
                    "visible-label",
                    "320101199001010209"
            );
            assertNotNull(loaded);
            assertEquals(9L, loaded.getId());
            assertEquals("visible-label", loaded.getDisplayName());
            assertEquals("13600136009", loaded.getPhone());
            assertEquals("320101199001010209", loaded.getIdCard());
        }

        String normalizedSql = singleLine(parameterCaptureInterceptor.lastPreparedSql).toLowerCase(Locale.ROOT);
        assertTrue(normalizedSql.contains("select ? as display_name"));
        assertTrue(normalizedSql.contains("user_id_card_encrypt"));
        assertTrue(normalizedSql.contains("id_card_hash"));
        assertEquals(2, parameterCaptureInterceptor.lastResolvedParameters.size());
        assertEquals("visible-label", parameterCaptureInterceptor.lastResolvedParameters.get(0));
        assertEquals(expectedHash, parameterCaptureInterceptor.lastResolvedParameters.get(1));
    }

    @Test
    void shouldKeepMultipleSelectPlaceholdersBeforeEncryptedWhereParameter() throws Exception {
        UserRecord user = user(10L, "Jill", "13600136010", "320101199001010210");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        String referenceId = loadReferenceId(10L);
        String expectedHash = loadSeparateTableHash(referenceId);
        parameterCaptureInterceptor.reset();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserProjectionDto loaded = mapper.selectWithMultiplePlainPlaceholdersAndEncryptedWhere(
                    "visible-label-a",
                    "visible-label-b",
                    "320101199001010210"
            );
            assertNotNull(loaded);
            assertEquals(10L, loaded.getId());
            assertEquals("visible-label-a", loaded.getDisplayName());
            assertEquals("13600136010", loaded.getPhone());
            assertEquals("320101199001010210", loaded.getIdCard());
        }

        String normalizedSql = singleLine(parameterCaptureInterceptor.lastPreparedSql).toLowerCase(Locale.ROOT);
        assertTrue(normalizedSql.contains("select ? as display_name"));
        assertTrue(normalizedSql.contains("? as marker"));
        assertTrue(normalizedSql.contains("user_id_card_encrypt"));
        assertTrue(normalizedSql.contains("id_card_hash"));
        assertEquals(3, parameterCaptureInterceptor.lastResolvedParameters.size());
        assertEquals("visible-label-a", parameterCaptureInterceptor.lastResolvedParameters.get(0));
        assertEquals("visible-label-b", parameterCaptureInterceptor.lastResolvedParameters.get(1));
        assertEquals(expectedHash, parameterCaptureInterceptor.lastResolvedParameters.get(2));
    }

    @Test
    void shouldKeepMultipleSelectPlaceholdersBeforeSameTableEncryptedWhereParameter() throws Exception {
        UserRecord user = user(11L, "Kara", "13600136011", "320101199001010211");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        String expectedHash = loadPhoneHash(11L);
        parameterCaptureInterceptor.reset();

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserProjectionDto loaded = mapper.selectWithMultiplePlainPlaceholdersAndSameTableEncryptedWhere(
                    "visible-label-a",
                    "visible-label-b",
                    "13600136011"
            );
            assertNotNull(loaded);
            assertEquals(11L, loaded.getId());
            assertEquals("visible-label-a", loaded.getDisplayName());
            assertEquals("13600136011", loaded.getPhone());
            assertEquals("320101199001010211", loaded.getIdCard());
        }

        String normalizedSql = singleLine(parameterCaptureInterceptor.lastPreparedSql).toLowerCase(Locale.ROOT);
        assertTrue(normalizedSql.contains("select ? as display_name"));
        assertTrue(normalizedSql.contains("? as marker"));
        assertTrue(normalizedSql.contains("phone_hash"));
        assertEquals(3, parameterCaptureInterceptor.lastResolvedParameters.size());
        assertEquals("visible-label-a", parameterCaptureInterceptor.lastResolvedParameters.get(0));
        assertEquals("visible-label-b", parameterCaptureInterceptor.lastResolvedParameters.get(1));
        assertEquals(expectedHash, parameterCaptureInterceptor.lastResolvedParameters.get(2));
    }

    @Test
    void shouldReadComplexDerivedJoinQueryAcrossStorageModes() throws Exception {
        UserRecord matched = user(31L, "Kate", "13000130001", "320101199001010131");
        UserRecord ignored = user(32L, "Leo", "13000130002", "320101199001010132");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(matched));
            assertEquals(1, mapper.insertUser(ignored));
        }

        insertOrderAccountRow(101L, 31L, 1001L, "first", "grid-a", 0);
        insertOrderAccountRow(102L, 31L, 1009L, "second", "grid-b", 0);
        insertOrderAccountRow(103L, 32L, 1005L, "third", "grid-c", 0);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserRecord loaded = mapper.selectLatestByComplexProjection(31L, "31");
            assertNotNull(loaded);
            assertEquals(31L, loaded.getId());
            assertEquals("Kate", loaded.getName());
            assertEquals("13000130001", loaded.getPhone());
            assertEquals("320101199001010131", loaded.getIdCard());
        }
    }

    @Test
    void shouldReadAssociatedEntityListAcrossStorageModes() throws Exception {
        UserRecord ownerOne = user(41L, "Mia", "13100131001", "320101199001010141");
        UserRecord reviewerOne = user(42L, "Noah", "13100131002", "320101199001010142");
        UserRecord ownerTwo = user(43L, "Olivia", "13100131003", "320101199001010143");
        UserRecord reviewerTwo = user(44L, "Piper", "13100131004", "320101199001010144");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(ownerOne));
            assertEquals(1, mapper.insertUser(reviewerOne));
            assertEquals(1, mapper.insertUser(ownerTwo));
            assertEquals(1, mapper.insertUser(reviewerTwo));
        }

        insertOrderAccountRow(201L, 41L, 42L, 2001L, "assoc-a", "grid-a", 0);
        insertOrderAccountRow(202L, 43L, 44L, 2002L, "assoc-b", "grid-b", 0);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<OrderAssociationView> views = mapper.selectOrderAssociations();
            assertEquals(2, views.size());

            OrderAssociationView first = views.get(0);
            assertEquals(201L, first.getOrderId());
            assertEquals("assoc-a", first.getRemark());
            assertNotNull(first.getOwner());
            assertEquals("Mia", first.getOwner().getName());
            assertEquals("13100131001", first.getOwner().getPhone());
            assertEquals("320101199001010141", first.getOwner().getIdCard());
            assertNotNull(first.getReviewer());
            assertEquals("Noah", first.getReviewer().getName());
            assertEquals("13100131002", first.getReviewer().getPhone());
            assertEquals("320101199001010142", first.getReviewer().getIdCard());

            OrderAssociationView second = views.get(1);
            assertEquals(202L, second.getOrderId());
            assertEquals("assoc-b", second.getRemark());
            assertNotNull(second.getOwner());
            assertEquals("Olivia", second.getOwner().getName());
            assertEquals("13100131003", second.getOwner().getPhone());
            assertEquals("320101199001010143", second.getOwner().getIdCard());
            assertNotNull(second.getReviewer());
            assertEquals("Piper", second.getReviewer().getName());
            assertEquals("13100131004", second.getReviewer().getPhone());
            assertEquals("320101199001010144", second.getReviewer().getIdCard());
        }
    }

    @Test
    void shouldReadAnnotatedDtoAcrossStorageModes() {
        UserRecord user = user(51L, "Quinn", "13200132051", "320101199001010151");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            UserProjectionDto dto = mapper.selectUserProjectionDto(51L);
            assertNotNull(dto);
            assertEquals(51L, dto.getId());
            assertEquals("Quinn", dto.getDisplayName());
            assertEquals("13200132051", dto.getPhone());
            assertEquals("320101199001010151", dto.getIdCard());
        }
    }

    @Test
    void shouldDecryptPlainDtoWithoutEncryptFieldUsingMappedStatementProjection() {
        UserRecord user = user(52L, "Rita", "13200132052", "320101199001010152");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(user));
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            PlainUserProjectionDto dto = mapper.selectPlainProjectionDto(52L);
            assertNotNull(dto);
            assertEquals(52L, dto.getId());
            assertEquals("Rita", dto.getDisplayName());
            assertEquals("13200132052", dto.getPhone());
            assertEquals("320101199001010152", dto.getIdCard());
        }
    }

    @Test
    void shouldReadNestedDtoAssociationListAcrossStorageModes() throws Exception {
        UserRecord owner = user(61L, "Rita", "13300133061", "320101199001010161");
        UserRecord reviewer = user(62L, "Sam", "13300133062", "320101199001010162");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(owner));
            assertEquals(1, mapper.insertUser(reviewer));
        }

        insertOrderAccountRow(301L, 61L, 62L, 3001L, "nested-assoc", "grid-z", 0);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<OrderNestedDto> views = mapper.selectNestedOrderAssociations();
            assertEquals(1, views.size());

            OrderNestedDto view = views.get(0);
            assertEquals(301L, view.getOrderId());
            assertNotNull(view.getParticipants());
            assertNotNull(view.getParticipants().getOwner());
            assertEquals("Rita", view.getParticipants().getOwner().getDisplayName());
            assertEquals("13300133061", view.getParticipants().getOwner().getPhone());
            assertEquals("320101199001010161", view.getParticipants().getOwner().getIdCard());
            assertNotNull(view.getParticipants().getReviewer());
            assertEquals("Sam", view.getParticipants().getReviewer().getDisplayName());
            assertEquals("13300133062", view.getParticipants().getReviewer().getPhone());
            assertEquals("320101199001010162", view.getParticipants().getReviewer().getIdCard());
        }
    }

    @Test
    void shouldReadParentListContainingChildDtoListAcrossStorageModes() throws Exception {
        UserRecord firstA = user(71L, "Tina", "13400134071", "320101199001010171");
        UserRecord firstB = user(72L, "Uma", "13400134072", "320101199001010172");
        UserRecord secondA = user(73L, "Vera", "13400134073", "320101199001010173");
        UserRecord secondB = user(74L, "Wade", "13400134074", "320101199001010174");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(firstA));
            assertEquals(1, mapper.insertUser(firstB));
            assertEquals(1, mapper.insertUser(secondA));
            assertEquals(1, mapper.insertUser(secondB));
        }

        insertOrderAccountRow(401L, 71L, 4001L, "child-list-a", "grid-l1", 0);
        insertOrderAccountRow(402L, 73L, 4002L, "child-list-b", "grid-l2", 0);
        insertOrderParticipantRow(501L, 401L, 71L, 1);
        insertOrderParticipantRow(502L, 401L, 72L, 2);
        insertOrderParticipantRow(503L, 402L, 73L, 1);
        insertOrderParticipantRow(504L, 402L, 74L, 2);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<OrderGroupDto> groups = mapper.selectOrderGroupsWithParticipants();
            assertEquals(2, groups.size());

            OrderGroupDto first = groups.get(0);
            assertEquals(401L, first.getOrderId());
            assertEquals("child-list-a", first.getRemark());
            assertNotNull(first.getParticipants());
            assertEquals(2, first.getParticipants().size());
            assertEquals("Tina", first.getParticipants().get(0).getDisplayName());
            assertEquals("13400134071", first.getParticipants().get(0).getPhone());
            assertEquals("320101199001010171", first.getParticipants().get(0).getIdCard());
            assertEquals("Uma", first.getParticipants().get(1).getDisplayName());
            assertEquals("13400134072", first.getParticipants().get(1).getPhone());
            assertEquals("320101199001010172", first.getParticipants().get(1).getIdCard());

            OrderGroupDto second = groups.get(1);
            assertEquals(402L, second.getOrderId());
            assertEquals("child-list-b", second.getRemark());
            assertNotNull(second.getParticipants());
            assertEquals(2, second.getParticipants().size());
            assertEquals("Vera", second.getParticipants().get(0).getDisplayName());
            assertEquals("13400134073", second.getParticipants().get(0).getPhone());
            assertEquals("320101199001010173", second.getParticipants().get(0).getIdCard());
            assertEquals("Wade", second.getParticipants().get(1).getDisplayName());
            assertEquals("13400134074", second.getParticipants().get(1).getPhone());
            assertEquals("320101199001010174", second.getParticipants().get(1).getIdCard());
        }
    }

    @Test
    void shouldDecryptComplexDtoWithMixedFieldModesAcrossStorageModes() throws Exception {
        UserRecord ownerOne = user(81L, "Xena", "13500135081", "320101199001010181");
        UserRecord reviewerOne = user(82L, "Yuri", "13500135082", "320101199001010182");
        UserRecord ownerTwo = user(83L, "Zoe", "13500135083", "320101199001010183");
        UserRecord reviewerTwo = user(84L, "Ares", "13500135084", "320101199001010184");
        UserRecord extraOne = user(85L, "Bela", "13500135085", "320101199001010185");
        UserRecord extraTwo = user(86L, "Cain", "13500135086", "320101199001010186");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(ownerOne));
            assertEquals(1, mapper.insertUser(reviewerOne));
            assertEquals(1, mapper.insertUser(ownerTwo));
            assertEquals(1, mapper.insertUser(reviewerTwo));
            assertEquals(1, mapper.insertUser(extraOne));
            assertEquals(1, mapper.insertUser(extraTwo));
        }

        insertOrderAccountRow(601L, 81L, 82L, 6001L, "mix-graph-a", "grid-m1", 0);
        insertOrderAccountRow(602L, 83L, 84L, 6002L, "mix-graph-b", "grid-m2", 0);
        insertOrderParticipantRow(701L, 601L, 81L, 1);
        insertOrderParticipantRow(702L, 601L, 82L, 2);
        insertOrderParticipantRow(703L, 601L, 85L, 3);
        insertOrderParticipantRow(704L, 602L, 83L, 1);
        insertOrderParticipantRow(705L, 602L, 84L, 2);
        insertOrderParticipantRow(706L, 602L, 86L, 3);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<ComplexMixedModeOrderDto> orders = mapper.selectComplexMixedModeOrders();
            assertEquals(2, orders.size());

            ComplexMixedModeOrderDto first = orders.get(0);
            assertEquals(601L, first.getOrderId());
            assertEquals("mix-graph-a", first.getRemark());
            assertNotNull(first.getParticipants());
            assertNotNull(first.getParticipants().getOwner());
            assertEquals("Xena", first.getParticipants().getOwner().getDisplayName());
            assertEquals("13500135081", first.getParticipants().getOwner().getPhone());
            assertEquals("320101199001010181", first.getParticipants().getOwner().getIdCard());
            assertNotNull(first.getParticipants().getReviewer());
            assertEquals("Yuri", first.getParticipants().getReviewer().getDisplayName());
            assertEquals("13500135082", first.getParticipants().getReviewer().getPhone());
            assertEquals("320101199001010182", first.getParticipants().getReviewer().getIdCard());
            assertNotNull(first.getParticipantList());
            assertEquals(3, first.getParticipantList().size());
            assertEquals("Xena", first.getParticipantList().get(0).getDisplayName());
            assertEquals("13500135081", first.getParticipantList().get(0).getPhone());
            assertEquals("320101199001010181", first.getParticipantList().get(0).getIdCard());
            assertEquals("Yuri", first.getParticipantList().get(1).getDisplayName());
            assertEquals("13500135082", first.getParticipantList().get(1).getPhone());
            assertEquals("320101199001010182", first.getParticipantList().get(1).getIdCard());
            assertEquals("Bela", first.getParticipantList().get(2).getDisplayName());
            assertEquals("13500135085", first.getParticipantList().get(2).getPhone());
            assertEquals("320101199001010185", first.getParticipantList().get(2).getIdCard());

            ComplexMixedModeOrderDto second = orders.get(1);
            assertEquals(602L, second.getOrderId());
            assertEquals("mix-graph-b", second.getRemark());
            assertNotNull(second.getParticipants());
            assertNotNull(second.getParticipants().getOwner());
            assertEquals("Zoe", second.getParticipants().getOwner().getDisplayName());
            assertEquals("13500135083", second.getParticipants().getOwner().getPhone());
            assertEquals("320101199001010183", second.getParticipants().getOwner().getIdCard());
            assertNotNull(second.getParticipants().getReviewer());
            assertEquals("Ares", second.getParticipants().getReviewer().getDisplayName());
            assertEquals("13500135084", second.getParticipants().getReviewer().getPhone());
            assertEquals("320101199001010184", second.getParticipants().getReviewer().getIdCard());
            assertNotNull(second.getParticipantList());
            assertEquals(3, second.getParticipantList().size());
            assertEquals("Zoe", second.getParticipantList().get(0).getDisplayName());
            assertEquals("13500135083", second.getParticipantList().get(0).getPhone());
            assertEquals("320101199001010183", second.getParticipantList().get(0).getIdCard());
            assertEquals("Ares", second.getParticipantList().get(1).getDisplayName());
            assertEquals("13500135084", second.getParticipantList().get(1).getPhone());
            assertEquals("320101199001010184", second.getParticipantList().get(1).getIdCard());
            assertEquals("Cain", second.getParticipantList().get(2).getDisplayName());
            assertEquals("13500135086", second.getParticipantList().get(2).getPhone());
            assertEquals("320101199001010186", second.getParticipantList().get(2).getIdCard());
        }
    }

    @Test
    void shouldReadComplexDashboardProjectionWithoutTouchingUnmappedBusinessGetters() throws Exception {
        UserRecord owner = user(87L, "Dora", "13500135087", "320101199001010187");
        UserRecord reviewer = user(88L, "Ethan", "13500135088", "320101199001010188");
        UserRecord participant = user(89L, "Fiona", "13500135089", "320101199001010189");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(owner));
            assertEquals(1, mapper.insertUser(reviewer));
            assertEquals(1, mapper.insertUser(participant));
        }

        insertOrderAccountRow(603L, 87L, 88L, 6003L, "dashboard-risk", "grid-m3", 0);
        insertOrderParticipantRow(707L, 603L, 87L, 1);
        insertOrderParticipantRow(708L, 603L, 88L, 2);
        insertOrderParticipantRow(709L, 603L, 89L, 3);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertDoesNotThrow(() -> {
                List<ProblematicDashboardDto> dashboards = mapper.selectProblematicDashboards();
                assertEquals(1, dashboards.size());

                ProblematicDashboardDto dashboard = dashboards.get(0);
                assertEquals(603L, dashboard.getOrderId());
                assertEquals("dashboard-risk", dashboard.getRemark());
                assertNotNull(dashboard.getParticipants());
                assertNotNull(dashboard.getParticipants().getOwner());
                assertEquals("Dora", dashboard.getParticipants().getOwner().getDisplayName());
                assertEquals("13500135087", dashboard.getParticipants().getOwner().getPhone());
                assertEquals("320101199001010187", dashboard.getParticipants().getOwner().getIdCard());
                assertNotNull(dashboard.getParticipants().getReviewer());
                assertEquals("Ethan", dashboard.getParticipants().getReviewer().getDisplayName());
                assertEquals("13500135088", dashboard.getParticipants().getReviewer().getPhone());
                assertEquals("320101199001010188", dashboard.getParticipants().getReviewer().getIdCard());
                assertNotNull(dashboard.getParticipantList());
                assertEquals(3, dashboard.getParticipantList().size());
                assertEquals("Dora", dashboard.getParticipantList().get(0).getDisplayName());
                assertEquals("13500135087", dashboard.getParticipantList().get(0).getPhone());
                assertEquals("320101199001010187", dashboard.getParticipantList().get(0).getIdCard());
                assertEquals("Ethan", dashboard.getParticipantList().get(1).getDisplayName());
                assertEquals("13500135088", dashboard.getParticipantList().get(1).getPhone());
                assertEquals("320101199001010188", dashboard.getParticipantList().get(1).getIdCard());
                assertEquals("Fiona", dashboard.getParticipantList().get(2).getDisplayName());
                assertEquals("13500135089", dashboard.getParticipantList().get(2).getPhone());
                assertEquals("320101199001010189", dashboard.getParticipantList().get(2).getIdCard());
            });
        }
    }

    @Test
    void shouldReadMultiUnionNestedSubqueryQueryAcrossStorageModes() throws Exception {
        UserRecord first = user(91L, "Milo", "13600136091", "320101199001010191");
        UserRecord second = user(92L, "Nina", "13600136092", "320101199001010192");
        UserRecord third = user(93L, "Owen", "13600136093", "320101199001010193");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(first));
            assertEquals(1, mapper.insertUser(second));
            assertEquals(1, mapper.insertUser(third));
        }

        insertOrderAccountRow(901L, 91L, 9001L, "union-a", "grid-u1", 0);
        insertOrderAccountRow(902L, 91L, 92L, 9002L, "union-b", "grid-u2", 0);
        insertOrderAccountRow(903L, 93L, 9003L, "union-c", "grid-u3", 0);
        insertOrderParticipantRow(991L, 901L, 91L, 1);
        insertOrderParticipantRow(992L, 902L, 92L, 2);
        insertOrderParticipantRow(993L, 903L, 93L, 1);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<UserProjectionDto> users = mapper.selectByMultiUnionNestedSubqueryAcrossStorageModes(
                    "13600136091",
                    "320101199001010192",
                    "%6093",
                    "%0193"
            );
            assertEquals(3, users.size());

            UserProjectionDto loadedFirst = users.get(0);
            assertEquals(91L, loadedFirst.getId());
            assertEquals("Milo", loadedFirst.getDisplayName());
            assertEquals("13600136091", loadedFirst.getPhone());
            assertEquals("320101199001010191", loadedFirst.getIdCard());

            UserProjectionDto loadedSecond = users.get(1);
            assertEquals(92L, loadedSecond.getId());
            assertEquals("Nina", loadedSecond.getDisplayName());
            assertEquals("13600136092", loadedSecond.getPhone());
            assertEquals("320101199001010192", loadedSecond.getIdCard());

            UserProjectionDto loadedThird = users.get(2);
            assertEquals(93L, loadedThird.getId());
            assertEquals("Owen", loadedThird.getDisplayName());
            assertEquals("13600136093", loadedThird.getPhone());
            assertEquals("320101199001010193", loadedThird.getIdCard());
        }
    }

    @Test
    void shouldReadWrappedUnionNestedSubqueryQueryAcrossStorageModesWithoutTypeCastError() throws Exception {
        UserRecord first = user(101L, "Paul", "13600136101", "320101199001010201");
        UserRecord second = user(102L, "Queen", "13600136102", "320101199001010202");
        UserRecord third = user(103L, "River", "13600136103", "320101199001010203");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            assertEquals(1, mapper.insertUser(first));
            assertEquals(1, mapper.insertUser(second));
            assertEquals(1, mapper.insertUser(third));
        }

        insertOrderAccountRow(1101L, 101L, 102L, 5001L, "wrapped-u1", "grid-w1", 0);
        insertOrderAccountRow(1102L, 103L, 102L, 5002L, "wrapped-u2", "grid-w2", 0);
        insertOrderAccountRow(1103L, 103L, 101L, 5003L, "wrapped-u3", "grid-w3", 0);
        insertOrderParticipantRow(1201L, 1101L, 101L, 1);
        insertOrderParticipantRow(1202L, 1102L, 102L, 2);
        insertOrderParticipantRow(1203L, 1103L, 103L, 3);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            List<UserProjectionDto> users = mapper.selectByWrappedMultiUnionNestedSubqueryAcrossStorageModes(
                    "13600136101",
                    "320101199001010202",
                    "%6103",
                    "%0203"
            );
            assertEquals(3, users.size());

            UserProjectionDto loadedFirst = users.get(0);
            assertEquals(101L, loadedFirst.getId());
            assertEquals("Paul", loadedFirst.getDisplayName());
            assertEquals("13600136101", loadedFirst.getPhone());
            assertEquals("320101199001010201", loadedFirst.getIdCard());

            UserProjectionDto loadedSecond = users.get(1);
            assertEquals(102L, loadedSecond.getId());
            assertEquals("Queen", loadedSecond.getDisplayName());
            assertEquals("13600136102", loadedSecond.getPhone());
            assertEquals("320101199001010202", loadedSecond.getIdCard());

            UserProjectionDto loadedThird = users.get(2);
            assertEquals(103L, loadedThird.getId());
            assertEquals("River", loadedThird.getDisplayName());
            assertEquals("13600136103", loadedThird.getPhone());
            assertEquals("320101199001010203", loadedThird.getIdCard());
        }
    }

    @Test
    void shouldBatchInsertAcrossStorageModesUsingBatchExecutor() throws Exception {
        List<UserRecord> users = List.of(
                user(11L, "Grace", "13300133001", "320101199001010011"),
                user(12L, "Heidi", "13300133002", "320101199001010022")
        );

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : users) {
                mapper.insertUser(user);
            }
            List<BatchResult> results = session.flushStatements();
            assertFalse(results.isEmpty());
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : users) {
                assertReadableUser(mapper, user);
            }
        }

        assertEquals(2, queryForInt("select count(1) from user_account"));
        assertEquals(2, queryForInt("select count(1) from user_id_card_encrypt"));
        assertSameTableStorage(11L, "13300133001");
        assertSameTableStorage(12L, "13300133002");
        assertSeparateTableStorage(loadReferenceId(11L), "320101199001010011");
        assertSeparateTableStorage(loadReferenceId(12L), "320101199001010022");
    }

    @Test
    void shouldBatchUpdateAcrossStorageModesUsingBatchExecutor() throws Exception {
        List<UserRecord> originalUsers = List.of(
                user(21L, "Ivan", "13200132001", "320101199001010031"),
                user(22L, "Judy", "13200132002", "320101199001010032")
        );
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : originalUsers) {
                assertEquals(1, mapper.insertUser(user));
            }
        }

        String reference21 = loadReferenceId(21L);
        String reference22 = loadReferenceId(22L);
        List<UserRecord> updatedUsers = List.of(
                user(21L, "Ivan", "13200132991", "320101199001019931"),
                user(22L, "Judy", "13200132992", "320101199001019932")
        );

        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : updatedUsers) {
                mapper.updatePhoneAndIdCard(user);
            }
            List<BatchResult> results = session.flushStatements();
            assertFalse(results.isEmpty());
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            for (UserRecord user : updatedUsers) {
                assertReadableUser(mapper, user);
            }
            assertNull(mapper.selectByPhone("13200132001"));
            assertNull(mapper.selectByPhone("13200132002"));
            assertNull(mapper.selectByIdCard("320101199001010031"));
            assertNull(mapper.selectByIdCard("320101199001010032"));
        }

        String updatedReference21 = loadReferenceId(21L);
        String updatedReference22 = loadReferenceId(22L);
        assertNotEquals(reference21, updatedReference21);
        assertNotEquals(reference22, updatedReference22);
        assertSameTableStorage(21L, "13200132991");
        assertSameTableStorage(22L, "13200132992");
        assertSeparateTableStorage(reference21, "320101199001010031");
        assertSeparateTableStorage(reference22, "320101199001010032");
        assertSeparateTableStorage(updatedReference21, "320101199001019931");
        assertSeparateTableStorage(updatedReference22, "320101199001019932");
    }

    private SqlSessionFactory buildSqlSessionFactory() {
        DatabaseEncryptionProperties properties = new DatabaseEncryptionProperties();
        properties.setDefaultCipherKey("integration-test-key");
        properties.setLogMaskedSql(false);

        EncryptMetadataRegistry metadataRegistry = new EncryptMetadataRegistry(
                properties, new AnnotationEncryptMetadataLoader());
        AlgorithmRegistry algorithmRegistry = new AlgorithmRegistry(
                Map.of("sm4", new Sm4CipherAlgorithm("integration-test-key")),
                Map.of("sm3", new Sm3AssistedQueryAlgorithm()),
                Map.of("normalizedLike", new NormalizedLikeQueryAlgorithm())
        );
        TrackingSeparateTableEncryptionManager separateTableManager =
                new TrackingSeparateTableEncryptionManager(dataSource, metadataRegistry, algorithmRegistry, properties);
        trackingSeparateTableManager = separateTableManager;
        ResultDecryptor resultDecryptor = new ResultDecryptor(metadataRegistry, algorithmRegistry, separateTableManager);
        SqlRewriteEngine sqlRewriteEngine = new SqlRewriteEngine(metadataRegistry, algorithmRegistry, properties);
        DatabaseEncryptionInterceptor interceptor =
                new DatabaseEncryptionInterceptor(sqlRewriteEngine, resultDecryptor, properties, separateTableManager,
                        metadataRegistry);

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(interceptor);
        configuration.addInterceptor(parameterCaptureInterceptor);
        configuration.addMapper(UserMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void initializeSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists order_participant");
            statement.execute("drop table if exists order_account");
            statement.execute("drop table if exists user_account");
            statement.execute("drop table if exists user_id_card_encrypt");
            RunScript.execute(connection, new StringReader(loadSchemaSql()));
        }
    }

    private String loadSchemaSql() throws Exception {
        try (InputStream inputStream =
                     MybatisEncryptionIntegrationTest.class.getResourceAsStream("/sql/h2-integration-schema.sql")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test schema resource: /sql/h2-integration-schema.sql");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String databaseUrl() {
        String basePath = H2_DB_DIR.resolve(H2_DB_NAME).toAbsolutePath().toString().replace("\\", "/");
        return "jdbc:h2:file:" + basePath + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    }

    private UserRecord user(Long id, String name, String phone, String idCard) {
        UserRecord user = new UserRecord();
        user.setId(id);
        user.setName(name);
        user.setPhone(phone);
        user.setIdCard(idCard);
        return user;
    }

    private void insertOrderAccountRow(long id, long userId, long createdSeq, String remark, String ownerName, int deleted)
            throws Exception {
        insertOrderAccountRow(id, userId, null, createdSeq, remark, ownerName, deleted);
    }

    private void insertOrderAccountRow(long id,
                                       long userId,
                                       Long relatedUserId,
                                       long createdSeq,
                                       String remark,
                                       String ownerName,
                                       int deleted)
            throws Exception {
        String sql = "insert into order_account (id, user_id, related_user_id, created_seq, remark, owner_name, deleted) "
                + "values (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, userId);
            if (relatedUserId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, relatedUserId);
            }
            statement.setLong(4, createdSeq);
            statement.setString(5, remark);
            statement.setString(6, ownerName);
            statement.setInt(7, deleted);
            statement.executeUpdate();
        }
    }

    private void insertOrderParticipantRow(long id, long orderId, long userId, int seqNo) throws Exception {
        String sql = "insert into order_participant (id, order_id, user_id, seq_no) values (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setLong(2, orderId);
            statement.setLong(3, userId);
            statement.setInt(4, seqNo);
            statement.executeUpdate();
        }
    }

    private void assertReadableUser(UserMapper mapper, UserRecord expected) {
        UserRecord byId = mapper.selectById(expected.getId());
        assertNotNull(byId);
        assertEquals(expected.getName(), byId.getName());
        assertEquals(expected.getPhone(), byId.getPhone());
        assertEquals(expected.getIdCard(), byId.getIdCard());

        if (expected.getPhone() != null) {
            UserRecord byPhone = mapper.selectByPhone(expected.getPhone());
            assertNotNull(byPhone);
            assertEquals(expected.getId(), byPhone.getId());
            assertEquals(expected.getPhone(), byPhone.getPhone());
        }
        if (expected.getIdCard() != null) {
            UserRecord byIdCard = mapper.selectByIdCard(expected.getIdCard());
            assertNotNull(byIdCard);
            assertEquals(expected.getId(), byIdCard.getId());
            assertEquals(expected.getIdCard(), byIdCard.getIdCard());
        }
    }

    private void assertSameTableStorage(Long id, String plainPhone) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_cipher, phone_hash, phone_like from user_account where id = " + id)) {
            resultSet.next();
            assertNotEquals(plainPhone, resultSet.getString("phone_cipher"));
            assertNotNull(resultSet.getString("phone_hash"));
            assertEquals(plainPhone, resultSet.getString("phone_like"));
        }
    }

    private void assertSeparateTableStorage(String referenceId, String plainIdCard) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id_card_cipher, id_card_hash, id_card_like from user_id_card_encrypt where id_card_hash = ?")) {
            statement.setString(1, referenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertNotEquals(plainIdCard, resultSet.getString("id_card_cipher"));
            assertNotNull(resultSet.getString("id_card_hash"));
            assertEquals(plainIdCard, resultSet.getString("id_card_like"));
            }
        }
    }

    private String loadReferenceId(long id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select id_card from user_account where id = " + id)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private String loadSeparateTableHash(String referenceId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id_card_hash from user_id_card_encrypt where id_card_hash = ?")) {
            statement.setString(1, referenceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private String loadPhoneHash(long id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select phone_hash from user_account where id = " + id)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int queryForInt(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String singleLine(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    interface UserMapper {

        @Insert("""
                insert into user_account (id, name, phone, id_card)
                values (#{id}, #{name}, #{phone}, #{idCard})
                """)
        int insertUser(UserRecord user);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where phone = #{phone}
                """)
        UserRecord selectByPhone(@Param("phone") String phone);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where id_card = #{idCard}
                """)
        UserRecord selectByIdCard(@Param("idCard") String idCard);

        @Select("""
                select #{displayName} as display_name,
                       u.id,
                       u.phone,
                       u.id_card
                from user_account u
                where u.id_card = #{idCard}
                """)
        UserProjectionDto selectWithPlainPlaceholderAndEncryptedWhere(@Param("displayName") String displayName,
                                                                      @Param("idCard") String idCard);

        @Select("""
                select #{displayName} as display_name,
                       #{marker} as marker,
                       u.id,
                       u.phone,
                       u.id_card
                from user_account u
                where u.id_card = #{idCard}
                """)
        UserProjectionDto selectWithMultiplePlainPlaceholdersAndEncryptedWhere(@Param("displayName") String displayName,
                                                                               @Param("marker") String marker,
                                                                               @Param("idCard") String idCard);

        @Select("""
                select #{displayName} as display_name,
                       #{marker} as marker,
                       u.id,
                       u.phone,
                       u.id_card
                from user_account u
                where u.phone = #{phone}
                """)
        UserProjectionDto selectWithMultiplePlainPlaceholdersAndSameTableEncryptedWhere(@Param("displayName") String displayName,
                                                                                        @Param("marker") String marker,
                                                                                        @Param("phone") String phone);

        @Select("""
                select id, name, phone, id_card
                from user_account
                where id = #{id}
                """)
        UserRecord selectById(@Param("id") Long id);

        @Select("""
                select c.id, c.name, c.phone, c.id_card
                from (
                    select o.user_id,
                           coalesce(max(case when o.user_id = #{userId} then o.created_seq end), min(o.created_seq)) as sort_key,
                           group_concat(cast(o.user_id as varchar)) as user_ids
                    from order_account o
                    where o.deleted = 0 and o.user_id is not null
                    group by o.user_id
                ) a
                join user_account c on a.user_id = c.id
                where a.user_ids regexp #{regexp}
                order by a.sort_key desc, a.user_id desc
                limit 1
                """)
        UserRecord selectLatestByComplexProjection(@Param("userId") Long userId, @Param("regexp") String regexp);

        @Select("""
                select o.id as order_id,
                       o.remark as order_remark,
                       owner.id as owner_id,
                       owner.name as owner_name,
                       owner.phone as owner_phone,
                       owner.id_card as owner_id_card,
                       reviewer.id as reviewer_id,
                       reviewer.name as reviewer_name,
                       reviewer.phone as reviewer_phone,
                       reviewer.id_card as reviewer_id_card
                from order_account o
                join user_account owner on o.user_id = owner.id
                join user_account reviewer on o.related_user_id = reviewer.id
                where o.deleted = 0
                order by o.id
                """)
        @Results(id = "orderAssociationViewMap", value = {
                @Result(property = "orderId", column = "order_id"),
                @Result(property = "remark", column = "order_remark"),
                @Result(property = "owner.id", column = "owner_id"),
                @Result(property = "owner.name", column = "owner_name"),
                @Result(property = "owner.phone", column = "owner_phone"),
                @Result(property = "owner.idCard", column = "owner_id_card"),
                @Result(property = "reviewer.id", column = "reviewer_id"),
                @Result(property = "reviewer.name", column = "reviewer_name"),
                @Result(property = "reviewer.phone", column = "reviewer_phone"),
                @Result(property = "reviewer.idCard", column = "reviewer_id_card")
        })
        List<OrderAssociationView> selectOrderAssociations();

        @Select("""
                select id,
                       name as display_name,
                       phone,
                       id_card
                from user_account
                where id = #{id}
                """)
        UserProjectionDto selectUserProjectionDto(@Param("id") Long id);

        @Select("""
                select id,
                       name as display_name,
                       phone,
                       id_card
                from user_account
                where id = #{id}
                """)
        PlainUserProjectionDto selectPlainProjectionDto(@Param("id") Long id);

        @Select("""
                select o.id as order_id,
                       owner.id as owner_id,
                       owner.name as owner_display_name,
                       owner.phone as owner_phone,
                       owner.id_card as owner_id_card,
                       reviewer.id as reviewer_id,
                       reviewer.name as reviewer_display_name,
                       reviewer.phone as reviewer_phone,
                       reviewer.id_card as reviewer_id_card
                from order_account o
                join user_account owner on o.user_id = owner.id
                join user_account reviewer on o.related_user_id = reviewer.id
                where o.deleted = 0
                order by o.id
                """)
        @Results(id = "orderNestedDtoMap", value = {
                @Result(property = "orderId", column = "order_id"),
                @Result(property = "participants.owner.id", column = "owner_id"),
                @Result(property = "participants.owner.displayName", column = "owner_display_name"),
                @Result(property = "participants.owner.phone", column = "owner_phone"),
                @Result(property = "participants.owner.idCard", column = "owner_id_card"),
                @Result(property = "participants.reviewer.id", column = "reviewer_id"),
                @Result(property = "participants.reviewer.displayName", column = "reviewer_display_name"),
                @Result(property = "participants.reviewer.phone", column = "reviewer_phone"),
                @Result(property = "participants.reviewer.idCard", column = "reviewer_id_card")
        })
        List<OrderNestedDto> selectNestedOrderAssociations();

        @Select("""
                select o.id as order_id,
                       o.remark as order_remark
                from order_account o
                where o.deleted = 0
                order by o.id
                """)
        @Results(id = "orderGroupDtoMap", value = {
                @Result(property = "orderId", column = "order_id"),
                @Result(property = "remark", column = "order_remark"),
                @Result(property = "participants", column = "order_id",
                        many = @Many(select = "selectParticipantsByOrderId"))
        })
        List<OrderGroupDto> selectOrderGroupsWithParticipants();

        @Select("""
                select o.id as order_id,
                       o.remark as order_remark,
                       owner.id as owner_id,
                       owner.name as owner_display_name,
                       owner.phone as owner_phone,
                       owner.id_card as owner_id_card,
                       reviewer.id as reviewer_id,
                       reviewer.name as reviewer_display_name,
                       reviewer.phone as reviewer_phone,
                       reviewer.id_card as reviewer_id_card
                from order_account o
                join user_account owner on o.user_id = owner.id
                join user_account reviewer on o.related_user_id = reviewer.id
                where o.deleted = 0
                order by o.id
                """)
        @Results(id = "complexMixedModeOrderDtoMap", value = {
                @Result(property = "orderId", column = "order_id"),
                @Result(property = "remark", column = "order_remark"),
                @Result(property = "participants.owner.id", column = "owner_id"),
                @Result(property = "participants.owner.displayName", column = "owner_display_name"),
                @Result(property = "participants.owner.phone", column = "owner_phone"),
                @Result(property = "participants.owner.idCard", column = "owner_id_card"),
                @Result(property = "participants.reviewer.id", column = "reviewer_id"),
                @Result(property = "participants.reviewer.displayName", column = "reviewer_display_name"),
                @Result(property = "participants.reviewer.phone", column = "reviewer_phone"),
                @Result(property = "participants.reviewer.idCard", column = "reviewer_id_card"),
                @Result(property = "participantList", column = "order_id",
                        many = @Many(select = "selectParticipantsByOrderId"))
        })
        List<ComplexMixedModeOrderDto> selectComplexMixedModeOrders();

        @Select("""
                select o.id as order_id,
                       o.remark as order_remark,
                       owner.id as owner_id,
                       owner.name as owner_display_name,
                       owner.phone as owner_phone,
                       owner.id_card as owner_id_card,
                       reviewer.id as reviewer_id,
                       reviewer.name as reviewer_display_name,
                       reviewer.phone as reviewer_phone,
                       reviewer.id_card as reviewer_id_card
                from order_account o
                join user_account owner on o.user_id = owner.id
                join user_account reviewer on o.related_user_id = reviewer.id
                where o.deleted = 0
                order by o.id
                """)
        @Results(id = "problematicDashboardMap", value = {
                @Result(property = "orderId", column = "order_id"),
                @Result(property = "remark", column = "order_remark"),
                @Result(property = "participants.owner.id", column = "owner_id"),
                @Result(property = "participants.owner.displayName", column = "owner_display_name"),
                @Result(property = "participants.owner.phone", column = "owner_phone"),
                @Result(property = "participants.owner.idCard", column = "owner_id_card"),
                @Result(property = "participants.reviewer.id", column = "reviewer_id"),
                @Result(property = "participants.reviewer.displayName", column = "reviewer_display_name"),
                @Result(property = "participants.reviewer.phone", column = "reviewer_phone"),
                @Result(property = "participants.reviewer.idCard", column = "reviewer_id_card"),
                @Result(property = "participantList", column = "order_id",
                        many = @Many(select = "selectDangerousParticipantsByOrderId"))
        })
        List<ProblematicDashboardDto> selectProblematicDashboards();

        @Select("""
                select t.id,
                       t.display_name,
                       t.phone,
                       t.id_card
                from (
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card
                    from user_account u
                    where u.phone = #{phoneEq}
                      and exists (
                          select 1
                          from order_account o
                          where o.user_id = u.id
                            and o.id in (
                                select p.order_id
                                from order_participant p
                                where p.seq_no = 1
                            )
                      )
                    union
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card
                    from user_account u
                    where u.id_card = #{idCardEq}
                      and u.id in (
                          select o.related_user_id
                          from order_account o
                          where o.related_user_id is not null
                            and o.id in (
                                select p.order_id
                                from order_participant p
                                where p.seq_no = 2
                            )
                      )
                    union
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card
                    from user_account u
                    where u.phone like #{phoneLike}
                      and u.id_card like #{idCardLike}
                      and exists (
                          select 1
                          from order_account o
                          where o.user_id = u.id
                            and exists (
                                select 1
                                from order_participant p
                                where p.order_id = o.id
                                  and p.user_id = u.id
                            )
                      )
                ) t
                order by t.id
                """)
        List<UserProjectionDto> selectByMultiUnionNestedSubqueryAcrossStorageModes(
                @Param("phoneEq") String phoneEq,
                @Param("idCardEq") String idCardEq,
                @Param("phoneLike") String phoneLike,
                @Param("idCardLike") String idCardLike
        );

        @Select("""
                select h.id,
                       h.display_name,
                       h.phone,
                       h.id_card
                from (
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card,
                           o.created_seq as sort_no
                    from user_account u
                    join order_account o on o.user_id = u.id
                    where u.phone = #{phoneEq}
                      and u.id in (
                          select p.user_id
                          from order_participant p
                          where p.seq_no = 1
                            and p.order_id in (
                                select oa.id
                                from order_account oa
                                where oa.deleted = 0
                            )
                      )
                    union
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card,
                           o.created_seq as sort_no
                    from user_account u
                    join order_account o on o.related_user_id = u.id
                    where u.id_card = #{idCardEq}
                      and o.id in (
                          select p.order_id
                          from order_participant p
                          where p.seq_no = 2
                      )
                      and u.id in (
                          select o2.related_user_id
                          from order_account o2
                          where o2.id in (
                              select p2.order_id
                              from order_participant p2
                              where p2.seq_no = 2
                          )
                      )
                    union
                    select u.id as id,
                           u.name as display_name,
                           u.phone as phone,
                           u.id_card as id_card,
                           o.created_seq as sort_no
                    from user_account u
                    join order_account o on o.user_id = u.id
                    where u.phone like #{phoneLike}
                      and u.id_card like #{idCardLike}
                      and exists (
                          select 1
                          from order_participant p
                          where p.order_id = o.id
                            and p.user_id = u.id
                      )
                ) h
                order by h.sort_no asc
                """)
        List<UserProjectionDto> selectByWrappedMultiUnionNestedSubqueryAcrossStorageModes(
                @Param("phoneEq") String phoneEq,
                @Param("idCardEq") String idCardEq,
                @Param("phoneLike") String phoneLike,
                @Param("idCardLike") String idCardLike
        );

        @Select("""
                select u.id,
                       u.name as display_name,
                       u.phone_cipher as phone,
                       u.id_card
                from order_participant op
                join user_account u on op.user_id = u.id
                where op.order_id = #{orderId}
                order by op.seq_no
                """)
        List<UserProjectionDto> selectParticipantsByOrderId(@Param("orderId") Long orderId);

        @Select("""
                select u.id,
                       u.name as display_name,
                       u.phone_cipher as phone,
                       u.id_card
                from order_participant op
                join user_account u on op.user_id = u.id
                where op.order_id = #{orderId}
                order by op.seq_no
                """)
        List<DangerousUserProjectionDto> selectDangerousParticipantsByOrderId(@Param("orderId") Long orderId);

        @Update("""
                update user_account
                set id_card = #{idCard}
                where id = #{id}
                """)
        int updateIdCard(UserRecord user);

        @Update("""
                update user_account
                set phone = #{phone}, id_card = #{idCard}
                where id = #{id}
                """)
        int updatePhoneAndIdCard(UserRecord user);
    }

    @EncryptTable("user_account")
    static class UserRecord {

        private Long id;
        private String name;

        @EncryptField(
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;

        @EncryptField(
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class OrderAssociationView {

        private Long orderId;
        private String remark;
        private UserAssociationDto owner;
        private UserAssociationDto reviewer;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public UserAssociationDto getOwner() {
            return owner;
        }

        public void setOwner(UserAssociationDto owner) {
            this.owner = owner;
        }

        public UserAssociationDto getReviewer() {
            return reviewer;
        }

        public void setReviewer(UserAssociationDto reviewer) {
            this.reviewer = reviewer;
        }
    }

    static class UserAssociationDto {

        private Long id;
        private String name;

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;

        @EncryptField(
                table = "user_account",
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class UserProjectionDto {

        private Long id;
        private String displayName;

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;

        @EncryptField(
                table = "user_account",
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class PlainUserProjectionDto {

        private Long id;
        private String displayName;
        private String phone;
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }
    }

    static class OrderNestedDto {

        private Long orderId;
        private ParticipantBundleDto participants;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public ParticipantBundleDto getParticipants() {
            return participants;
        }

        public void setParticipants(ParticipantBundleDto participants) {
            this.participants = participants;
        }
    }

    static class ParticipantBundleDto {

        private UserProjectionDto owner;
        private UserProjectionDto reviewer;

        public UserProjectionDto getOwner() {
            return owner;
        }

        public void setOwner(UserProjectionDto owner) {
            this.owner = owner;
        }

        public UserProjectionDto getReviewer() {
            return reviewer;
        }

        public void setReviewer(UserProjectionDto reviewer) {
            this.reviewer = reviewer;
        }
    }

    static class OrderGroupDto {

        private Long orderId;
        private String remark;
        private List<UserProjectionDto> participants;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public List<UserProjectionDto> getParticipants() {
            return participants;
        }

        public void setParticipants(List<UserProjectionDto> participants) {
            this.participants = participants;
        }
    }

    static class ComplexMixedModeOrderDto {

        private Long orderId;
        private String remark;
        private ParticipantBundleDto participants;
        private List<UserProjectionDto> participantList;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public ParticipantBundleDto getParticipants() {
            return participants;
        }

        public void setParticipants(ParticipantBundleDto participants) {
            this.participants = participants;
        }

        public List<UserProjectionDto> getParticipantList() {
            return participantList;
        }

        public void setParticipantList(List<UserProjectionDto> participantList) {
            this.participantList = participantList;
        }
    }

    static class ProblematicDashboardDto {

        private Long orderId;
        private String remark;
        private ProblematicParticipantBundleDto participants;
        private List<DangerousUserProjectionDto> participantList;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public ProblematicParticipantBundleDto getParticipants() {
            return participants;
        }

        public void setParticipants(ProblematicParticipantBundleDto participants) {
            this.participants = participants;
        }

        public List<DangerousUserProjectionDto> getParticipantList() {
            return participantList;
        }

        public void setParticipantList(List<DangerousUserProjectionDto> participantList) {
            this.participantList = participantList;
        }

        public String getDashboardSummary() {
            throw new IllegalStateException("unmapped dashboard getter should not be touched");
        }
    }

    static class ProblematicParticipantBundleDto {

        private DangerousUserProjectionDto owner;
        private DangerousUserProjectionDto reviewer;

        public DangerousUserProjectionDto getOwner() {
            return owner;
        }

        public void setOwner(DangerousUserProjectionDto owner) {
            this.owner = owner;
        }

        public DangerousUserProjectionDto getReviewer() {
            return reviewer;
        }

        public void setReviewer(DangerousUserProjectionDto reviewer) {
            this.reviewer = reviewer;
        }

        public String getParticipantDigest() {
            throw new IllegalStateException("unmapped participant bundle getter should not be touched");
        }
    }

    static class DangerousUserProjectionDto {

        private Long id;
        private String displayName;

        @EncryptField(
                table = "user_account",
                column = "phone",
                storageColumn = "phone_cipher",
                assistedQueryColumn = "phone_hash",
                likeQueryColumn = "phone_like"
        )
        private String phone;

        @EncryptField(
                table = "user_account",
                column = "id_card",
                storageMode = FieldStorageMode.SEPARATE_TABLE,
                storageTable = "user_id_card_encrypt",
                storageColumn = "id_card_cipher",
                storageIdColumn = "id",
                assistedQueryColumn = "id_card_hash",
                likeQueryColumn = "id_card_like"
        )
        private String idCard;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }

        public String getDerivedMask() {
            throw new IllegalStateException("unmapped user projection getter should not be touched");
        }
    }

    static class TrackingSeparateTableEncryptionManager extends SeparateTableEncryptionManager {

        private int prepareWriteReferencesCalls;

        TrackingSeparateTableEncryptionManager(JdbcDataSource dataSource,
                                               EncryptMetadataRegistry metadataRegistry,
                                               AlgorithmRegistry algorithmRegistry,
                                               DatabaseEncryptionProperties properties) {
            super(dataSource, metadataRegistry, algorithmRegistry, properties);
        }

        @Override
        public void prepareWriteReferences(org.apache.ibatis.mapping.MappedStatement mappedStatement,
                                           BoundSql boundSql,
                                           org.apache.ibatis.executor.Executor executor) {
            prepareWriteReferencesCalls++;
            super.prepareWriteReferences(mappedStatement, boundSql, executor);
        }
    }

    static class StatementCapture {

        private final String sql;
        private final List<Object> parameters;
        private final Object parameterObject;

        StatementCapture(String sql, List<Object> parameters, Object parameterObject) {
            this.sql = sql;
            this.parameters = parameters;
            this.parameterObject = parameterObject;
        }
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
    })
    static class ParameterCaptureInterceptor implements Interceptor {

        private final ParameterValueResolver parameterValueResolver = new ParameterValueResolver();
        private Object lastIdCardAdditionalParameter;
        private String lastPreparedSql;
        private List<Object> lastResolvedParameters = List.of();
        private final List<StatementCapture> preparedStatements = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (invocation.getTarget() instanceof StatementHandler statementHandler) {
                BoundSql boundSql = statementHandler.getBoundSql();
                lastPreparedSql = boundSql.getSql();
                lastResolvedParameters = resolveParameterValues(statementHandler, boundSql);
                preparedStatements.add(new StatementCapture(
                        boundSql.getSql(),
                        lastResolvedParameters,
                        boundSql.getParameterObject()
                ));
                if (boundSql.hasAdditionalParameter("idCard")) {
                    lastIdCardAdditionalParameter = boundSql.getAdditionalParameter("idCard");
                }
                if (boundSql.hasAdditionalParameter("__encrypt_prepared_refs")) {
                    Object preparedReferences = boundSql.getAdditionalParameter("__encrypt_prepared_refs");
                    if (preparedReferences instanceof Map<?, ?> references && !references.isEmpty()) {
                        lastIdCardAdditionalParameter = references.values().iterator().next();
                    }
                }
            }
            return result;
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(java.util.Properties properties) {
        }

        private List<Object> resolveParameterValues(StatementHandler statementHandler, BoundSql boundSql) {
            MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
            Configuration configuration = (Configuration) metaObject.getValue("delegate.configuration");
            Object parameterObject = boundSql.getParameterObject();
            if (configuration == null) {
                return List.of();
            }
            java.util.ArrayList<Object> values = new java.util.ArrayList<>(boundSql.getParameterMappings().size());
            for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                values.add(parameterValueResolver.resolve(configuration, boundSql, parameterObject, parameterMapping));
            }
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }

        private StatementCapture findPreparedStatementContaining(String sqlFragment) {
            String normalizedFragment = sqlFragment.toLowerCase(Locale.ROOT);
            for (StatementCapture statementCapture : preparedStatements) {
                String normalizedSql = statementCapture.sql == null
                        ? ""
                        : statementCapture.sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                if (normalizedSql.contains(normalizedFragment)) {
                    return statementCapture;
                }
            }
            return null;
        }

        private void reset() {
            lastIdCardAdditionalParameter = null;
            lastPreparedSql = null;
            lastResolvedParameters = List.of();
            preparedStatements.clear();
        }
    }
}
