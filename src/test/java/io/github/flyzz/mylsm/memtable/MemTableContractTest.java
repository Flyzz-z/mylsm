package io.github.flyzz.mylsm.memtable;

import io.github.flyzz.mylsm.model.ByteKey;
import io.github.flyzz.mylsm.model.Tombstone;
import io.github.flyzz.mylsm.model.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 只依赖接口行为；新增实现可继承此测试并提供实例工厂。 */
abstract class MemTableContractTest {
    private MemTable table;

    protected abstract MemTable createMemTable();

    @BeforeEach
    void setUp() {
        table = createMemTable();
    }

    @Test
    void unknownKeysRemainAbsentAcrossReads() {
        assertEquals(Optional.empty(), table.get(key(1)));
        assertEquals(Optional.empty(), table.get(key(1)));
        table.put(key(2), value(20));
        assertEquals(Optional.empty(), table.get(key(1)));
        assertEquals(Optional.of(value(20)), table.get(key(2)));
    }

    @Test
    void retrievesValueUsingAnEqualKeyObject() {
        table.put(key(1), value(10));

        assertEquals(Optional.of(value(10)), table.get(key(1)));
        assertEquals(Optional.of(value(10)), table.get(key(1)));
    }

    @Test
    void latestPutReplacesPreviousValue() {
        table.put(key(1), value(10));
        table.put(key(1), value(20));

        assertEquals(Optional.of(value(20)), table.get(key(1)));
    }

    @Test
    void deleteReplacesValueWithTombstone() {
        table.put(key(1), value(10));
        table.delete(key(1));

        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(1)));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(1)));
    }

    @Test
    void deleteOfAbsentKeyStillStoresTombstone() {
        table.delete(key(1));

        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(1)));
        assertEquals(Optional.empty(), table.get(key(2)));
    }

    @Test
    void repeatedDeleteKeepsTombstone() {
        table.put(key(1), value(10));
        table.delete(key(1));
        table.delete(key(1));

        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(1)));
    }

    @Test
    void putAfterDeleteRestoresAValue() {
        table.delete(key(1));
        table.put(key(1), value(20));

        assertEquals(Optional.of(value(20)), table.get(key(1)));
    }

    @Test
    void operationsDoNotAffectOtherKeysIncludingHighBytesAndPrefixes() {
        ByteKey prefix = ByteKey.copyOf(new byte[]{1, 0});
        table.put(key(1), value(10));
        table.put(prefix, value(20));
        table.put(key(0x80), value(30));
        table.put(key(0xff), value(40));

        table.delete(key(1));
        table.put(key(0x80), value(50));

        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(1)));
        assertEquals(Optional.of(value(20)), table.get(prefix));
        assertEquals(Optional.of(value(50)), table.get(key(0x80)));
        assertEquals(Optional.of(value(40)), table.get(key(0xff)));
    }

    @Test
    void emptyKeyAndValueRemainDistinctFromDeletionAndAbsence() {
        ByteKey emptyKey = ByteKey.copyOf(new byte[0]);
        Value emptyValue = Value.copyOf(new byte[0]);

        assertEquals(Optional.empty(), table.get(emptyKey));
        table.put(emptyKey, emptyValue);
        assertEquals(Optional.of(emptyValue), table.get(emptyKey));
        table.delete(emptyKey);
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(emptyKey));
        table.put(emptyKey, emptyValue);
        assertEquals(Optional.of(emptyValue), table.get(emptyKey));
    }

    @Test
    void nullArgumentsFailWithoutChangingAnyState() {
        table.put(key(1), value(10));
        table.delete(key(2));

        assertThrows(NullPointerException.class, () -> table.put(null, value(20)));
        assertThrows(NullPointerException.class, () -> table.put(key(1), null));
        assertThrows(NullPointerException.class, () -> table.put(key(2), null));
        assertThrows(NullPointerException.class, () -> table.put(key(3), null));
        assertThrows(NullPointerException.class, () -> table.get(null));
        assertThrows(NullPointerException.class, () -> table.delete(null));

        assertEquals(Optional.of(value(10)), table.get(key(1)));
        assertEquals(Optional.of(Tombstone.INSTANCE), table.get(key(2)));
        assertEquals(Optional.empty(), table.get(key(3)));
    }

    @Test
    void externalArrayChangesCannotModifyStoredData() {
        byte[] keyBytes = {1};
        byte[] valueBytes = {10};
        ByteKey key = ByteKey.copyOf(keyBytes);
        table.put(key, Value.copyOf(valueBytes));

        keyBytes[0] = 2;
        valueBytes[0] = 20;
        key.toByteArray()[0] = 3;
        Value returned = assertInstanceOf(Value.class, table.get(key(1)).orElseThrow());
        returned.toByteArray()[0] = 30;

        assertEquals(Optional.of(value(10)), table.get(key(1)));
        assertEquals(Optional.empty(), table.get(key(2)));
        assertEquals(Optional.empty(), table.get(key(3)));
    }

    @Test
    void instancesHaveIndependentState() {
        MemTable other = createMemTable();
        table.put(key(1), value(10));
        table.delete(key(2));

        assertEquals(Optional.empty(), other.get(key(1)));
        assertEquals(Optional.empty(), other.get(key(2)));
        other.put(key(1), value(20));
        assertEquals(Optional.of(value(10)), table.get(key(1)));
        assertEquals(Optional.of(value(20)), other.get(key(1)));
    }

    private static ByteKey key(int value) {
        return ByteKey.copyOf(new byte[]{(byte) value});
    }

    private static Value value(int value) {
        return Value.copyOf(new byte[]{(byte) value});
    }
}
