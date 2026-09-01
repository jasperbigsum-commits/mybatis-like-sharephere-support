package io.github.jasper.mybatis.encrypt.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasper.mybatis.encrypt.core.lookup.SensitivePlaintextLookupService;
import io.github.jasper.mybatis.encrypt.core.mask.SensitiveDataContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
@Tag("web")
class SensitiveRequestPayloadResolverTest {

    @Test
    void shouldResolveSensitiveSubmitMetaIntoPlaintextFields() throws Exception {
        String body = "{\"name\":\"Alice\",\"sensitiveSubmitMeta\":{\"phone\":{\"sid\":\"SID\",\"pid\":\"PID\","
                + "\"vid\":\"U-1\",\"hash\":\"HASH\",\"state\":\"unchangedMasked\"}}}";
        RecordingLookupService lookup = new RecordingLookupService("13800138000");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewrite(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("\"phone\":\"13800138000\""));
        assertFalse(rewritten.contains("sensitiveSubmitMeta"));
        assertEquals(1, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
        assertEquals("SID", lookup.lastLookupMeta.getSid());
        assertEquals("PID", lookup.lastLookupMeta.getPid());
        assertEquals("U-1", lookup.lastLookupMeta.getVid());
        assertEquals("HASH", lookup.lastLookupMeta.getHash());
    }

    @Test
    void shouldResolveFormSensitiveSubmitMetaIntoPlaintextFields() {
        String body = "name=Alice&sensitiveSubmitMeta%5Bphone%5D%5Bsid%5D=SID"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bpid%5D=PID"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bvid%5D=U-1"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bhash%5D=HASH"
                + "&sensitiveSubmitMeta%5Bphone%5D%5Bstate%5D=unchangedMasked";
        RecordingLookupService lookup = new RecordingLookupService("13800138000");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewriteForm(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("name=Alice"));
        assertTrue(rewritten.contains("phone=13800138000"));
        assertFalse(rewritten.contains("sensitiveSubmitMeta"));
        assertEquals(1, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
        assertEquals("SID", lookup.lastLookupMeta.getSid());
        assertEquals("PID", lookup.lastLookupMeta.getPid());
        assertEquals("U-1", lookup.lastLookupMeta.getVid());
        assertEquals("HASH", lookup.lastLookupMeta.getHash());
    }

    @Test
    void shouldUseCurrentValueWhenFormLegacySensitiveInputWasChanged() {
        String body = "phone%5Bvalue%5D=13900139000"
                + "&phone%5BmaskedValue%5D=138****8000"
                + "&phone%5BlookupMeta%5D%5Bsid%5D=SID"
                + "&phone%5BlookupMeta%5D%5Bpid%5D=PID"
                + "&phone%5BlookupMeta%5D%5Bvid%5D=U-2"
                + "&phone%5BlookupMeta%5D%5Bhash%5D=HASH-2"
                + "&phone%5Bstate%5D=changed";
        RecordingLookupService lookup = new RecordingLookupService("old-value");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewriteForm(body, StandardCharsets.UTF_8);

        assertEquals("phone=13900139000", rewritten);
        assertEquals(0, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
    }

    @Test
    void shouldRewriteLegacySensitiveInputObjectToPlaintextValue() throws Exception {
        String body = "{\"phone\":{\"value\":\"138****8000\",\"maskedValue\":\"138****8000\","
                + "\"lookupMeta\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-2\",\"hash\":\"HASH-2\"},"
                + "\"state\":\"masked\"}}";
        RecordingLookupService lookup = new RecordingLookupService("13800138001");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewrite(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("\"phone\":\"13800138001\""));
        assertEquals(1, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
        assertEquals("U-2", lookup.lastLookupMeta.getVid());
    }

    @Test
    void shouldUseCurrentValueWhenLegacySensitiveInputWasChanged() throws Exception {
        String body = "{\"phone\":{\"value\":\"13900139000\",\"maskedValue\":\"138****8000\","
                + "\"lookupMeta\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-2\",\"hash\":\"HASH-2\"},"
                + "\"state\":\"changed\"}}";
        RecordingLookupService lookup = new RecordingLookupService("old-value");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewrite(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("\"phone\":\"13900139000\""));
        assertEquals(0, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
    }

    @Test
    void shouldPreferSubmitMetaWhenBothPayloadShapesExistForSameField() throws Exception {
        String body = "{\"phone\":{\"value\":\"138****8000\",\"maskedValue\":\"138****8000\","
                + "\"lookupMeta\":{\"sid\":\"LEGACY\",\"pid\":\"PID\",\"vid\":\"U-OLD\",\"hash\":\"HASH-OLD\"},"
                + "\"state\":\"masked\"},\"sensitiveSubmitMeta\":{\"phone\":{\"sid\":\"META\",\"pid\":\"PID\","
                + "\"vid\":\"U-NEW\",\"hash\":\"HASH-NEW\",\"state\":\"unchangedMasked\"}}}";
        RecordingLookupService lookup = new RecordingLookupService("13800138002");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewrite(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("\"phone\":\"13800138002\""));
        assertEquals(1, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
        assertEquals("META", lookup.lastLookupMeta.getSid());
        assertEquals("U-NEW", lookup.lastLookupMeta.getVid());
    }

    @Test
    void shouldLeaveObjectWithLookupMetaButNoSensitiveStateUnchanged() throws Exception {
        String body = "{\"profile\":{\"lookupMeta\":{\"sid\":\"SID\",\"pid\":\"PID\",\"vid\":\"U-3\",\"hash\":\"HASH-3\"}}}";
        RecordingLookupService lookup = new RecordingLookupService("13800138003");

        String rewritten = new SensitiveRequestPayloadResolver(new ObjectMapper(), lookup)
                .rewrite(body, StandardCharsets.UTF_8);

        assertTrue(rewritten.contains("\"profile\":{\"lookupMeta\""));
        assertEquals(0, lookup.internalInvocations);
        assertEquals(0, lookup.externalInvocations);
    }

    private static final class RecordingLookupService implements SensitivePlaintextLookupService {

        private final String plaintext;
        private int externalInvocations;
        private int internalInvocations;
        private SensitiveDataContext.SensitiveLookupMeta lastLookupMeta;

        private RecordingLookupService(String plaintext) {
            this.plaintext = plaintext;
        }

        @Override
        public String lookup(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
            this.externalInvocations++;
            throw new AssertionError("request hydration must use internal lookup");
        }

        @Override
        public String lookupInternal(SensitiveDataContext.SensitiveLookupMeta lookupMeta) {
            this.internalInvocations++;
            this.lastLookupMeta = lookupMeta;
            return plaintext;
        }
    }
}
