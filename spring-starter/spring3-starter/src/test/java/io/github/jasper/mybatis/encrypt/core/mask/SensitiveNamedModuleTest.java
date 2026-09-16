package io.github.jasper.mybatis.encrypt.core.mask;

import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a real named module with exports but no opens, not a simulated reflection failure. */
class SensitiveNamedModuleTest {
    @TempDir Path directory;

    @Test
    void closedModuleIsOpaqueUntilRegisteredThenUsesPublicAccessors() throws Exception {
        Class<?> type = compile("public String getPhone() { return phone; } "
                + "public void setPhone(String value) { phone = value; }");
        Object source = type.getConstructor().newInstance();
        Object copy = type.getConstructor().newInstance();
        assertFalse(type.getModule().isOpen("fixture.api", SensitiveDataMasker.class.getModule()));
        assertThrows(java.lang.reflect.InaccessibleObjectException.class,
                () -> type.getDeclaredField("phone").setAccessible(true));
        assertDoesNotThrow(() -> new SensitiveDataMasker().maskAnnotatedObjectGraph(copy));
        SensitiveDataMasker masker = new SensitiveDataMasker(
                records -> Collections.singletonMap(records.iterator().next(), "*******8000"), null, null,
                SensitiveTraversalPolicy.builder().allowTypes(type).build());
        try (SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            SensitiveDataContext.record(source, "phone", "13800138000", null);
            assertDoesNotThrow(() -> masker.mask(copy));
        }
        assertEquals("*******8000", type.getMethod("getPhone").invoke(source));
        assertEquals("*******8000", type.getMethod("getPhone").invoke(copy));
    }

    @Test
    void selectedFieldWithoutAccessorsFailsClosedWithNoPlaintextDiagnostics() throws Exception {
        Class<?> type = compile("");
        Object source = type.getConstructor().newInstance();
        SensitiveDataMasker masker = new SensitiveDataMasker(
                records -> Collections.singletonMap(records.iterator().next(), "*******8000"), null);
        try (SensitiveDataContext.Scope scope = SensitiveDataContext.open(false, SensitiveResponseStrategy.RECORDED_ONLY)) {
            SensitiveDataContext.record(source, "phone", "13800138000", null);
            EncryptionConfigurationException error = assertThrows(EncryptionConfigurationException.class,
                    () -> masker.mask(source));
            assertTrue(error.getMessage().contains("fixture.api.Dto.phone"));
            assertFalse(error.getMessage().contains("13800138000"));
            assertNull(error.getCause());
        }
    }

    private Class<?> compile(String accessors) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("src/fixture/api"));
        Path descriptor = directory.resolve("src/module-info.java");
        Files.writeString(descriptor, "module fixture { exports fixture.api; }");
        Path dto = sources.resolve("Dto.java");
        Files.writeString(dto, "package fixture.api; public class Dto { private String phone = \"13800138000\"; "
                + accessors + " }");
        Path output = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", output.toString(), descriptor.toString(), dto.toString()));
        ModuleFinder finder = ModuleFinder.of(output);
        java.lang.module.Configuration config = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of("fixture"));
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(config, getClass().getClassLoader());
        return layer.findLoader("fixture").loadClass("fixture.api.Dto");
    }
}
