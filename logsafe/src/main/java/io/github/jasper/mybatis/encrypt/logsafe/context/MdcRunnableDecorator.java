package io.github.jasper.mybatis.encrypt.logsafe.context;

import java.util.Objects;

/**
 * Propagates the caller MDC into a decorated {@link Runnable} and restores the worker MDC afterward.
 */
public class MdcRunnableDecorator {

    /**
     * Captures the current MDC and returns a runnable that applies it while the delegate runs.
     *
     * @param runnable delegate task
     * @return MDC-aware runnable
     */
    public Runnable decorate(final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        final MdcSnapshot callerSnapshot = MdcSnapshot.capture();
        return new Runnable() {
            @Override
            public void run() {
                MdcSnapshot workerSnapshot = MdcSnapshot.capture();
                callerSnapshot.restore();
                try {
                    runnable.run();
                } finally {
                    workerSnapshot.restore();
                }
            }
        };
    }
}
