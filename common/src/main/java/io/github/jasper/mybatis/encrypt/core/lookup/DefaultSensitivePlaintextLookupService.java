package io.github.jasper.mybatis.encrypt.core.lookup;

import io.github.jasper.mybatis.encrypt.algorithm.AlgorithmRegistry;
import io.github.jasper.mybatis.encrypt.config.DatabaseEncryptionProperties;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptColumnRule;
import io.github.jasper.mybatis.encrypt.core.metadata.EncryptMetadataRegistry;
import io.github.jasper.mybatis.encrypt.exception.EncryptionErrorCode;
import io.github.jasper.mybatis.encrypt.exception.EncryptionException;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default explicit plaintext lookup service.
 */
public class DefaultSensitivePlaintextLookupService implements SensitivePlaintextLookupService {

    private final Map<String, DataSource> dataSources;
    private final EncryptMetadataRegistry metadataRegistry;
    private final AlgorithmRegistry algorithmRegistry;
    private final DatabaseEncryptionProperties properties;
    private final SensitivePlaintextAuditRecorder auditRecorder;

    /**
     * Creates the default lookup service.
     *
     * <p>The current implementation intentionally supports a single datasource only. Multiple
     * datasource routing is rejected at lookup time instead of guessing which datasource owns a
     * lookup meta payload.</p>
     *
     * @param dataSources datasource beans keyed by bean name
     * @param metadataRegistry encryption metadata used to resolve {@code sid/pid} to a field rule
     * @param algorithmRegistry algorithm registry used to decrypt the resolved ciphertext
     * @param properties encryption properties, including SQL dialect quoting rules
     * @param auditRecorder audit hook for explicit {@link #lookup(SensitiveDataContext.SensitiveLookupMeta)} calls
     */
    public DefaultSensitivePlaintextLookupService(Map<String, DataSource> dataSources,
                                                  EncryptMetadataRegistry metadataRegistry,
                                                  AlgorithmRegistry algorithmRegistry,
                                                  DatabaseEncryptionProperties properties,
                                                  SensitivePlaintextAuditRecorder auditRecorder) {
        this.dataSources = dataSources == null ? Collections.emptyMap()
                : new LinkedHashMap<>(dataSources);
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        this.auditRecorder = auditRecorder == null ? SensitivePlaintextAuditRecorder.noOp() : auditRecorder;
    }

    @Override
    public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        return lookup(lookupMeta, Collections.emptyMap());
    }

    @Override
    public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta, Map<String, Object> attributes) {
        EncryptColumnRule rule = null;
        try {
            validateLookupMeta(lookupMeta);
            rule = resolveRule(lookupMeta);
            requireSingleDataSource();
            String plaintext = queryAndDecrypt(rule, lookupMeta);
            recordAudit(buildAuditEvent(true, lookupMeta, rule, plaintext, null, attributes));
            return plaintext;
        } catch (EncryptionException ex) {
            recordAudit(buildAuditEvent(false, lookupMeta, rule, null, ex.getErrorCode().name(), attributes));
            throw ex;
        }
    }

    @Override
    public String lookupInternal(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        validateLookupMeta(lookupMeta);
        String recordedPlaintext = SensitiveDataContext.findRecordedPlaintext(lookupMeta);
        if (recordedPlaintext != null) {
            return recordedPlaintext;
        }
        requireSingleDataSource();
        EncryptColumnRule rule = resolveRule(lookupMeta);
        return queryAndDecrypt(rule, lookupMeta);
    }

    private String lookupPlaintext(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        validateLookupMeta(lookupMeta);
        requireSingleDataSource();
        EncryptColumnRule rule = resolveRule(lookupMeta);
        return queryAndDecrypt(rule, lookupMeta);
    }

    private void validateLookupMeta(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        if (lookupMeta == null
                || StringUtils.isBlank(lookupMeta.getSid())
                || StringUtils.isBlank(lookupMeta.getPid())
                || StringUtils.isBlank(lookupMeta.getVid())
                || StringUtils.isBlank(lookupMeta.getHash())) {
            throw new EncryptionException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "Incomplete sensitive lookup meta.");
        }
    }

    private EncryptColumnRule resolveRule(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        for (String tableName : metadataRegistry.getRegisteredTableNames()) {
            io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule tableRule =
                    metadataRegistry.findByTable(tableName).orElse(null);
            EncryptColumnRule matched = tableRule == null ? null : tableRule.getColumnRules().stream()
                    .filter(rule -> lookupMeta.getSid().equals(rule.sidCode()) && lookupMeta.getPid().equals(rule.pidCode()))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        throw new EncryptionException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "No encryption rule matched the provided lookup meta.");
    }

    private void recordAudit(SensitivePlaintextAuditEvent baseEvent) {
        auditRecorder.record(baseEvent);
    }

    private SensitivePlaintextAuditEvent buildAuditEvent(boolean success,
                                                         SensitiveDataContext.SensitiveLookupMeta lookupMeta,
                                                         EncryptColumnRule rule,
                                                         String plaintext,
                                                         String errorCode,
                                                         Map<String, Object> attributes) {
        SensitivePlaintextAuditEvent.Builder builder = SensitivePlaintextAuditEvent.builder()
                .success(success)
                .lookupMeta(lookupMeta)
                .plaintext(plaintext)
                .errorCode(errorCode)
                .attributes(attributes);
        if (rule != null) {
            builder.tableName(rule.table())
                    .propertyName(rule.property())
                    .columnName(rule.column());
        }
        return builder.build();
    }

    private String queryAndDecrypt(EncryptColumnRule rule, SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        if (rule.isStoredInSeparateTable()) {
            return querySeparateTableAndDecrypt(rule, lookupMeta);
        }
        return querySameTableAndDecrypt(rule, lookupMeta);
    }

    private String querySameTableAndDecrypt(EncryptColumnRule rule, SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        String businessColumn = requireBusinessKeyColumn(rule);
        String sql = "select " + quote(rule.storageColumn())
                + " from " + quote(rule.table())
                + " where " + quote(rule.assistedQueryColumn()) + " = ?"
                + " and " + quote(businessColumn) + " = ?";
        try (Connection connection = resolveDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lookupMeta.getHash());
            statement.setObject(2, lookupMeta.getVid());
            return readSingleCipherAndDecrypt(rule, statement);
        } catch (SQLException ex) {
            throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                    "Failed to execute plaintext lookup.", ex);
        }
    }

    private String querySeparateTableAndDecrypt(EncryptColumnRule rule, SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        String businessColumn = requireBusinessKeyColumn(rule);
        String mainSql = "select " + quote(rule.column())
                + " from " + quote(rule.table())
                + " where " + quote(businessColumn) + " = ?"
                + " and " + quote(rule.column()) + " = ?";
        try (Connection connection = resolveDataSource().getConnection();
             PreparedStatement mainStatement = connection.prepareStatement(mainSql)) {
            mainStatement.setObject(1, lookupMeta.getVid());
            mainStatement.setString(2, lookupMeta.getHash());
            try (ResultSet mainResultSet = mainStatement.executeQuery()) {
                if (!mainResultSet.next()) {
                    throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                            "No plaintext record matched the provided lookup meta.");
                }
                if (mainResultSet.next()) {
                    throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                            "Lookup meta matched multiple plaintext records.");
                }
            }
            String separateSql = "select " + quote(rule.storageColumn())
                    + " from " + quote(rule.storageTable())
                    + " where " + quote(rule.assistedQueryColumn()) + " = ?";
            try (PreparedStatement separateStatement = connection.prepareStatement(separateSql)) {
                separateStatement.setString(1, lookupMeta.getHash());
                return readSingleCipherAndDecrypt(rule, separateStatement);
            }
        } catch (SQLException ex) {
            throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                    "Failed to execute plaintext lookup.", ex);
        }
    }

    private String readSingleCipherAndDecrypt(EncryptColumnRule rule, PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                        "No plaintext record matched the provided lookup meta.");
            }
            String cipher = resultSet.getString(1);
            if (resultSet.next()) {
                throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                        "Lookup meta matched multiple plaintext records.");
            }
            return algorithmRegistry.cipher(rule.cipherAlgorithm()).decrypt(cipher);
        }
    }

    private String requireBusinessKeyColumn(EncryptColumnRule rule) {
        if (rule == null || StringUtils.isBlank(rule.lookupBusinessKeyColumn())) {
            throw new EncryptionException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "Lookup business key column is unresolved.");
        }
        return rule.lookupBusinessKeyColumn();
    }

    private DataSource resolveDataSource() {
        requireSingleDataSource();
        return dataSources.values().iterator().next();
    }

    private void requireSingleDataSource() {
        if (dataSources.size() > 1) {
            throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                    "Multiple datasources are not yet supported for plaintext lookup.");
        }
        if (dataSources.isEmpty()) {
            throw new EncryptionException(EncryptionErrorCode.GENERAL_FAILURE,
                    "No datasource available for plaintext lookup.");
        }
    }

    private String quote(String identifier) {
        return properties.getSqlDialect().quote(identifier);
    }
}
