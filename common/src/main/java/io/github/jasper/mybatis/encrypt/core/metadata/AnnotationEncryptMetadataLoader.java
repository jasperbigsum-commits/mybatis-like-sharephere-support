package io.github.jasper.mybatis.encrypt.core.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import io.github.jasper.mybatis.encrypt.annotation.EncryptField;
import io.github.jasper.mybatis.encrypt.annotation.EncryptTable;
import io.github.jasper.mybatis.encrypt.util.NameUtils;
import io.github.jasper.mybatis.encrypt.util.StringUtils;

/**
 * 从实体注解中加载加密元数据。
 *
 * <p>{@link EncryptField#column()} 的列名解析顺序为：显式配置的 {@code @EncryptField.column}、
 * MyBatis-Plus 的 {@code @TableField(value)}、JPA 的 {@code @Column(name)}，最后退回到属性名 snake_case。</p>
 *
 * <p>如果一个 DTO 同时承接多张表的字段，可以不给类声明 {@link EncryptTable}，
 * 而是直接在各个 {@link EncryptField} 上通过 {@link EncryptField#table()} 指定来源表。
 * 这样既保留按属性解密的能力，又不会把整个 DTO 误绑定到某一张表。</p>
 */
public class AnnotationEncryptMetadataLoader {

    /**
     * 从实体类型上读取并构建表级加密规则。
     *
     * @param type 实体类型
     * @return 加密表规则；当实体未声明任何加密字段时返回 {@code null}
     */
    public EncryptTableRule load(Class<?> type) {
        String tableName = resolveTableName(type);
        EncryptTableRule rule = new EncryptTableRule(tableName);
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
                    blankToDefault(encryptField.table(), tableName),
                    column,
                    encryptField.cipherAlgorithm(),
                    blankToNull(encryptField.assistedQueryColumn()),
                    blankToNull(encryptField.assistedQueryAlgorithm()),
                    blankToNull(encryptField.likeQueryColumn()),
                    blankToNull(encryptField.likeQueryAlgorithm()),
                    blankToNull(encryptField.maskedColumn()),
                    blankToNull(encryptField.maskedAlgorithm()),
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
        if (encryptTable != null && StringUtils.isNotBlank(encryptTable.value())) {
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
                if (value instanceof String && StringUtils.isNotBlank((String) value)) {
                    return (String) value;
                }
                return null;
            } catch (ReflectiveOperationException ignore) {
                return null;
            }
        }
        return null;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }
}
