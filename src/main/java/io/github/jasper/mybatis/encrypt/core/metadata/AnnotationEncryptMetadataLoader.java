package io.github.jasper.mybatis.encrypt.core.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.util.NameUtils;

/**
 * Loads encryption metadata from entity annotations.
 *
 * <p>Column resolution order for {@link EncryptField#column()} is:
 * explicit {@code @EncryptField.column}, MyBatis-Plus {@code @TableField(value)},
 * JPA {@code @Column(name)}, then property-name snake_case.</p>
 */
public class AnnotationEncryptMetadataLoader {

    public EncryptTableRule load(Class<?> type) {
        EncryptTableRule rule = new EncryptTableRule(resolveTableName(type));
        boolean found = false;
        for (Field field : type.getDeclaredFields()) {
            EncryptField encryptField = field.getAnnotation(EncryptField.class);
            if (encryptField == null) {
                continue;
            }
            found = true;
            String property = field.getName();
            String column = blankToDefault(encryptField.column(), resolveColumnName(field));
            String sourceIdColumn = blankToDefault(encryptField.sourceIdColumn(), inferSourceIdColumn(type, encryptField));
            String sourceIdProperty = blankToDefault(encryptField.sourceIdProperty(), inferSourceIdProperty(type, sourceIdColumn));
            rule.addColumnRule(new EncryptColumnRule(
                    property,
                    column,
                    encryptField.cipherAlgorithm(),
                    blankToNull(encryptField.assistedQueryColumn()),
                    blankToNull(encryptField.assistedQueryAlgorithm()),
                    blankToNull(encryptField.likeQueryColumn()),
                    blankToNull(encryptField.likeQueryAlgorithm()),
                    encryptField.storageMode(),
                    blankToNull(encryptField.storageTable()),
                    blankToDefault(encryptField.storageColumn(), column),
                    sourceIdProperty,
                    sourceIdColumn,
                    blankToDefault(encryptField.storageIdColumn(), sourceIdColumn)
            ));
        }
        return found ? rule : null;
    }

    private String resolveTableName(Class<?> type) {
        EncryptTable encryptTable = type.getAnnotation(EncryptTable.class);
        if (encryptTable != null && !encryptTable.value().isBlank()) {
            return encryptTable.value();
        }
        String tableName = firstNonBlank(
                extractAnnotationStringValue(type, "com.baomidou.mybatisplus.annotation.TableName", "value"),
                extractAnnotationStringValue(type, "jakarta.persistence.Table", "name"),
                extractAnnotationStringValue(type, "javax.persistence.Table", "name")
        );
        return tableName != null ? tableName : NameUtils.camelToSnake(type.getSimpleName());
    }

    private String resolveColumnName(Field field) {
        String resolved = firstNonBlank(
                extractAnnotationStringValue(field, "com.baomidou.mybatisplus.annotation.TableId", "value"),
                extractAnnotationStringValue(field, "com.baomidou.mybatisplus.annotation.TableField", "value"),
                extractAnnotationStringValue(field, "jakarta.persistence.Column", "name"),
                extractAnnotationStringValue(field, "javax.persistence.Column", "name")
        );
        return resolved != null ? resolved : NameUtils.camelToSnake(field.getName());
    }

    private String inferSourceIdColumn(Class<?> type, EncryptField encryptField) {
        String explicitSourceIdProperty = blankToNull(encryptField.sourceIdProperty());
        if (explicitSourceIdProperty != null) {
            return NameUtils.camelToSnake(explicitSourceIdProperty);
        }
        Field idField = resolveIdField(type);
        return idField != null ? resolveColumnName(idField) : "id";
    }

    private String inferSourceIdProperty(Class<?> type, String sourceIdColumn) {
        if (sourceIdColumn == null || sourceIdColumn.isBlank()) {
            return "id";
        }
        String normalizedSourceIdColumn = NameUtils.normalizeIdentifier(sourceIdColumn);
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> normalizedSourceIdColumn.equals(NameUtils.normalizeIdentifier(resolveColumnName(field))))
                .map(Field::getName)
                .findFirst()
                .orElse("id");
    }

    private Field resolveIdField(Class<?> type) {
        Field namedIdField = Arrays.stream(type.getDeclaredFields())
                .filter(field -> "id".equals(field.getName()))
                .findFirst()
                .orElse(null);
        if (namedIdField != null) {
            return namedIdField;
        }
        return Arrays.stream(type.getDeclaredFields())
                .filter(this::isIdField)
                .findFirst()
                .orElse(null);
    }

    private boolean isIdField(Field field) {
        return hasAnnotation(field, "com.baomidou.mybatisplus.annotation.TableId")
                || hasAnnotation(field, "jakarta.persistence.Id")
                || hasAnnotation(field, "javax.persistence.Id");
    }

    private boolean hasAnnotation(Field field, String className) {
        return Arrays.stream(field.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getName().equals(className));
    }

    private String extractAnnotationStringValue(Object source, String className, String attributeName) {
        Annotation[] annotations = source instanceof Class<?>
                ? ((Class<?>) source).getAnnotations()
                : ((Field) source).getAnnotations();
        for (Annotation annotation : annotations) {
            if (!annotation.annotationType().getName().equals(className)) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod(attributeName).invoke(annotation);
                return value instanceof String string && !string.isBlank() ? string : null;
            } catch (ReflectiveOperationException ignore) {
                return null;
            }
        }
        return null;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
