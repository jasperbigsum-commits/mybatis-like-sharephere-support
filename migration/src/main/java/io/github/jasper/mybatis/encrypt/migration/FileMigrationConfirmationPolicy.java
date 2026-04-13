package io.github.jasper.mybatis.encrypt.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * File-based operator confirmation. The first run writes a template and blocks execution.
 */
public class FileMigrationConfirmationPolicy implements MigrationConfirmationPolicy {

    private final Path directory;

    /**
     * 文件类型迁移确认策略
     * @param directory 目录路径
     */
    public FileMigrationConfirmationPolicy(Path directory) {
        this.directory = directory;
    }

    @Override
    public void confirm(EntityMigrationPlan plan, MigrationRiskManifest manifest) {
        Path file = confirmationFile(plan);
        if (!Files.exists(file)) {
            writeTemplate(file, manifest, false);
            throw new MigrationException("Migration confirmation file created. Review and set approved=true before retry: "
                    + file);
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
        } catch (IOException ex) {
            throw new MigrationException("Failed to read migration confirmation file: " + file, ex);
        }
        Set<String> expectedEntries = new LinkedHashSet<String>();
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            expectedEntries.add(entry.asToken());
        }
        Set<String> configuredEntries = new LinkedHashSet<String>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("entry.")) {
                configuredEntries.add(properties.getProperty(name));
            }
        }
        if (!expectedEntries.equals(configuredEntries)) {
            writeTemplate(file, manifest, Boolean.parseBoolean(properties.getProperty("approved", "false")));
            throw new MigrationException("Migration confirmation file does not match actual mutation scope: " + file);
        }
        if (!Boolean.parseBoolean(properties.getProperty("approved", "false"))) {
            throw new MigrationException("Migration confirmation file exists but approved=false: " + file);
        }
    }

    private void writeTemplate(Path file, MigrationRiskManifest manifest, boolean approved) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new MigrationException("Failed to create migration confirmation directory: " + directory, ex);
        }
        Properties properties = new Properties();
        properties.setProperty("approved", Boolean.toString(approved));
        properties.setProperty("entityName", manifest.getEntityName());
        properties.setProperty("tableName", manifest.getTableName());
        int index = 1;
        for (MigrationRiskEntry entry : manifest.getEntries()) {
            properties.setProperty("entry." + index++, entry.asToken());
        }
        try (OutputStream outputStream = Files.newOutputStream(file)) {
            properties.store(outputStream, "Review mutation scope and set approved=true to continue");
        } catch (IOException ex) {
            throw new MigrationException("Failed to write migration confirmation file: " + file, ex);
        }
    }

    private Path confirmationFile(EntityMigrationPlan plan) {
        String fileName = plan.getEntityType().getName().replaceAll("[^a-zA-Z0-9._-]", "_")
                + "__" + plan.getTableName().replaceAll("[^a-zA-Z0-9._-]", "_")
                + ".confirm.properties";
        return directory.resolve(fileName);
    }
}
