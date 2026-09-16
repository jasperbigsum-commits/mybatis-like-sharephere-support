package io.github.jasper.mybatis.encrypt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataMasker;
import io.github.jasper.mybatis.encrypt.web.SensitiveRequestBodyAdvice;
import io.github.jasper.mybatis.encrypt.web.SensitiveRequestPayloadResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("unit")
@Tag("config")
@Tag("web")
class SensitiveResponseAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SensitiveResponseAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SensitiveDataMasker.class, SensitiveDataMasker::new)
            .withBean(SensitivePlaintextLookupService.class, () -> new SensitivePlaintextLookupService() {
                @Override
                public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
                    return "plaintext";
                }
            });

    @Test
    void shouldMaskTemplateModelWithoutTraversingMvcInfrastructure() {
        contextRunner.run(context -> {
            for (io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy strategy
                    : io.github.jasper.mybatis.encrypt.core.mask.SensitiveResponseStrategy.values()) {
                TemplatePerson source = new TemplatePerson();
                TemplatePerson copy = new TemplatePerson();
                TemplatePerson helperOnly = new TemplatePerson();
                org.springframework.web.servlet.ModelAndView view =
                        new org.springframework.web.servlet.ModelAndView("template");
                view.addObject("person", copy);
                view.addObject("saxLocator", new org.xml.sax.helpers.LocatorImpl());
                view.addObject("org.springframework.validation.BindingResult.person",
                        new org.springframework.validation.BeanPropertyBindingResult(helperOnly, "person"));
                org.springframework.mock.web.MockHttpServletRequest request =
                        new org.springframework.mock.web.MockHttpServletRequest(context.getServletContext());
                request.setAttribute(org.springframework.web.servlet.DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE,
                        context.getSourceApplicationContext());
                view.addObject("requestContext", new org.springframework.web.servlet.support.RequestContext(request));
                try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0]) { };
                     SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, strategy)) {
                    view.addObject("loader", loader);
                    SensitiveDataContext.record(source, "phone", source.phone, null);
                    context.getBean(SensitiveDataMasker.class).mask(view.getModel());
                    org.junit.jupiter.api.Assertions.assertEquals("*******8000", copy.phone);
                    org.junit.jupiter.api.Assertions.assertEquals("13800138000", helperOnly.phone);
                }
            }
        });
    }

    static class TemplatePerson {
        @io.github.jasper.mybatis.encrypt.annotation.SensitiveField
        String phone = "13800138000";
    }

    @Test
    void shouldRegisterRequestHydrationBeansWhenLookupServiceExists() {
        contextRunner.run(context -> {
            assertNotNull(context.getBean(SensitiveRequestPayloadResolver.class));
            assertNotNull(context.getBean(SensitiveRequestBodyAdvice.class));
        });
    }
}
