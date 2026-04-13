package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Stores task progress as human-readable properties files.
 */
public class FileMigrationStateStore implements MigrationStateStore {

    private final Path directory;

    /**
     * 文件类型迁移状态存储器
     * @param directory 存储目录地址
     */
    public FileMigrationStateStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<MigrationState> load(EntityMigrationPlan plan) {
        Path file = fileOf(plan);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
            MigrationState state = new MigrationState();
            state.setEntityName(properties.getProperty("entityName"));
            state.setTableName(properties.getProperty("tableName"));
            state.setIdColumn(properties.getProperty("idColumn"));
            state.setIdJavaType(properties.getProperty("idJavaType"));
            state.setStatus(MigrationStatus.valueOf(properties.getProperty("status", MigrationStatus.READY.name())));
            state.setTotalRows(parseLong(properties.getProperty("totalRows")));
            state.setRangeStart(properties.getProperty("rangeStart"));
            state.setRangeEnd(properties.getProperty("rangeEnd"));
            state.setLastProcessedId(properties.getProperty("lastProcessedId"));
            state.setScannedRows(parseLong(properties.getProperty("scannedRows")));
            state.setMigratedRows(parseLong(properties.getProperty("migratedRows")));
            state.setSkippedRows(parseLong(properties.getProperty("skippedRows")));
            state.setVerifiedRows(parseLong(properties.getProperty("verifiedRows")));
            state.setVerificationEnabled(Boolean.parseBoolean(properties.getProperty("verificationEnabled", "false")));
            state.setLastError(properties.getProperty("lastError"));
            return Optional.of(state);
        } catch (IOException ex) {
            throw new MigrationException("Failed to load migration state file: " + file, ex);
        }
    }

    @Override
    public void save(EntityMigrationPlan plan, MigrationState state) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new MigrationException("Failed to create migration state directory: " + directory, ex);
        }
        Properties properties = new Properties();
        writeIfPresent(properties, "entityName", state.getEntityName());
        writeIfPresent(properties, "tableName", state.getTableName());
        writeIfPresent(properties, "idColumn", state.getIdColumn());
        writeIfPresent(properties, "idJavaType", state.getIdJavaType());
        properties.setProperty("status", state.getStatus().name());
        properties.setProperty("totalRows", Long.toString(state.getTotalRows()));
        writeIfPresent(properties, "rangeStart", state.getRangeStart());
        writeIfPresent(properties, "rangeEnd", state.getRangeEnd());
        writeIfPresent(properties, "lastProcessedId", state.getLastProcessedId());
        properties.setProperty("scannedRows", Long.toString(state.getScannedRows()));
        properties.setProperty("migratedRows", Long.toString(state.getMigratedRows()));
        properties.setProperty("skippedRows", Long.toString(state.getSkippedRows()));
        properties.setProperty("verifiedRows", Long.toString(state.getVerifiedRows()));
        properties.setProperty("verificationEnabled", Boolean.toString(state.isVerificationEnabled()));
        writeIfPresent(properties, "lastError", state.getLastError());
        try (OutputStream outputStream = Files.newOutputStream(fileOf(plan))) {
            properties.store(outputStream, "mybatis-like-sharephere-support migration state");
        } catch (IOException ex) {
            throw new MigrationException("Failed to save migration state for table: " + plan.getTableName(), ex);
        }
    }

    private Path fileOf(EntityMigrationPlan plan) {
        String name = sanitize(plan.getEntityType().getName()) + "__" + sanitize(plan.getTableName()) + ".properties";
        return directory.resolve(name);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void writeIfPresent(Properties properties, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            properties.setProperty(key, value);
        }
    }

    private long parseLong(String value) {
        return StringUtils.isBlank(value) ? 0L : Long.parseLong(value);
    }
}
