package io.github.jasper.mybatis.encrypt.logsafe;

/**
 * Immutable wrapper whose {@link #toString()} is safe for log output.
 */
public final class MaskedLogValue {

    private final String text;

    /**
     * Creates a log-safe value wrapper.
     *
     * @param text pre-masked text to render
     */
    public MaskedLogValue(String text) {
        this.text = text;
    }

    /**
     * Returns the pre-masked text.
     *
     * @return log-safe text
     */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
