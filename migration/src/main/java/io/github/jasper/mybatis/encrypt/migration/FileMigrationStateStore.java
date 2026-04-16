package io.github.jasper.mybatis.encrypt.migration;

import io.github.jasper.mybatis.encrypt.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
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
            state.setDataSourceName(properties.getProperty("dataSourceName"));
            state.setEntityName(properties.getProperty("entityName"));
            state.setTableName(properties.getProperty("tableName"));
            state.setCursorColumns(readIndexedList(properties, "cursorColumns",
                    firstNonBlank(properties.getProperty("cursorColumn"), properties.getProperty("idColumn"))));
            state.setCursorJavaTypes(readIndexedList(properties, "cursorJavaTypes",
                    firstNonBlank(properties.getProperty("cursorJavaType"), properties.getProperty("idJavaType"))));
            state.setStatus(MigrationStatus.valueOf(properties.getProperty("status", MigrationStatus.READY.name())));
            state.setTotalRows(parseLong(properties.getProperty("totalRows")));
            state.setRangeStartValues(readIndexedList(properties, "rangeStartValues", properties.getProperty("rangeStart")));
            state.setRangeEndValues(readIndexedList(properties, "rangeEndValues", properties.getProperty("rangeEnd")));
            state.setLastProcessedCursorValues(readIndexedList(properties, "lastProcessedCursorValues",
                    firstNonBlank(properties.getProperty("lastProcessedCursor"), properties.getProperty("lastProcessedId"))));
            state.setScannedRows(parseLong(properties.getProperty("scannedRows")));
            state.setMigratedRows(parseLong(properties.getProperty("migratedRows")));
            state.setSkippedRows(parseLong(properties.getProperty("skippedRows")));
            state.setVerifiedRows(parseLong(properties.getProperty("verifiedRows")));
            state.setVerificationEnabled(Boolean.parseBoolean(properties.getProperty("verificationEnabled", "false")));
            state.setLastError(properties.getProperty("lastError"));
            return Optional.of(state);
        } catch (IllegalArgumentException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_DATA_INVALID,
                    "Failed to parse migration state file: " + file, ex);
        } catch (IOException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                    "Failed to load migration state file: " + file, ex);
        }
    }

    @Override
    public void save(EntityMigrationPlan plan, MigrationState state) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                    "Failed to create migration state directory: " + directory, ex);
        }
        Properties properties = new Properties();
        writeIfPresent(properties, "dataSourceName", state.getDataSourceName());
        writeIfPresent(properties, "entityName", state.getEntityName());
        writeIfPresent(properties, "tableName", state.getTableName());
        writeIndexedList(properties, "cursorColumns", state.getCursorColumns());
        writeIndexedList(properties, "cursorJavaTypes", state.getCursorJavaTypes());
        if (state.getCursorColumns().size() == 1) {
            writeIfPresent(properties, "cursorColumn", state.getCursorColumn());
            writeIfPresent(properties, "idColumn", state.getCursorColumn());
        }
        if (state.getCursorJavaTypes().size() == 1) {
            writeIfPresent(properties, "cursorJavaType", state.getCursorJavaType());
            writeIfPresent(properties, "idJavaType", state.getCursorJavaType());
        }
        properties.setProperty("status", state.getStatus().name());
        properties.setProperty("totalRows", Long.toString(state.getTotalRows()));
        writeIndexedList(properties, "rangeStartValues", state.getRangeStartValues());
        writeIndexedList(properties, "rangeEndValues", state.getRangeEndValues());
        writeIndexedList(properties, "lastProcessedCursorValues", state.getLastProcessedCursorValues());
        if (state.getRangeStartValues().size() == 1) {
            writeIfPresent(properties, "rangeStart", state.getRangeStart());
        }
        if (state.getRangeEndValues().size() == 1) {
            writeIfPresent(properties, "rangeEnd", state.getRangeEnd());
        }
        if (state.getLastProcessedCursorValues().size() == 1) {
            writeIfPresent(properties, "lastProcessedCursor", state.getLastProcessedCursor());
            writeIfPresent(properties, "lastProcessedId", state.getLastProcessedCursor());
        }
        properties.setProperty("scannedRows", Long.toString(state.getScannedRows()));
        properties.setProperty("migratedRows", Long.toString(state.getMigratedRows()));
        properties.setProperty("skippedRows", Long.toString(state.getSkippedRows()));
        properties.setProperty("verifiedRows", Long.toString(state.getVerifiedRows()));
        properties.setProperty("verificationEnabled", Boolean.toString(state.isVerificationEnabled()));
        writeIfPresent(properties, "lastError", state.getLastError());
        Path targetFile = fileOf(plan);
        Path tempFile = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
        try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
            properties.store(outputStream, "mybatis-like-sharephere-support migration state");
            outputStream.flush();
        } catch (IOException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                    "Failed to save migration state for table: " + plan.getTableName(), ex);
        }
        try {
            Files.move(tempFile, targetFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            try {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                        "Failed to save migration state for table: " + plan.getTableName(), moveEx);
            }
        }
    }

    @Override
    public MigrationCheckpointLock acquireCheckpointLock(EntityMigrationPlan plan) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                    "Failed to create migration state directory: " + directory, ex);
        }
        Path lockFile = lockFileOf(plan);
        try {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock;
            try {
                fileLock = channel.tryLock();
            } catch (OverlappingFileLockException ex) {
                closeQuietly(channel);
                throw new MigrationCheckpointLockException(MigrationErrorCode.CHECKPOINT_LOCKED,
                        "Migration checkpoint lock is already held for task: " + lockFile, ex);
            }
            if (fileLock == null) {
                closeQuietly(channel);
                throw new MigrationCheckpointLockException(MigrationErrorCode.CHECKPOINT_LOCKED,
                        "Migration checkpoint lock is already held for task: " + lockFile);
            }
            return new FileCheckpointLock(lockFile, channel, fileLock);
        } catch (MigrationCheckpointLockException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new MigrationStateStoreException(MigrationErrorCode.STATE_STORE_IO_FAILED,
                    "Failed to acquire migration checkpoint lock for table: " + plan.getTableName(), ex);
        }
    }

    private Path fileOf(EntityMigrationPlan plan) {
        StringBuilder name = new StringBuilder();
        if (StringUtils.isNotBlank(plan.getDataSourceName())) {
            name.append(sanitize(plan.getDataSourceName())).append("__");
        }
        name.append(sanitize(plan.getEntityName())).append("__").append(sanitize(plan.getTableName())).append(".properties");
        return directory.resolve(name.toString());
    }

    private Path lockFileOf(EntityMigrationPlan plan) {
        StringBuilder name = new StringBuilder();
        if (StringUtils.isNotBlank(plan.getDataSourceName())) {
            name.append(sanitize(plan.getDataSourceName())).append("__");
        }
        name.append(sanitize(plan.getEntityName())).append("__").append(sanitize(plan.getTableName())).append(".lock");
        return directory.resolve(name.toString());
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private void writeIfPresent(Properties properties, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            properties.setProperty(key, value);
        }
    }

    private java.util.List<String> readIndexedList(Properties properties, String prefix, String fallbackValue) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int index = 0; ; index++) {
            String value = properties.getProperty(prefix + "." + index);
            if (value == null && !properties.containsKey(prefix + "." + index)) {
                break;
            }
            values.add(value);
        }
        if (!values.isEmpty()) {
            return values;
        }
        if (fallbackValue == null) {
            return java.util.Collections.emptyList();
        }
        values.add(fallbackValue);
        return values;
    }

    private void writeIndexedList(Properties properties, String prefix, java.util.List<String> values) {
        if (values == null) {
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value != null) {
                properties.setProperty(prefix + "." + index, value);
            }
        }
    }

    private long parseLong(String value) {
        return StringUtils.isBlank(value) ? 0L : Long.parseLong(value);
    }

    private void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignore) {
        }
    }

    private static final class FileCheckpointLock implements MigrationCheckpointLock {

        private final Path lockFile;
        private final FileChannel channel;
        private final FileLock fileLock;
        private boolean closed;

        private FileCheckpointLock(Path lockFile, FileChannel channel, FileLock fileLock) {
            this.lockFile = lockFile;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                fileLock.release();
            } catch (IOException ignore) {
            } finally {
                try {
                    channel.close();
                } catch (IOException ignore) {
                }
                try {
                    Files.deleteIfExists(lockFile);
                } catch (IOException ignore) {
                }
            }
        }
    }
}
