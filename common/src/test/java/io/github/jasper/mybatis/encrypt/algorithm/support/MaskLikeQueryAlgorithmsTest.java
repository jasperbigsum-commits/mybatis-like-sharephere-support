package io.github.jasper.mybatis.encrypt.algorithm.support;

import io.github.jasper.mybatis.encrypt.exception.EncryptionConfigurationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaskLikeQueryAlgorithmsTest {

    @Test
    void shouldKeepFirstNLastM() {
        KeepFirstNLastMLikeQueryAlgorithm algorithm = new KeepFirstNLastMLikeQueryAlgorithm(3, 4);

        assertEquals("138****8000", algorithm.transform("13800138000"));
        assertEquals("abc", algorithm.transform("abc"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    @Test
    void shouldKeepFromXToY() {
        KeepFromXToYLikeQueryAlgorithm algorithm = new KeepFromXToYLikeQueryAlgorithm(3, 6);

        assertEquals("***0013****", algorithm.transform("13800138000"));
        assertEquals("***12", algorithm.transform("abc12"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    @Test
    void shouldMaskFirstNLastM() {
        MaskFirstNLastMLikeQueryAlgorithm algorithm = new MaskFirstNLastMLikeQueryAlgorithm(3, 4);

        assertEquals("***0013****", algorithm.transform("13800138000"));
        assertEquals("***", algorithm.transform("abc"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    @Test
    void shouldMaskFromXToY() {
        MaskFromXToYLikeQueryAlgorithm algorithm = new MaskFromXToYLikeQueryAlgorithm(3, 6);

        assertEquals("138****8000", algorithm.transform("13800138000"));
        assertEquals("abc**", algorithm.transform("abc12"));
        assertEquals("", algorithm.transform(""));
        assertNull(algorithm.transform(null));
    }

    @Test
    void shouldSupportCustomReplaceChar() {
        assertEquals("138####8000", new KeepFirstNLastMLikeQueryAlgorithm(3, 4, '#').transform("13800138000"));
        assertEquals("138####8000", new MaskFromXToYLikeQueryAlgorithm(3, 6, '#').transform("13800138000"));
    }

    @Test
    void shouldRejectInvalidRangeArguments() {
        assertThrows(EncryptionConfigurationException.class, () -> new KeepFromXToYLikeQueryAlgorithm(5, 3));
        assertThrows(EncryptionConfigurationException.class, () -> new MaskFromXToYLikeQueryAlgorithm(-1, 3));
        assertThrows(EncryptionConfigurationException.class, () -> new MaskFirstNLastMLikeQueryAlgorithm(-1, 1));
    }
}
