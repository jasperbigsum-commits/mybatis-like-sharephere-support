package io.github.jasper.mybatis.encrypt.web;

import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Applies sensitive response masking just before HTTP message conversion.
 *
 * <p>The advice is deliberately thin. Whether masking is required has already been decided by
 * {@link SensitiveResponseContextInterceptor}, and the actual replacement logic lives in
 * {@link SensitiveDataMasker}. This keeps the MVC integration layer free of masking rules.</p>
 */
@ControllerAdvice
public class SensitiveResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final SensitiveDataMasker sensitiveDataMasker;

    /**
     * Creates response advice that delegates masking to {@link SensitiveDataMasker}.
     *
     * @param sensitiveDataMasker masker used to mutate response DTOs in place
     */
    public SensitiveResponseBodyAdvice(SensitiveDataMasker sensitiveDataMasker) {
        this.sensitiveDataMasker = sensitiveDataMasker;
    }

    /**
     * Enables the advice only when the current request scope requires masking.
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return SensitiveDataContext.shouldMask();
    }

    /**
     * Mutates the response object in place and returns the same reference for message conversion.
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        return sensitiveDataMasker.mask(body);
    }
}
