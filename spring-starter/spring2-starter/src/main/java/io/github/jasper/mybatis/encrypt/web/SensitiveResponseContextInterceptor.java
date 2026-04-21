package io.github.jasper.mybatis.encrypt.web;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveResponse;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Opens and closes a sensitive response scope around annotated controller methods.
 *
 * <p>The interceptor mirrors {@link SensitiveDataContext}'s stack semantics at request level so
 * nested controller invocations in the same request do not overwrite each other's scope handles.
 * Async request continuations are intentionally treated as a new boundary; the current scope is
 * closed when concurrent handling starts and is not propagated to worker threads.</p>
 */
public class SensitiveResponseContextInterceptor implements AsyncHandlerInterceptor {

    private static final String SCOPES_ATTRIBUTE =
            SensitiveResponseContextInterceptor.class.getName() + ".SCOPES";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        SensitiveResponse annotation = resolveAnnotation(handler);
        if (annotation == null) {
            return true;
        }
        SensitiveDataContext.Scope scope =
                SensitiveDataContext.open(annotation.returnSensitive(), annotation.strategy());
        scopeStack(request).push(scope);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        closeScope(request, handler);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        closeScope(request, handler);
    }

    private void closeScope(HttpServletRequest request, Object handler) {
        if (resolveAnnotation(handler) == null) {
            return;
        }
        Object scopes = request.getAttribute(SCOPES_ATTRIBUTE);
        if (!(scopes instanceof Deque<?>)) {
            return;
        }
        Object scope = ((Deque<?>) scopes).pollFirst();
        if (scope instanceof SensitiveDataContext.Scope) {
            ((SensitiveDataContext.Scope) scope).close();
        }
        if (((Deque<?>) scopes).isEmpty()) {
            request.removeAttribute(SCOPES_ATTRIBUTE);
        }
    }

    @SuppressWarnings("unchecked")
    private Deque<SensitiveDataContext.Scope> scopeStack(HttpServletRequest request) {
        Object scopes = request.getAttribute(SCOPES_ATTRIBUTE);
        if (scopes instanceof Deque<?>) {
            return (Deque<SensitiveDataContext.Scope>) scopes;
        }
        Deque<SensitiveDataContext.Scope> stack = new ArrayDeque<SensitiveDataContext.Scope>();
        request.setAttribute(SCOPES_ATTRIBUTE, stack);
        return stack;
    }

    private SensitiveResponse resolveAnnotation(Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return null;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        SensitiveResponse methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), SensitiveResponse.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), SensitiveResponse.class);
    }
}
