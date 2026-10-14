package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MemTableRecordTest {
    @Test
    void rejectsNullKey() {
        assertThrows(NullPointerException.class,
                () -> new MemTableRecord(null, Tombstone.INSTANCE));
    }

    @Test
    void rejectsNullEntry() {
        assertThrows(NullPointerException.class,
                () -> new MemTableRecord(ByteKey.copyOf(new byte[0]), null));
    }
}
