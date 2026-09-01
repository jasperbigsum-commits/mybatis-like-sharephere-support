package io.github.jasper.mybatis.encrypt.core.lookup;

/**
 * Audit hook for explicit plaintext lookup operations.
 */
public interface SensitivePlaintextAuditRecorder {

    /**
     * Records a plaintext lookup audit event.
     *
     * @param event structured audit event
     */
    void record(SensitivePlaintextAuditEvent event);

    /**
     * No-op recorder.
     *
     * @return no-op recorder
     */
    static SensitivePlaintextAuditRecorder noOp() {
        return new SensitivePlaintextAuditRecorder() {
            @Override
            public void record(SensitivePlaintextAuditEvent event) {
            }
        };
    }
}
