package io.github.flyzz.mylsm.sstable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SsTableRecordTest {
    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class,
                () -> new SsTableRecord(null, Tombstone.INSTANCE));
        assertThrows(NullPointerException.class,
                () -> new SsTableRecord(ByteKey.copyOf(new byte[0]), null));
    }
}
