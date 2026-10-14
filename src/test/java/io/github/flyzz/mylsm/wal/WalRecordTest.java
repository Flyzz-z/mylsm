package io.github.flyzz.mylsm.wal;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalRecordTest {
    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new WalRecord(null, Tombstone.INSTANCE));
        assertThrows(NullPointerException.class,
                () -> new WalRecord(ByteKey.copyOf(new byte[0]), null));
    }

    @Test
    void contentsRemainIsolatedFromExternalArrays() {
        byte[] key = {1};
        byte[] value = {2};
        WalRecord record = new WalRecord(ByteKey.copyOf(key), Value.copyOf(value));
        key[0] = 3;
        value[0] = 4;
        record.key().toByteArray()[0] = 5;
        ((Value) record.entry()).toByteArray()[0] = 6;

        assertArrayEquals(new byte[]{1}, record.key().toByteArray());
        assertArrayEquals(new byte[]{2}, ((Value) record.entry()).toByteArray());
    }
}
