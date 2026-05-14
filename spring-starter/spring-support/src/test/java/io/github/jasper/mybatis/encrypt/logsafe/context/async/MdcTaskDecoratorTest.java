package io.github.jasper.mybatis.encrypt.logsafe.context.async;

import io.github.jasper.mybatis.encrypt.logsafe.context.MdcRunnableDecorator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("logsafe")
class MdcTaskDecoratorTest {

    @Test
    void shouldDelegateToFrameworkNeutralRunnableDecorator() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        MdcTaskDecorator decorator = new MdcTaskDecorator(new MdcRunnableDecorator() {
            @Override
            public Runnable decorate(final Runnable runnable) {
                delegated.set(true);
                return new Runnable() {
                    @Override
                    public void run() {
                        runnable.run();
                    }
                };
            }
        });
        AtomicBoolean ran = new AtomicBoolean(false);

        Runnable decorated = decorator.decorate(new Runnable() {
            @Override
            public void run() {
                ran.set(true);
            }
        });
        decorated.run();

        assertTrue(delegated.get());
        assertTrue(ran.get());
    }
}
