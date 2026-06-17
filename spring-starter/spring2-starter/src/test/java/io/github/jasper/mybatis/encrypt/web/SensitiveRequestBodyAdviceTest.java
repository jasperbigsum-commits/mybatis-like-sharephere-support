package io.github.jasper.mybatis.encrypt.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasper.mybatis.encrypt.annotation.SensitiveRequestHydration;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("web")
class SensitiveRequestBodyAdviceTest {

    @Test
    void shouldSupportOnlyAnnotatedControllerMethods() throws Exception {
        SensitiveRequestBodyAdvice advice = new SensitiveRequestBodyAdvice(
                new SensitiveRequestPayloadResolver(new ObjectMapper(), new FixedLookupService("13800138001")));

        assertTrue(advice.supports(methodParameter(SampleController.class, "annotated"),
                String.class, StringHttpMessageConverter.class));
        assertTrue(advice.supports(methodParameter(ClassAnnotatedController.class, "classAnnotated"),
                String.class, StringHttpMessageConverter.class));
        assertFalse(advice.supports(methodParameter(SampleController.class, "plain"),
                String.class, StringHttpMessageConverter.class));
    }

    @Test
    void shouldRewriteLegacySensitiveObjectBeforeSpring2Binding() throws Exception {
        SensitiveRequestBodyAdvice advice = new SensitiveRequestBodyAdvice(
                new SensitiveRequestPayloadResolver(new ObjectMapper(), new FixedLookupService("13800138001")));
        String body = "{\"phone\":{\"value\":\"138****8000\",\"maskedValue\":\"138****8000\","
                + "\"lookupMeta\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-2\",\"hash\":\"HASH-2\"},"
                + "\"state\":\"masked\"}}";

        HttpInputMessage rewritten = advice.beforeBodyRead(jsonMessage(body), null, null, null);
        String rewrittenBody = StreamUtils.copyToString(rewritten.getBody(), StandardCharsets.UTF_8);

        assertTrue(rewrittenBody.contains("\"phone\":\"13800138001\""));
        assertFalse(rewrittenBody.contains("lookupMeta"));
    }

    @Test
    void shouldRewriteFormSensitiveSubmitMetaBeforeSpring2Binding() throws Exception {
        SensitiveRequestBodyAdvice advice = new SensitiveRequestBodyAdvice(
                new SensitiveRequestPayloadResolver(new ObjectMapper(), new FixedLookupService("13800138001")));
        String body = "name=Alice&sensitiveSubmitMeta%5Bphone%5D%5Bsid%5D=SID"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bpid%5D=PID"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bvid%5D=U-2"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bhash%5D=HASH-2"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bstate%5D=unchangedMasked";

        HttpInputMessage rewritten = advice.beforeBodyRead(formMessage(body), null, null, null);
        String rewrittenBody = StreamUtils.copyToString(rewritten.getBody(), StandardCharsets.UTF_8);

        assertTrue(rewrittenBody.contains("name=Alice"));
        assertTrue(rewrittenBody.contains("phone=13800138001"));
        assertFalse(rewrittenBody.contains("sensitiveSubmitMeta"));
    }

    private HttpInputMessage jsonMessage(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new SimpleHttpInputMessage(body, headers);
    }

    private HttpInputMessage formMessage(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new SimpleHttpInputMessage(body, headers);
    }

    private MethodParameter methodParameter(Class<?> controllerType, String methodName) throws Exception {
        Method method = controllerType.getDeclaredMethod(methodName, String.class);
        return new MethodParameter(method, 0);
    }

    private static final class SimpleHttpInputMessage implements HttpInputMessage {

        private final byte[] body;
        private final HttpHeaders headers;

        private SimpleHttpInputMessage(String body, HttpHeaders headers) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.headers = headers;
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

    private static final class FixedLookupService implements SensitivePlaintextLookupService {

        private final String plaintext;

        private FixedLookupService(String plaintext) {
            this.plaintext = plaintext;
        }

        @Override
        public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
            return plaintext;
        }
    }

    private static class SampleController {

        @SensitiveRequestHydration
        void annotated(String body) {
        }

        void plain(String body) {
        }
    }

    @SensitiveRequestHydration
    private static class ClassAnnotatedController extends SampleController {

        void classAnnotated(String body) {
        }
    }
}
