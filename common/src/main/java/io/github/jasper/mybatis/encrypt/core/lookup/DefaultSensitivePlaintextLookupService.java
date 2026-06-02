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

    public DefaultSensitivePlaintextLookupService(Map<String, DataSource> dataSources,
                                                  EncryptMetadataRegistry metadataRegistry,
                                                  AlgorithmRegistry algorithmRegistry,
                                                  DatabaseEncryptionProperties properties,
                                                  SensitivePlaintextAuditRecorder auditRecorder) {
        this.dataSources = dataSources == null ? Collections.<String, DataSource>emptyMap()
                : new LinkedHashMap<String, DataSource>(dataSources);
        this.metadataRegistry = metadataRegistry;
        this.algorithmRegistry = algorithmRegistry;
        this.properties = properties == null ? new DatabaseEncryptionProperties() : properties;
        this.auditRecorder = auditRecorder == null ? SensitivePlaintextAuditRecorder.noOp() : auditRecorder;
    }

    @Override
    public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        try {
            validateLookupMeta(lookupMeta);
            requireSingleDataSource();
            EncryptColumnRule rule = resolveRule(lookupMeta);
            String plaintext = queryAndDecrypt(rule, lookupMeta);
            auditRecorder.recordSuccess(lookupMeta);
            return plaintext;
        } catch (EncryptionException ex) {
            auditRecorder.recordFailure(lookupMeta, ex.getErrorCode().name());
            throw ex;
        }
    }

    private void validateLookupMeta(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        if (lookupMeta == null
                || StringUtils.isBlank(lookupMeta.sid())
                || StringUtils.isBlank(lookupMeta.pid())
                || StringUtils.isBlank(lookupMeta.vid())
                || StringUtils.isBlank(lookupMeta.hash())) {
            throw new EncryptionException(EncryptionErrorCode.INVALID_FIELD_RULE,
                    "Incomplete sensitive lookup meta.");
        }
    }

    private EncryptColumnRule resolveRule(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
        for (String tableName : metadataRegistry.getRegisteredTableNames()) {
            io.github.jasper.mybatis.encrypt.core.metadata.EncryptTableRule tableRule =
                    metadataRegistry.findByTable(tableName).orElse(null);
            EncryptColumnRule matched = tableRule == null ? null : tableRule.getColumnRules().stream()
                    .filter(rule -> lookupMeta.sid().equals(rule.sidCode()) && lookupMeta.pid().equals(rule.pidCode()))
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
        }
        throw new EncryptionException(EncryptionErrorCode.INVALID_FIELD_RULE,
                "No encryption rule matched the provided lookup meta.");
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
            statement.setString(1, lookupMeta.hash());
            statement.setObject(2, lookupMeta.vid());
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
            mainStatement.setObject(1, lookupMeta.vid());
            mainStatement.setString(2, lookupMeta.hash());
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
                separateStatement.setString(1, lookupMeta.hash());
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
