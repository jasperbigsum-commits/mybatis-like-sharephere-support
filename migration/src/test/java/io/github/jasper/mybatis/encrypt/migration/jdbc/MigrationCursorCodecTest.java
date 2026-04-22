package io.github.jasper.mybatis.encrypt.migration.jdbc;

import io.github.jasper.mybatis.encrypt.migration.MigrationCursor;
import io.github.jasper.mybatis.encrypt.migration.MigrationCursorException;
import io.github.jasper.mybatis.encrypt.migration.MigrationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖游标编解码的核心约束，确保复合检查点的序列化形状稳定可恢复。
 */
@DisplayName("迁移游标编解码")
@Tag("unit")
@Tag("migration")
class MigrationCursorCodecTest {

    @Test
    void shouldDecodeCompositeCheckpointWithDeclaredTypes() {
        MigrationCursor cursor = MigrationCursorCodec.decode(
                Arrays.asList("tenant_id", "record_no"),
                Arrays.asList("tenantA", "2"),
                Arrays.asList(String.class.getName(), Long.class.getName()));

        assertEquals("tenantA", cursor.getValue("tenant_id"));
        assertEquals(2L, cursor.getValue("record_no"));
        assertEquals(Arrays.asList("tenantA", "2"), MigrationCursorCodec.stringify(cursor));
        assertEquals("{tenant_id=tenantA, record_no=2}", MigrationCursorCodec.display(cursor));
    }

    @Test
    void shouldRejectCheckpointShapeMismatch() {
        MigrationCursorException exception = assertThrows(MigrationCursorException.class, () ->
                MigrationCursorCodec.decode(
                        Arrays.asList("tenant_id", "record_no"),
                        Arrays.asList("tenantA"),
                        Arrays.asList(String.class.getName(), Long.class.getName())));

        assertEquals(MigrationErrorCode.CURSOR_CHECKPOINT_INVALID, exception.getErrorCode());
        assertEquals("Cursor checkpoint shape does not match cursor columns: [tenant_id, record_no]",
                exception.getMessage());
    }
}
