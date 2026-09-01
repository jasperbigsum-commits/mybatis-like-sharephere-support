package io.github.jasper.mybatis.encrypt.web;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveRequestHydration;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Rewrites sensitive request metadata before Jackson binds controller arguments.
 */
@ControllerAdvice
public class SensitiveRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private final SensitiveRequestPayloadResolver payloadResolver;

    /**
     * Creates request body advice backed by the shared sensitive payload resolver.
     *
     * @param payloadResolver resolver used to rewrite supported request bodies
     */
    public SensitiveRequestBodyAdvice(SensitiveRequestPayloadResolver payloadResolver) {
        this.payloadResolver = payloadResolver;
    }

    @Override
    public boolean supports(MethodParameter methodParameter,
                            Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return hasHydrationAnnotation(methodParameter);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage,
                                           MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        MediaType contentType = inputMessage.getHeaders().getContentType();
        if (contentType == null) {
            return inputMessage;
        }
        boolean json = MediaType.APPLICATION_JSON.includes(contentType);
        boolean form = MediaType.APPLICATION_FORM_URLENCODED.includes(contentType);
        if (!json && !form) {
            return inputMessage;
        }
        Charset charset = contentType.getCharset() == null ? StandardCharsets.UTF_8 : contentType.getCharset();
        String body = StreamUtils.copyToString(inputMessage.getBody(), charset);
        String rewritten;
        if (json) {
            rewritten = payloadResolver.rewrite(body, charset);
        } else {
            rewritten = payloadResolver.rewriteForm(body, charset);
        }
        return new RewrittenHttpInputMessage(inputMessage.getHeaders(), rewritten, charset);
    }

    private boolean hasHydrationAnnotation(MethodParameter methodParameter) {
        if (methodParameter == null) {
            return false;
        }
        Method method = methodParameter.getMethod();
        if (method != null && AnnotatedElementUtils.findMergedAnnotation(
                method, SensitiveRequestHydration.class) != null) {
            return true;
        }
        Class<?> containingClass = methodParameter.getContainingClass();
        return AnnotatedElementUtils.findMergedAnnotation(
                containingClass, SensitiveRequestHydration.class) != null;
    }

    private static final class RewrittenHttpInputMessage implements HttpInputMessage {

        private final HttpHeaders headers;
        private final byte[] body;

        private RewrittenHttpInputMessage(HttpHeaders sourceHeaders, String body, Charset charset) {
            this.headers = new HttpHeaders();
            this.headers.putAll(sourceHeaders);
            this.body = (body == null ? "" : body).getBytes(charset);
            this.headers.setContentLength(this.body.length);
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
