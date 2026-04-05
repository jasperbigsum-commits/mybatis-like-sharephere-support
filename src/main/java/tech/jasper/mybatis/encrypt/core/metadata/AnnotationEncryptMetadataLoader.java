package tech.jasper.mybatis.encrypt.core.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import tech.jasper.mybatis.encrypt.annotation.EncryptField;
import tech.jasper.mybatis.encrypt.annotation.EncryptTable;
import tech.jasper.mybatis.encrypt.util.NameUtils;

/**
 * 注解规则加载器。
 *
 * <p>负责从实体类上的 {@link EncryptTable} 和 {@link EncryptField} 注解中提取加密规则，
 * 并兼容读取 MyBatis-Plus 的 {@code @TableName}/{@code @TableField} 信息。</p>
 */
public class AnnotationEncryptMetadataLoader {

    /**
     * 从实体类加载表级与字段级加密规则。
     *
     * @param type 实体类型
     * @return 解析出的表规则；如果类上没有任何加密字段则返回 null
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
                    blankToDefault(encryptField.sourceIdProperty(), "id"),
                    blankToDefault(encryptField.sourceIdColumn(), NameUtils.camelToSnake(encryptField.sourceIdProperty())),
                    blankToDefault(encryptField.storageIdColumn(),
                            blankToDefault(encryptField.sourceIdColumn(), NameUtils.camelToSnake(encryptField.sourceIdProperty())))
            ));
        }
        return found ? rule : null;
    }

    private String resolveTableName(Class<?> type) {
        EncryptTable encryptTable = type.getAnnotation(EncryptTable.class);
        if (encryptTable != null && !encryptTable.value().isBlank()) {
            return encryptTable.value();
        }
        String tableName = extractThirdPartyAnnotationValue(type, "com.baomidou.mybatisplus.annotation.TableName");
        return tableName != null ? tableName : NameUtils.camelToSnake(type.getSimpleName());
    }

    private String resolveColumnName(Field field) {
        String tableField = extractThirdPartyAnnotationValue(field, "com.baomidou.mybatisplus.annotation.TableField");
        return tableField != null ? tableField : NameUtils.camelToSnake(field.getName());
    }

    private String extractThirdPartyAnnotationValue(Object source, String className) {
        Annotation[] annotations = source instanceof Class<?>
                ? ((Class<?>) source).getAnnotations()
                : ((Field) source).getAnnotations();
        for (Annotation annotation : annotations) {
            if (!annotation.annotationType().getName().equals(className)) {
                continue;
            }
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                return value instanceof String string && !string.isBlank() ? string : null;
            } catch (ReflectiveOperationException ignore) {
                return null;
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
