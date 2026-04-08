package io.github.jasper.mybatis.encrypt.core.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.util.NameUtils;

/**
 * 从实体注解中加载加密元数据。
 *
 * <p>{@link EncryptField#column()} 的列名解析顺序为：显式配置的 {@code @EncryptField.column}、
 * MyBatis-Plus 的 {@code @TableField(value)}、JPA 的 {@code @Column(name)}，最后退回到属性名 snake_case。</p>
 */
public class AnnotationEncryptMetadataLoader {

    /**
     * 从实体类型上读取并构建表级加密规则。
     *
     * @param type 实体类型
     * @return 加密表规则；当实体未声明任何加密字段时返回 {@code null}
     */
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
                    blankToDefault(encryptField.storageIdColumn(), "id")
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
