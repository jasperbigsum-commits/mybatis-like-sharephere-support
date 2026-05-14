package io.github.jasper.mybatis.encrypt.logsafe;

/**
 * Immutable wrapper whose {@link #toString()} is safe for log output.
 */
public final class MaskedLogValue {

    private final String text;

    public MaskedLogValue(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
