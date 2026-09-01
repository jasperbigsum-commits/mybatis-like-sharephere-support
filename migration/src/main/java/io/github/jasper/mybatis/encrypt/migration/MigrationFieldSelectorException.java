package io.github.jasper.mybatis.encrypt.migration;

/**
 * Raised when field selectors do not match any registered encrypted field.
 */
public class MigrationFieldSelectorException extends MigrationDefinitionException {

    /**
     * Create a selector resolution exception.
     *
     * @param message error message
     */
    public MigrationFieldSelectorException(String message) {
        super(MigrationErrorCode.FIELD_SELECTOR_UNRESOLVED, message);
    }
}
