package io.github.jasper.mybatis.encrypt.logsafe.context.async;

import io.github.jasper.mybatis.encrypt.logsafe.context.MdcRunnableDecorator;
import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} adapter for logsafe MDC propagation.
 */
public class MdcTaskDecorator implements TaskDecorator {

    private final MdcRunnableDecorator delegate;

    /**
     * Creates a task decorator backed by a default MDC runnable decorator.
     */
    public MdcTaskDecorator() {
        this(new MdcRunnableDecorator());
    }

    /**
     * Creates a task decorator backed by the supplied MDC runnable decorator.
     *
     * @param delegate delegate that captures and restores MDC context
     */
    public MdcTaskDecorator(MdcRunnableDecorator delegate) {
        this.delegate = delegate;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        return delegate.decorate(runnable);
    }
}
