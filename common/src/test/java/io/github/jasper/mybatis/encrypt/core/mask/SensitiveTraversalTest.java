package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.annotation.SensitiveField;
import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SensitiveTraversalTest {
    @Test
    void annotatedGraphSkipsClassLoaderButMasksNestedBusinessFields() throws Exception {
        try (AppLoader loader = new AppLoader();
             SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, SensitiveResponseStrategy.ANNOTATED_FIELDS)) {
            Person person = new Person();
            Map<String, Object> model = model(loader, person);
            assertDoesNotThrow(() -> new SensitiveDataMasker().mask(model));
            assertEquals("*******8000", person.phone);
            assertEquals("13800138000", person.backupPhone);
            assertEquals("13800138000", loader.internal.phone);
        }
    }

    @Test
    void recordedCopyTraversalSkipsClassLoaderAndPreservesMatchingRules() throws Exception {
        try (AppLoader loader = new AppLoader();
             SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            Person source = new Person();
            Person copy = new Person();
            SensitiveDataContext.record(source, "phone", source.phone, null);
            Map<String, Object> model = model(loader, copy);
            assertDoesNotThrow(() -> new SensitiveDataMasker().mask(model));
            assertEquals("*******8000", source.phone);
            assertEquals("*******8000", copy.phone);
            assertEquals("13800138000", copy.backupPhone);
            assertEquals("13800138000", loader.internal.phone);
        }
    }

    @Test
    void visitsBusinessFieldsAbovePlatformSuperclassWithoutOpeningPlatformFields() {
        BusinessException value = new BusinessException();
        maskerFor(BusinessException.class).maskAnnotatedObjectGraph(value);
        assertEquals("*******8000", value.person.phone);
    }

    @Test
    void invalidExplicitSensitiveFieldStillFailsFast() {
        assertThrows(EncryptionConfigurationException.class,
                () -> new SensitiveDataMasker().maskAnnotatedObjectGraph(new InvalidPerson()));
    }

    @Test
    void syntheticOuterReferenceDoesNotEscapeResponseGraph() {
        Outer outer = new Outer();
        Outer.Response response = outer.new Response();
        maskerFor(Outer.Response.class).maskAnnotatedObjectGraph(response);
        assertEquals("*******8000", response.person.phone);
        assertEquals("13800138000", outer.unrelated.phone);
    }

    @Test
    void platformTypesOutsideJavaPackagesAreOpaqueForEveryStrategy() throws Exception {
        for (SensitiveResponseStrategy strategy : SensitiveResponseStrategy.values()) {
            try (SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, strategy)) {
                Person source = new Person();
                Person copy = new Person();
                BusinessLocator locator = new BusinessLocator();
                locator.setPublicId("original-public-id");
                Map<String, Object> model = new LinkedHashMap<String, Object>();
                model.put("sax", new org.xml.sax.helpers.LocatorImpl());
                model.put("businessLocator", locator);
                model.put("dom", javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().newDocument().createElement("root"));
                model.put("rows", Arrays.asList(new Object[] {copy}));
                SensitiveDataContext.record(source, "phone", source.phone, null);
                assertDoesNotThrow(() -> maskerFor(BusinessLocator.class).mask(model));
                assertEquals("*******8000", copy.phone);
                assertEquals("*******8000", locator.person.phone);
                assertEquals("original-public-id", locator.getPublicId());
                assertEquals("13800138000", copy.backupPhone);
            }
        }
    }

    static class BusinessLocator extends org.xml.sax.helpers.LocatorImpl {
        Person person = new Person();
    }

    private SensitiveDataMasker maskerFor(Class<?>... types) {
        return new SensitiveDataMasker(null, null, null,
                SensitiveTraversalPolicy.builder().allowTypes(types).build());
    }

    @Test
    void unknownObjectsRemainOpaqueEvenWhenTheyContainAnnotatedDtos() {
        Outer helper = new Outer();
        new SensitiveDataMasker().maskAnnotatedObjectGraph(helper);
        assertEquals("13800138000", helper.unrelated.phone);
    }

    @Test
    void approvedSubclassDoesNotAuthorizeThirdPartySuperclassFields() {
        ApprovedWrapper wrapper = new ApprovedWrapper();
        maskerFor(ApprovedWrapper.class).maskAnnotatedObjectGraph(wrapper);
        assertEquals("*******8000", wrapper.data.phone);
        assertEquals("13800138000", wrapper.internal.phone);
    }

    @Test
    void adaptersVisitOnlyPublicPayloadForEveryStrategy() {
        for (SensitiveResponseStrategy strategy : SensitiveResponseStrategy.values()) {
            try (SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, strategy)) {
                Person source = new Person();
                ApprovedWrapper wrapper = new ApprovedWrapper();
                SensitiveDataContext.record(source, "phone", source.phone, null);
                SensitiveTraversalPolicy policy = SensitiveTraversalPolicy.builder()
                        .adapt(ApprovedWrapper.class, value -> value.data).build();
                new SensitiveDataMasker(null, null, null, policy).mask(wrapper);
                assertEquals("*******8000", wrapper.data.phone);
                assertEquals("13800138000", wrapper.internal.phone);
            }
        }
    }

    static class ThirdPartyWrapper { Person internal = new Person(); }
    static class ApprovedWrapper extends ThirdPartyWrapper { Person data = new Person(); }

    static class Outer {
        Person unrelated = new Person();
        class Response {
            Person person = new Person();
        }
    }

    private Map<String, Object> model(AppLoader loader, Person person) {
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        model.put("loader", loader);
        model.put("rows", Arrays.asList(new Object[] {person}));
        model.put("cycle", model);
        return model;
    }

    static class AppLoader extends URLClassLoader {
        final Person internal = new Person();
        AppLoader() { super(new URL[0]); }
    }
    static class BasePerson {
        @SensitiveField String phone = "13800138000";
    }
    static class Person extends BasePerson {
        String backupPhone = "13800138000";
    }
    static class BusinessException extends RuntimeException {
        Person person = new Person();
    }
    static class InvalidPerson {
        @SensitiveField int phone;
    }
}
